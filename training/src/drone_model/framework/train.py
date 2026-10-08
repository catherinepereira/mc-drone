"""
Trains a policy on its collected data by behavior cloning. Episodes split into training and validation, so no
validation step comes from a training episode, and the checkpoint with the best validation loss is kept

    python -m drone_model.framework.train --policy skill
    python -m drone_model.framework.train --policy skill --init checkpoints/skill.pt --runs hunt_mobs-flat,hunt_mobs-flat-dagger
"""

from __future__ import annotations

import argparse
import time
from pathlib import Path

import numpy as np
import torch

from ..paths import CHECKPOINTS
from ..torch_utils import BestCheckpoint, autocast, load_weights, pick_device, split_episodes, write_report
from .data import episode_files, load_episodes, load_steps, pad_episodes
from .registry import get
from .spec import PolicySpec


class Batches:
    """Random training batches and the validation batches, as steps or, for a sequence policy, as whole episodes"""

    def __init__(self, spec: PolicySpec, files: list[Path], stride: int, batch_size: int, rng: np.random.Generator, device: torch.device) -> None:
        self.spec, self.batch_size, self.rng, self.device = spec, batch_size, rng, device
        train_files, val_files = split_episodes(files, 0.1, rng)
        if spec.sequence:
            self.train, self.val = load_episodes(train_files), load_episodes(val_files)
            self.size = sum(len(next(iter(e.values()))) for e in self.train)
        else:
            self.train, self.val = load_steps(train_files, stride), load_steps(val_files, stride)
            self.size = len(next(iter(self.train.values())))
        print(f"train {self.size} steps from {len(train_files)} episodes, validation from {len(val_files)}, device {device}", flush=True)

    def sample(self) -> dict[str, torch.Tensor]:
        if self.spec.sequence:
            picks = self.rng.integers(0, len(self.train), size=self.batch_size)
            return pad_episodes([self.train[i] for i in picks], self.device)
        idx = torch.from_numpy(self.rng.integers(0, self.size, size=self.batch_size))
        return {k: v[idx].to(self.device, non_blocking=True) for k, v in self.train.items()}

    def validation(self):
        if self.spec.sequence:
            for s in range(0, len(self.val), self.batch_size):
                yield pad_episodes(self.val[s : s + self.batch_size], self.device)
            return
        n = len(next(iter(self.val.values())))
        for s in range(0, n, 1024):
            yield {k: v[s : s + 1024].to(self.device) for k, v in self.val.items()}


@torch.no_grad()
def validate(spec: PolicySpec, model: torch.nn.Module, batches: Batches) -> dict[str, float]:
    """Mean of each value spec.loss reports over the validation batches"""
    model.eval()
    sums: dict[str, float] = {}
    count = 0
    for b in batches.validation():
        with autocast(batches.device):
            out = spec.loss(model, b)
        for k, v in out.items():
            sums[k] = sums.get(k, 0.0) + float(v)
        count += 1
    return {k: v / max(count, 1) for k, v in sums.items()}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--policy", required=True)
    parser.add_argument("--runs", default=None, help="comma-separated data folders under data/policies/<policy>, all of them by default")
    parser.add_argument("--steps", type=int, default=8000)
    parser.add_argument("--batch-size", type=int, default=None, help="the policy's own by default")
    parser.add_argument("--stride", type=int, default=None, help="keep every stride-th step, the policy's own by default")
    parser.add_argument("--lr", type=float, default=5e-4)
    parser.add_argument("--init", type=Path, default=None, help="a checkpoint to fine-tune")
    parser.add_argument("--out", type=Path, default=None, help="checkpoints/<policy>.pt by default")
    parser.add_argument("--eval-every", type=int, default=1000)
    parser.add_argument("--seed", type=int, default=0)
    args = parser.parse_args()

    spec = get(args.policy)
    out = args.out or CHECKPOINTS / f"{spec.name}.pt"
    torch.manual_seed(args.seed)
    rng = np.random.default_rng(args.seed)
    device = pick_device()
    files = episode_files(spec.name, args.runs.split(",") if args.runs else None)
    batches = Batches(spec, files, args.stride or spec.stride, args.batch_size or spec.batch_size, rng, device)

    model = spec.model().to(device)
    if args.init is not None:
        load_weights(model, args.init, device)
    optimizer = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=1e-4)
    scheduler = torch.optim.lr_scheduler.OneCycleLR(optimizer, max_lr=args.lr, total_steps=args.steps)
    best = BestCheckpoint(out)
    history = []
    start = time.perf_counter()
    for step in range(1, args.steps + 1):
        model.train()
        b = spec.augment(batches.sample())
        with autocast(device):
            loss = spec.loss(model, b)["loss"]
        optimizer.zero_grad(set_to_none=True)
        loss.backward()
        torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
        optimizer.step()
        scheduler.step()
        if step % args.eval_every == 0 or step == args.steps:
            val = validate(spec, model, batches)
            history.append({"step": step, **val})
            saved = best.offer(val["loss"], model=model.state_dict(), step=step, val=val, policy=spec.name)
            shown = ", ".join(f"{k} {v:.3f}" for k, v in val.items())
            print(f"step {step} {shown} {time.perf_counter() - start:.0f}s{' saved' if saved else ''}", flush=True)
    write_report(out.with_suffix(".json"), args, best_val_loss=best.best, history=history)
    print(f"best val loss {best.best:.3f}, checkpoint {out}")


if __name__ == "__main__":
    main()
