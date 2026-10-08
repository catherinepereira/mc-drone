"""The policies evaluation and videos fly, each behind act(state, obs)"""

from __future__ import annotations

from pathlib import Path

import numpy as np
import torch

from .experts.jobs import episode_expert
from .paths import SCHEMATICS
from .perception.reader import Reader
from .policies.cnn import DronePolicy, to_image
from .policies.seq import SeqAgent
from .policies.skill import with_skill
from .torch_utils import load_weights, pick_device

POLICIES = ("expert", "bc", "seq", "random")


class CnnAgent:
    """A DronePolicy checkpoint, behavior cloning or PPO, flying navigate_to from RGB, depth, and the drone's motion"""

    def __init__(self, checkpoint: Path, device: torch.device) -> None:
        self.device = device
        self.model = DronePolicy().to(device)
        load_weights(self.model, checkpoint, device)
        self.model.eval()

    @torch.no_grad()
    def act(self, state: dict, obs: dict) -> np.ndarray:
        image = to_image(torch.from_numpy(obs["rgb"])[None].to(self.device), torch.from_numpy(obs["depth"])[None].to(self.device))
        return self.model(image, torch.from_numpy(obs["state"][:6])[None].to(self.device))[0].cpu().numpy()


class RandomAgent:
    def __init__(self, seed: int = 0) -> None:
        self.rng = np.random.default_rng(seed)

    def act(self, state: dict, obs: dict) -> np.ndarray:
        return self.rng.uniform(-1, 1, size=5).astype(np.float32)


class Pilot:
    """
    Hands out the agent for each episode. The expert starts fresh every episode, as the job planner for tasks with a job
    when a block reader is given. Learned policies load once and carry nothing between episodes
    """

    def __init__(self, policy: str, task: str, checkpoint: Path | None = None, reader: Reader | None = None, skill: Path | None = None) -> None:
        if policy not in POLICIES:
            raise ValueError(f"policy is one of {', '.join(POLICIES)}")
        if task != "navigate_to" and policy in ("bc", "random"):
            raise ValueError("tool tasks fly with the expert or a seq policy")
        self.policy = policy
        self.task = task
        self.checkpoint = checkpoint
        self.reader = reader
        self.skill = skill
        self.device = pick_device()
        self.agent = None

    def start(self, mask_ids: dict, info: dict):
        if self.policy == "expert":
            return with_skill(episode_expert(self.task, info["state"].get("job"), mask_ids, self.reader, SCHEMATICS), self.skill)
        if self.agent is None:
            self.agent = {
                "bc": lambda: CnnAgent(self.checkpoint, self.device),
                "seq": lambda: SeqAgent(self.checkpoint, mask_ids, self.device),
                "random": RandomAgent,
            }[self.policy]()
        if isinstance(self.agent, SeqAgent):
            self.agent.reset()
        return self.agent


def outcome(episode: dict) -> str:
    if episode.get("success"):
        return "success"
    return "out of bounds" if episode.get("outOfBounds") else "timeout"
