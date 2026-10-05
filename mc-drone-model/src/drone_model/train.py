"""Behavior cloning on recorded navigate_to demos"""

from __future__ import annotations

import argparse
import json
import time
from pathlib import Path

import numpy as np
import torch
from torch import nn

from .data import Demos, load_demos
from .model import DronePolicy, to_image

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / "data"


def batches(demos: Demos, batch_size: int, shuffle: bool, rng: np.random.Generator):
    order = rng.permutation(len(demos)) if shuffle else np.arange(len(demos))
    for start in range(0, len(order), batch_size):
        idx = torch.from_numpy(order[start : start + batch_size])
        yield demos.rgb[idx], demos.depth[idx], demos.state[idx], demos.action[idx]


def run_epoch(model, demos, optimizer, device, batch_size, rng, train: bool) -> float:
    model.train(train)
    total, count = 0.0, 0
    loss_fn = nn.MSELoss()
    with torch.set_grad_enabled(train):
        for rgb, depth, state, action in batches(demos, batch_size, train, rng):
            image = to_image(rgb.to(device), depth.to(device))
            if train:
                # brightness jitter, the sky and lighting shift with time of day
                image[:, :3] *= torch.empty(len(image), 1, 1, 1, device=device).uniform_(0.8, 1.2)
            pred = model(image, state.to(device))
            loss = loss_fn(pred, action.to(device))
            if train:
                optimizer.zero_grad(set_to_none=True)
                loss.backward()
                optimizer.step()
            total += loss.item() * len(action)
            count += len(action)
    return total / max(count, 1)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--data", type=Path, default=DATA)
    parser.add_argument("--epochs", type=int, default=30)
    parser.add_argument("--batch-size", type=int, default=128)
    parser.add_argument("--lr", type=float, default=1e-3)
    parser.add_argument("--val-fraction", type=float, default=0.15)
    parser.add_argument("--out", type=Path, default=ROOT / "checkpoints" / "bc.pt")
    parser.add_argument("--seed", type=int, default=0)
    args = parser.parse_args()

    torch.manual_seed(args.seed)
    rng = np.random.default_rng(args.seed)
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    demos = load_demos(args.data)

    # split by episode so validation frames never share an episode with training frames
    episodes = np.unique(demos.episode)
    val_eps = rng.choice(episodes, size=max(1, int(len(episodes) * args.val_fraction)), replace=False)
    is_val = np.isin(demos.episode, val_eps)
    train_set, val_set = demos.subset(~is_val), demos.subset(is_val)
    print(f"train {len(train_set)} steps, val {len(val_set)} steps, device {device}")

    model = DronePolicy().to(device)
    optimizer = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=1e-4)
    scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(optimizer, args.epochs)
    history = []
    best = float("inf")
    args.out.parent.mkdir(parents=True, exist_ok=True)
    for epoch in range(1, args.epochs + 1):
        start = time.perf_counter()
        train_loss = run_epoch(model, train_set, optimizer, device, args.batch_size, rng, train=True)
        val_loss = run_epoch(model, val_set, optimizer, device, args.batch_size, rng, train=False)
        scheduler.step()
        history.append({"epoch": epoch, "train_loss": train_loss, "val_loss": val_loss})
        marker = ""
        if val_loss < best:
            best = val_loss
            torch.save({"model": model.state_dict(), "epoch": epoch, "val_loss": val_loss}, args.out)
            marker = " saved"
        print(f"epoch {epoch:3d} train {train_loss:.4f} val {val_loss:.4f} {time.perf_counter() - start:.1f}s{marker}", flush=True)

    report = args.out.with_suffix(".json")
    report.write_text(json.dumps({"args": {k: str(v) for k, v in vars(args).items()}, "best_val_loss": best, "history": history}, indent=2))
    print(f"best val loss {best:.4f}, checkpoint {args.out}")


if __name__ == "__main__":
    main()
