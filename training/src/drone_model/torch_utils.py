"""What every model script shares: the device, mixed precision, checkpoints, episode splits, and run reports"""

from __future__ import annotations

import argparse
import json
import math
from collections.abc import Sequence
from pathlib import Path
from typing import Any, TypeVar

import numpy as np
import torch

T = TypeVar("T")


def pick_device() -> torch.device:
    return torch.device("cuda" if torch.cuda.is_available() else "cpu")


def autocast(device: torch.device) -> torch.autocast:
    """bfloat16 on the GPU, full precision on the CPU"""
    return torch.autocast(device.type, dtype=torch.bfloat16, enabled=device.type == "cuda")


def load_weights(model: torch.nn.Module, path: Path, device: torch.device) -> dict[str, Any]:
    """Loads a checkpoint's model weights into model and returns the whole checkpoint"""
    checkpoint = torch.load(path, map_location=device, weights_only=True)
    model.load_state_dict(checkpoint["model"])
    return checkpoint


def split_episodes(episodes: Sequence[T], val_fraction: float, rng: np.random.Generator) -> tuple[list[T], list[T]]:
    """Training and validation episodes, at least one for validation, so no episode's steps land on both sides"""
    order = rng.permutation(len(episodes))
    n_val = max(1, int(len(episodes) * val_fraction))
    return [episodes[i] for i in order[n_val:]], [episodes[i] for i in order[:n_val]]


class BestCheckpoint:
    """Saves the checkpoint each time the validation score improves, lower is better unless higher_is_better"""

    def __init__(self, path: Path, higher_is_better: bool = False) -> None:
        self.path = path
        self.higher_is_better = higher_is_better
        self.best = -math.inf if higher_is_better else math.inf
        path.parent.mkdir(parents=True, exist_ok=True)

    def offer(self, score: float, **checkpoint: Any) -> bool:
        if not (score > self.best if self.higher_is_better else score < self.best):
            return False
        self.best = score
        torch.save(checkpoint, self.path)
        return True


def write_report(path: Path, args: argparse.Namespace | None = None, **fields: Any) -> None:
    """The run's arguments and results as JSON, usually beside its checkpoint"""
    report = ({"args": {k: str(v) for k, v in vars(args).items()}} if args is not None else {}) | fields
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(report, indent=2))
