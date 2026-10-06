"""Loads recorded demos into memory as compact tensors"""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import torch
from mcdrone import list_episodes, state_vector
from mcdrone.dataset import action_array

from ..policies.cnn import DEPTH_MAX


@dataclass
class Demos:
    rgb: torch.Tensor  # (N, H, W, 3) uint8
    depth: torch.Tensor  # (N, H, W) uint8, depth / DEPTH_MAX * 255
    state: torch.Tensor  # (N, 6) float32
    action: torch.Tensor  # (N, 5) float32
    episode: np.ndarray  # (N,) episode index, for splitting by episode

    def __len__(self) -> int:
        return len(self.action)

    def subset(self, mask: np.ndarray) -> "Demos":
        idx = torch.from_numpy(np.flatnonzero(mask))
        return Demos(self.rgb[idx], self.depth[idx], self.state[idx], self.action[idx], self.episode[mask])


def load_demos(data: Path, require_expert_labels: bool = True) -> Demos:
    rgbs, depths, states, actions, episodes = [], [], [], [], []
    usable = 0
    for i, ep in enumerate(list_episodes(data)):
        labels_file = ep.path / "expert.jsonl"
        labels = None
        if labels_file.exists():
            labels = [json.loads(line) for line in labels_file.read_text(encoding="utf-8").splitlines() if line.strip()]
        elif require_expert_labels:
            continue
        usable += 1
        for row in ep.steps:
            if row["action"] is None:
                continue
            n = row["step"]
            label = labels[n] if labels is not None else None
            if isinstance(label, dict):
                label = label["move"]
            target = np.asarray(label, dtype=np.float32) if label is not None else action_array(row["action"])
            rgbs.append(ep.rgb(n))
            depths.append((np.clip(ep.depth(n), 0, DEPTH_MAX) / DEPTH_MAX * 255).astype(np.uint8))
            states.append(state_vector(row["state"], include_marker=False))
            actions.append(target)
            episodes.append(i)
    if not actions:
        raise SystemExit(f"no usable demos in {data}, run python -m drone_model.collect.demos first")
    print(f"loaded {len(actions)} steps from {usable} episodes")
    return Demos(
        torch.from_numpy(np.stack(rgbs)),
        torch.from_numpy(np.stack(depths)),
        torch.from_numpy(np.stack(states)),
        torch.from_numpy(np.stack(actions)),
        np.asarray(episodes),
    )
