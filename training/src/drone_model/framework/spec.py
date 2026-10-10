"""
What a learned drone policy provides, so one set of commands collects its data, trains it, evaluates it, and films it.
A policy is a PolicySpec subclass registered in framework.registry, see training/POLICIES.md
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Any

import numpy as np
import torch
from torch import nn

from ..scripted.jobs import episode_expert
from ..paths import SCHEMATICS
from ..torch_utils import load_weights


@dataclass
class Step:
    """One step as a policy's record sees it, from a live run or from a recorded episode"""

    state: dict  # the drone's state, privileged fields included, record must only read what the policy may
    obs: dict  # the env's observation: rgb, depth, mask, and the vectors DroneEnv adds
    label: dict  # the teacher's action for this step, see scripted.base.tool_action
    prev: dict | None  # what flew on the step before, None on the first step
    mask_ids: dict  # the mod's names for mask ids, sent on connect
    teacher: Any = None  # the teacher itself on a live run, None for a recorded episode


class Agent:
    """A trained policy flying one episode at a time"""

    def reset(self) -> None:
        """A new episode starts"""

    def act(self, step: Step) -> dict:
        """The action for this step. step.label is the teacher's, a policy that works for its teacher (the skill) reads it"""
        raise NotImplementedError

    def executed(self, action: dict) -> None:
        """What actually flew this step, DAgger flies the teacher on some steps and a recurrent policy feeds it back in"""


class PolicySpec:
    # the registry's name for the policy, its data folder, and its default checkpoint
    name = ""
    # what the env sends, teachers that map the arena read the mask
    streams: tuple[str, ...] = ("rgb", "depth", "mask")
    # trains on whole episodes, for a policy that carries memory from step to step
    sequence = False
    # steps a flat batch holds, or episodes a sequence batch holds
    batch_size = 128
    # keep every stride-th step of an episode for flat training, neighboring frames are nearly the same
    stride = 1
    # weight of the penalty on the predicted turn reversing between consecutive steps, see look
    smoothness = 0.0

    def model(self) -> nn.Module:
        raise NotImplementedError

    def teacher(self, task: str, info: dict, mask_ids: dict, reader):
        """The expert that labels every step: the task's scripted expert, or a job planner when the task hands over a job"""
        return episode_expert(task, info["state"].get("job"), mask_ids, reader, SCHEMATICS)

    def record(self, step: Step) -> dict[str, np.ndarray] | None:
        """The training row for one step, inputs and targets by name, None to leave the step out"""
        raise NotImplementedError

    def loss(self, model: nn.Module, batch: dict[str, torch.Tensor]) -> dict[str, torch.Tensor]:
        """The training loss as "loss" plus anything worth printing. A sequence batch adds "valid", False on padding"""
        raise NotImplementedError

    def look(self, model: nn.Module, batch: dict[str, torch.Tensor]) -> torch.Tensor:
        """The predicted turn for a flat batch as (yaw, pitch) rows, needed when smoothness is above 0"""
        raise NotImplementedError

    def augment(self, batch: dict[str, torch.Tensor]) -> dict[str, torch.Tensor]:
        """Training-time changes to a batch: brightness jitter on rgb, light differs in caves, at dusk, and under trees"""
        rgb = batch["rgb"]
        shape = (rgb.shape[0],) + (1,) * (rgb.dim() - 1)
        batch["rgb"] = (rgb.float() * torch.empty(shape, device=rgb.device).uniform_(0.7, 1.3)).clamp(0, 255).to(torch.uint8)
        return batch

    def agent(self, model: nn.Module, mask_ids: dict) -> Agent:
        raise NotImplementedError

    def load(self, checkpoint: Path, device: torch.device) -> nn.Module:
        model = self.model().to(device)
        load_weights(model, checkpoint, device)
        return model.eval()

