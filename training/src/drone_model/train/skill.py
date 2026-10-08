"""Behavior cloning for the cell skill on data/skill, split by episode"""

from __future__ import annotations

import argparse
import time
from pathlib import Path

import numpy as np
import torch
from torch.nn import functional as F

from ..paths import CHECKPOINTS, DATA
from ..policies.skill import SkillPolicy
from ..torch_utils import BestCheckpoint, autocast, load_weights, pick_device, split_episodes, write_report

SKILL_DATA = DATA / "skill"
FIELDS = ("rgb", "depth", "state", "goal", "move", "fire")


def episode_files(data: Path) -> list[Path]:
    files = sorted(data.glob("*/*.npz"))
    if not files:
        raise SystemExit(f"no skill data in {data}, run python -m drone_model.collect.skill first")
    return files


def load(files: list[Path], stride: int) -> dict[str, torch.Tensor]:
    """Every stride-th step of the episodes, read twice so the arrays are allocated once at full size"""
    lengths, shapes = [], {}
    for f in files:
        with np.load(f) as z:
            lengths.append(len(range(0, len(z["move"]), stride)))
            shapes = shapes or {k: (z[k].shape[1:], z[k].dtype) for k in FIELDS}
    total = sum(lengths)
    out = {k: np.empty((total, *shape), dtype=dtype) for k, (shape, dtype) in shapes.items()}
    at = 0
    for f, n in zip(files, lengths):
        with np.load(f) as z:
            for k in FIELDS:
                out[k][at : at + n] = z[k][::stride]
        at += n
    return {k: torch.from_numpy(v) for k, v in out.items()}


def losses(model, b: dict[str, torch.Tensor]) -> dict[str, torch.Tensor]:
    move, fire = model(b["rgb"], b["depth"], b["state"], b["goal"])
    # firing only matters for break and place goals, the last three goal values are the mode
    acting = b["goal"][:, -3] < 0.5
    move_loss = F.mse_loss(move.float(), b["move"])
    fire_loss = F.binary_cross_entropy_with_logits(fire.float()[acting], b["fire"][acting]) if acting.any() else move_loss * 0
    pred = (fire.float() > 0) & acting
    fired = b["fire"] > 0.5
    recall = (pred & fired).sum() / fired.sum().clamp(min=1)
    precision = (pred & fired).sum() / pred.sum().clamp(min=1)
    return {"loss": move_loss + fire_loss, "move": move_loss, "fire": fire_loss, "fire_recall": recall, "fire_precision": precision}


@torch.no_grad()
def validate(model, val: dict[str, torch.Tensor], device: torch.device, chunk: int = 1024) -> dict[str, float]:
    """Mean of each loss and rate over the validation steps, a chunk at a time"""
    model.eval()
    sums: dict[str, float] = {}
    count = 0
    for s in range(0, len(val["move"]), chunk):
        with autocast(device):
            out = losses(model, {k: v[s : s + chunk].to(device) for k, v in val.items()})
        for k, v in out.items():
            sums[k] = sums.get(k, 0.0) + v.item()
        count += 1
    return {k: v / count for k, v in sums.items()}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--data", type=Path, default=SKILL_DATA)
    parser.add_argument("--steps", type=int, default=8000)
    parser.add_argument("--batch-size", type=int, default=128)
    parser.add_argument("--lr", type=float, default=5e-4)
    parser.add_argument("--init", type=Path, default=None)
    parser.add_argument("--out", type=Path, default=CHECKPOINTS / "skill.pt")
    parser.add_argument("--seed", type=int, default=0)
    parser.add_argument("--stride", type=int, default=2, help="keep every stride-th step, neighbors are nearly the same frame")
    args = parser.parse_args()

    torch.manual_seed(args.seed)
    rng = np.random.default_rng(args.seed)
    device = pick_device()
    train_files, val_files = split_episodes(episode_files(args.data), 0.1, rng)
    train = load(train_files, args.stride)
    val = load(val_files, args.stride)
    n = len(train["move"])
    fired = float(train["fire"].mean())
    print(f"train {n} steps from {len(train_files)} episodes, val {len(val['move'])} steps, fire share {fired:.3f}, device {device}", flush=True)

    model = SkillPolicy().to(device)
    if args.init is not None:
        load_weights(model, args.init, device)
    optimizer = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=1e-4)
    scheduler = torch.optim.lr_scheduler.OneCycleLR(optimizer, max_lr=args.lr, total_steps=args.steps)
    best = BestCheckpoint(args.out)
    history = []
    start = time.perf_counter()
    for step in range(1, args.steps + 1):
        model.train()
        idx = torch.from_numpy(rng.integers(0, n, size=args.batch_size))
        b = {k: v[idx].to(device, non_blocking=True) for k, v in train.items()}
        # brightness jitter, the same fields look different in caves, at dusk, and under trees
        b["rgb"] = (b["rgb"].float() * torch.empty(len(idx), 1, 1, 1, device=device).uniform_(0.7, 1.3)).clamp(0, 255).to(torch.uint8)
        with autocast(device):
            out = losses(model, b)
        optimizer.zero_grad(set_to_none=True)
        out["loss"].backward()
        torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
        optimizer.step()
        scheduler.step()
        if step % 1000 == 0 or step == args.steps:
            va = validate(model, val, device)
            history.append({"step": step, **va})
            saved = best.offer(va["loss"], model=model.state_dict(), step=step, val=va)
            print(
                f"step {step} val loss {va['loss']:.3f} (move {va['move']:.3f} fire {va['fire']:.3f}, fire recall {va['fire_recall']:.2f} "
                f"precision {va['fire_precision']:.2f}) {time.perf_counter() - start:.0f}s{' saved' if saved else ''}",
                flush=True,
            )
    write_report(args.out.with_suffix(".json"), args, best_val_loss=best.best, history=history)
    print(f"best val loss {best.best:.3f}, checkpoint {args.out}")


if __name__ == "__main__":
    main()
