"""
The navigate_to policy: a small CNN over RGB and depth plus the drone's velocity and heading, predicting the flight
vector. It never sees the marker position and has to find the marker in the image. PPO fine-tunes it, see framework.ppo
"""

from __future__ import annotations

import numpy as np
import torch
from mcdrone import state_vector
from torch import nn
from torch.nn import functional as F

from ..scripted.base import tool_action
from ..framework.spec import Agent, PolicySpec, Step

STATE_DIM = 6
ACTION_DIM = 5
DEPTH_MAX = 64.0


def conv(cin: int, cout: int, k: int, s: int) -> nn.Sequential:
    return nn.Sequential(nn.Conv2d(cin, cout, k, s, k // 2), nn.BatchNorm2d(cout), nn.ReLU(inplace=True))


class DronePolicy(nn.Module):
    def __init__(self) -> None:
        super().__init__()
        self.encoder = nn.Sequential(
            conv(4, 32, 5, 2),
            conv(32, 64, 3, 2),
            conv(64, 64, 3, 2),
            conv(64, 128, 3, 2),
            nn.AdaptiveAvgPool2d((3, 4)),
            nn.Flatten(),
        )
        self.head = nn.Sequential(
            nn.Linear(128 * 12 + STATE_DIM, 256),
            nn.ReLU(inplace=True),
            nn.Dropout(0.2),
            nn.Linear(256, ACTION_DIM),
            nn.Tanh(),
        )

    def forward(self, image: torch.Tensor, state: torch.Tensor) -> torch.Tensor:
        """image is (B, 4, H, W) with rgb in [0, 1] and depth / DEPTH_MAX as the 4th channel"""
        return self.head(torch.cat([self.encoder(image), state], dim=1))


def to_image(rgb: torch.Tensor, depth: torch.Tensor) -> torch.Tensor:
    """(B, H, W, 3) uint8 and (B, H, W) float or uint8-quantized depth into the model's input"""
    rgb = rgb.permute(0, 3, 1, 2).float() / 255.0
    if depth.dtype == torch.uint8:
        depth = depth.float() / 255.0
    else:
        depth = depth.clamp(0, DEPTH_MAX) / DEPTH_MAX
    return torch.cat([rgb, depth.unsqueeze(1)], dim=1)


def quantize_depth(depth: np.ndarray) -> np.ndarray:
    return (np.clip(depth, 0, DEPTH_MAX) / DEPTH_MAX * 255).astype(np.uint8)


class NavigateAgent(Agent):
    def __init__(self, model: DronePolicy) -> None:
        self.model = model
        self.device = next(model.parameters()).device

    @torch.no_grad()
    def act(self, step: Step) -> dict:
        image = to_image(torch.from_numpy(step.obs["rgb"])[None].to(self.device), torch.from_numpy(step.obs["depth"])[None].to(self.device))
        state = torch.from_numpy(state_vector(step.state, include_marker=False)[:STATE_DIM])[None].to(self.device)
        return tool_action(self.model(image, state)[0].cpu().numpy())


class NavigateSpec(PolicySpec):
    name = "navigate"

    def model(self) -> nn.Module:
        return DronePolicy()

    def record(self, step: Step) -> dict[str, np.ndarray]:
        return {
            "rgb": step.obs["rgb"],
            "depth": quantize_depth(step.obs["depth"]),
            "state": state_vector(step.state, include_marker=False)[:STATE_DIM],
            "move": np.asarray(step.label["move"], dtype=np.float32),
        }

    def loss(self, model: nn.Module, batch: dict[str, torch.Tensor]) -> dict[str, torch.Tensor]:
        move = model(to_image(batch["rgb"], batch["depth"]), batch["state"])
        loss = F.mse_loss(move.float(), batch["move"])
        return {"loss": loss}

    def agent(self, model: nn.Module, mask_ids: dict) -> Agent:
        return NavigateAgent(model)
