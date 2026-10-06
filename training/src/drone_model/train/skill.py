"""Behavior cloning for the cell skill on data/skill, split by episode"""

from __future__ import annotations

import argparse
import json
import time
from pathlib import Path

import numpy as np
import torch
from torch.nn import functional as F

from ..paths import CHECKPOINTS, DATA
from ..policies.skill import SkillPolicy

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
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    files = episode_files(args.data)
    order = rng.permutation(len(files))
    n_val = max(1, len(files) // 10)
    train = load([files[i] for i in order[n_val:]], args.stride)
    val = load([files[i] for i in order[:n_val]], args.stride)
    n = len(train["move"])
    fired = float(train["fire"].mean())
    print(f"train {n} steps from {len(files) - n_val} episodes, val {len(val['move'])} steps, fire share {fired:.3f}, device {device}", flush=True)

    model = SkillPolicy().to(device)
    if args.init is not None:
        model.load_state_dict(torch.load(args.init, map_location=device, weights_only=True)["model"])
    optimizer = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=1e-4)
    scheduler = torch.optim.lr_scheduler.OneCycleLR(optimizer, max_lr=args.lr, total_steps=args.steps)
    best = float("inf")
    history = []
    start = time.perf_counter()
    args.out.parent.mkdir(parents=True, exist_ok=True)
    for step in range(1, args.steps + 1):
        model.train()
        idx = torch.from_numpy(rng.integers(0, n, size=args.batch_size))
        b = {k: v[idx].to(device, non_blocking=True) for k, v in train.items()}
        # brightness jitter, the same fields look different in caves, at dusk, and under trees
        b["rgb"] = (b["rgb"].float() * torch.empty(len(idx), 1, 1, 1, device=device).uniform_(0.7, 1.3)).clamp(0, 255).to(torch.uint8)
        with torch.autocast(device.type, dtype=torch.bfloat16, enabled=device.type == "cuda"):
            out = losses(model, b)
        optimizer.zero_grad(set_to_none=True)
        out["loss"].backward()
        torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
        optimizer.step()
        scheduler.step()
        if step % 1000 == 0 or step == args.steps:
            model.eval()
            sums: dict[str, float] = {}
            count = 0
            with torch.no_grad():
                for s in range(0, len(val["move"]), 1024):
                    vb = {k: v[s : s + 1024].to(device) for k, v in val.items()}
                    with torch.autocast(device.type, dtype=torch.bfloat16, enabled=device.type == "cuda"):
                        o = losses(model, vb)
                    for k, v in o.items():
                        sums[k] = sums.get(k, 0.0) + v.item()
                    count += 1
            va = {k: v / count for k, v in sums.items()}
            history.append({"step": step, **va})
            saved = ""
            if va["loss"] < best:
                best = va["loss"]
                torch.save({"model": model.state_dict(), "step": step, "val": va}, args.out)
                saved = " saved"
            print(
                f"step {step} val loss {va['loss']:.3f} (move {va['move']:.3f} fire {va['fire']:.3f}, fire recall {va['fire_recall']:.2f} "
                f"precision {va['fire_precision']:.2f}) {time.perf_counter() - start:.0f}s{saved}",
                flush=True,
            )
    args.out.with_suffix(".json").write_text(json.dumps({"args": {k: str(v) for k, v in vars(args).items()}, "best_val_loss": best, "history": history}, indent=2))
    print(f"best val loss {best:.3f}, checkpoint {args.out}")


if __name__ == "__main__":
    main()
