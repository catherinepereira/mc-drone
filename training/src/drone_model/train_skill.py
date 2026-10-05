"""Behavior cloning for the cell skill on data/skill, split by episode"""

from __future__ import annotations

import argparse
import json
import time
from pathlib import Path

import numpy as np
import torch
from torch.nn import functional as F

from .skill import SkillPolicy

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / "data" / "skill"
FIELDS = ("rgb", "depth", "state", "goal", "move", "fire")


def load(data: Path) -> list[dict[str, np.ndarray]]:
    episodes = []
    for f in sorted(data.glob("*/*.npz")):
        with np.load(f) as z:
            episodes.append({k: z[k] for k in FIELDS})
    if not episodes:
        raise SystemExit(f"no skill data in {data}, run python -m drone_model.collect_skill first")
    return episodes


def concat(episodes) -> dict[str, torch.Tensor]:
    return {k: torch.from_numpy(np.concatenate([e[k] for e in episodes])) for k in FIELDS}


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
    parser.add_argument("--data", type=Path, default=DATA)
    parser.add_argument("--steps", type=int, default=8000)
    parser.add_argument("--batch-size", type=int, default=128)
    parser.add_argument("--lr", type=float, default=5e-4)
    parser.add_argument("--init", type=Path, default=None)
    parser.add_argument("--out", type=Path, default=ROOT / "checkpoints" / "skill.pt")
    parser.add_argument("--seed", type=int, default=0)
    args = parser.parse_args()

    torch.manual_seed(args.seed)
    rng = np.random.default_rng(args.seed)
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    episodes = load(args.data)
    order = rng.permutation(len(episodes))
    n_val = max(1, len(episodes) // 10)
    train = concat([episodes[i] for i in order[n_val:]])
    val = concat([episodes[i] for i in order[:n_val]])
    n = len(train["move"])
    fired = float(train["fire"].mean())
    print(f"train {n} steps from {len(episodes) - n_val} episodes, val {len(val['move'])} steps, fire share {fired:.3f}, device {device}", flush=True)

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
