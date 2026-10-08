"""
Trains the block reader on recorded frames that carry the state stream.
Labels come from the mod's block state ids, the reader itself only gets rgb and depth
"""

from __future__ import annotations

import argparse
import json
import time
from pathlib import Path

import numpy as np
import torch
from mcdrone import list_episodes
from torch.nn import functional as F

from ..paths import CHECKPOINTS, DATA
from ..perception.reader import CLASSES, NOT_CROP, BlockReader, state_tables
from ..torch_utils import BestCheckpoint, autocast, pick_device, split_episodes, write_report

CACHE = "reader-frames"
FIELDS = ("rgb", "depth", "cls", "ripe")
# neighboring frames are nearly identical, keep every STRIDE-th
STRIDE = 3


def cache_episode(episode, classes: np.ndarray, ripe: np.ndarray) -> dict[str, np.ndarray] | None:
    cache = episode.path / CACHE
    if not (cache / "done").exists():
        frames = [n for n in range(0, len(episode.steps), STRIDE) if episode.has("state", n) and episode.has("rgb", n)]
        if not frames:
            return None
        parts: dict[str, list] = {k: [] for k in FIELDS}
        for n in frames:
            ids = episode.block_states(n)
            parts["rgb"].append(episode.rgb(n))
            parts["depth"].append(episode.depth(n).astype(np.float16))
            parts["cls"].append(classes[np.minimum(ids, len(classes) - 1)])
            parts["ripe"].append(ripe[np.minimum(ids, len(ripe) - 1)])
        cache.mkdir(exist_ok=True)
        for k, v in parts.items():
            np.save(cache / f"{k}.npy", np.stack(v))
        (cache / "done").touch()
    return {k: np.load(cache / f"{k}.npy", mmap_mode="r") for k in FIELDS}


def load_frames(data: Path) -> list[dict[str, np.ndarray]]:
    out = []
    for task_dir in sorted(p for p in data.iterdir() if (p / "state_names.json").exists()):
        classes, ripe = state_tables(json.loads((task_dir / "state_names.json").read_text(encoding="utf-8")))
        # an episode without an outcome is still being recorded
        episodes = [ep for ep in list_episodes(data, task_dir.name) if (ep.path / "state").is_dir() and ep.meta.get("outcome")]
        for ep in episodes:
            frames = cache_episode(ep, classes, ripe)
            if frames is not None:
                out.append(frames)
        print(f"{task_dir.name}: {len(episodes)} episodes", flush=True)
    if not out:
        raise SystemExit(f"no frames with the state stream in {data}, record with --streams rgb,depth,mask,state")
    return out


def sample(episodes, batch: int, rng: np.random.Generator, augment: bool) -> dict[str, torch.Tensor]:
    picks = rng.integers(0, len(episodes), size=batch)
    rows = {k: [] for k in FIELDS}
    for e in picks:
        ep = episodes[e]
        n = rng.integers(0, len(ep["cls"]))
        flip = augment and rng.random() < 0.5
        for k in FIELDS:
            v = np.asarray(ep[k][n])
            rows[k].append(v[:, ::-1].copy() if flip else v)
    out = {k: torch.from_numpy(np.stack(v)) for k, v in rows.items()}
    if augment:
        scale = torch.empty(batch, 1, 1, 1).uniform_(0.7, 1.3)
        out["rgb"] = (out["rgb"].float() * scale).clamp(0, 255).to(torch.uint8)
    return out


def class_weights(episodes, rng: np.random.Generator) -> torch.Tensor:
    """Inverse square root frequency, so floor and sky don't drown out a single block of bricks"""
    counts = np.zeros(len(CLASSES))
    for ep in episodes[:: max(1, len(episodes) // 40)]:
        counts += np.bincount(np.asarray(ep["cls"][:: 5]).ravel(), minlength=len(CLASSES))
    w = 1.0 / np.sqrt(np.maximum(counts / counts.sum(), 1e-5))
    return torch.tensor(w / w.mean(), dtype=torch.float32)


def evaluate(model, episodes, device, rng) -> dict[str, float]:
    model.eval()
    inter = np.zeros(len(CLASSES))
    union = np.zeros(len(CLASSES))
    correct = total = ripe_ok = ripe_n = 0
    with torch.no_grad():
        for ep in episodes:
            for start in range(0, len(ep["cls"]), 32):
                rgb = torch.from_numpy(np.asarray(ep["rgb"][start : start + 32])).to(device)
                depth = torch.from_numpy(np.asarray(ep["depth"][start : start + 32]).astype(np.float32)).to(device)
                cls = np.asarray(ep["cls"][start : start + 32])
                ripe = np.asarray(ep["ripe"][start : start + 32])
                logits, ripe_logits = model(rgb, depth)
                pred = logits.argmax(1).cpu().numpy()
                correct += (pred == cls).sum()
                total += cls.size
                for c in range(len(CLASSES)):
                    p, t = pred == c, cls == c
                    inter[c] += (p & t).sum()
                    union[c] += (p | t).sum()
                crop = ripe != NOT_CROP
                if crop.any():
                    ripe_pred = (ripe_logits[:, 0].cpu().numpy() > 0)[crop]
                    ripe_ok += (ripe_pred == (ripe[crop] == 1)).sum()
                    ripe_n += crop.sum()
    present = union > 0
    iou = inter[present] / union[present]
    per_class = {CLASSES[c]: float(inter[c] / union[c]) for c in np.flatnonzero(present)}
    return {"pixel_acc": float(correct / total), "mean_iou": float(iou.mean()), "ripe_acc": float(ripe_ok / max(ripe_n, 1)), "per_class_iou": per_class}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--data", type=Path, default=DATA)
    parser.add_argument("--steps", type=int, default=6000)
    parser.add_argument("--batch-size", type=int, default=24)
    parser.add_argument("--lr", type=float, default=1e-3)
    parser.add_argument("--eval-every", type=int, default=1000)
    parser.add_argument("--out", type=Path, default=CHECKPOINTS / "reader.pt")
    parser.add_argument("--seed", type=int, default=0)
    args = parser.parse_args()

    torch.manual_seed(args.seed)
    rng = np.random.default_rng(args.seed)
    device = pick_device()
    train, val = split_episodes(load_frames(args.data), 0.1, rng)
    print(f"train {len(train)} episodes ({sum(len(e['cls']) for e in train)} frames), val {len(val)} episodes, device {device}", flush=True)

    model = BlockReader().to(device)
    weights = class_weights(train, rng).to(device)
    optimizer = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=1e-4)
    scheduler = torch.optim.lr_scheduler.OneCycleLR(optimizer, max_lr=args.lr, total_steps=args.steps)
    best = BestCheckpoint(args.out, higher_is_better=True)
    history = []
    start = time.perf_counter()
    for step in range(1, args.steps + 1):
        model.train()
        b = {k: v.to(device, non_blocking=True) for k, v in sample(train, args.batch_size, rng, augment=True).items()}
        with autocast(device):
            logits, ripe_logits = model(b["rgb"], b["depth"].float())
            loss = F.cross_entropy(logits.float(), b["cls"].long(), weight=weights)
            crop = b["ripe"] != NOT_CROP
            if crop.any():
                loss = loss + F.binary_cross_entropy_with_logits(ripe_logits[:, 0].float()[crop], (b["ripe"][crop] == 1).float())
        optimizer.zero_grad(set_to_none=True)
        loss.backward()
        optimizer.step()
        scheduler.step()
        if step % args.eval_every == 0 or step == args.steps:
            metrics = evaluate(model, val, device, rng)
            history.append({"step": step, "loss": loss.item(), **{k: v for k, v in metrics.items() if k != "per_class_iou"}})
            saved = best.offer(metrics["mean_iou"], model=model.state_dict(), step=step, metrics=metrics, classes=CLASSES)
            if saved:
                final = metrics
            print(
                f"step {step} loss {loss.item():.3f} pixel acc {metrics['pixel_acc']:.3f} mean IoU {metrics['mean_iou']:.3f} "
                f"ripe acc {metrics['ripe_acc']:.3f} {time.perf_counter() - start:.0f}s{' saved' if saved else ''}",
                flush=True,
            )
    write_report(args.out.with_suffix(".json"), args, best=final, history=history)
    worst = sorted(final["per_class_iou"].items(), key=lambda kv: kv[1])[:6]
    print(f"best mean IoU {best.best:.3f}, weakest classes: " + ", ".join(f"{n.split(':')[-1]} {v:.2f}" for n, v in worst))


if __name__ == "__main__":
    main()
