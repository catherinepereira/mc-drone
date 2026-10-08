"""
The goto policy: learned flight to a goal point, around pillars, trees, terrain, and cave roofs, from the camera image,
depth, the range sensors, and the drone's motion. The goto planner (experts.goto) names the goal: a point given as
coordinates, a spot over a block it found, a place a few blocks from someone it follows, or the next search point
"""

from __future__ import annotations

import math

import numpy as np
import torch
from mcdrone.env import range_vector
from torch import nn
from torch.nn import functional as F

from ..experts.base import tool_action
from ..framework.spec import Agent, PolicySpec, Step
from .seq import conv, state_features
from .skill import frame

MODES = ("goto", "follow", "explore")
GOAL_SCALE = 8.0
# the goal relative to the camera in the heading frame (3), its distance across the ground and bearing, how far off to
# hold (standoff, hover), and the mode
GOAL_DIM = 3 + 2 + 2 + len(MODES)
STATE_DIM = 12 + 2


def goal_features(state: dict, intent: dict) -> np.ndarray:
    pos = state["pos"]
    eye = np.array([pos[0], pos[1] + state["camera"]["eyeHeight"], pos[2]])
    yaw = math.radians(state["yaw"])
    d = np.asarray(intent["aim"], dtype=np.float64) - eye
    forward = d[0] * -math.sin(yaw) + d[2] * math.cos(yaw)
    right = d[0] * -math.cos(yaw) + d[2] * -math.sin(yaw)
    across = math.hypot(d[0], d[2])
    mode = [1.0 if intent["mode"] == m else 0.0 for m in MODES]
    return np.asarray(
        [forward / GOAL_SCALE, right / GOAL_SCALE, d[1] / GOAL_SCALE, min(across, 32.0) / GOAL_SCALE, math.atan2(right, forward) / math.pi,
         intent["standoff"] / GOAL_SCALE, intent["hover"] / GOAL_SCALE, *mode],
        dtype=np.float32,
    )


def drone_features(state: dict) -> np.ndarray:
    """The drone's motion, heading, geofence position, and tool state, plus the range sensors ahead and below"""
    return np.concatenate([state_features(state), range_vector(state)]).astype(np.float32)


class GotoPolicy(nn.Module):
    def __init__(self) -> None:
        super().__init__()
        self.encoder = nn.Sequential(
            conv(4, 32, 5, 2), conv(32, 64, 3, 2), conv(64, 96, 3, 2), conv(96, 128, 3, 2),
            nn.AdaptiveAvgPool2d((3, 4)), nn.Flatten(), nn.Linear(128 * 12, 256), nn.ReLU(inplace=True),
        )
        self.head = nn.Sequential(nn.Linear(256 + STATE_DIM + GOAL_DIM, 256), nn.ReLU(inplace=True), nn.Linear(256, 128), nn.ReLU(inplace=True))
        self.move = nn.Linear(128, 5)
        # a direct path from the goal to the move, steering toward it needs no image to learn
        self.steer = nn.Linear(GOAL_DIM, 5)

    def forward(self, rgb, depth, state, goal) -> torch.Tensor:
        """rgb (B, H, W, 3) uint8, depth (B, H, W) uint8 as depth / 64 * 255, the rest float vectors"""
        image = torch.cat([rgb.permute(0, 3, 1, 2).float() / 255.0, depth.unsqueeze(1).float() / 255.0], dim=1)
        h = self.head(torch.cat([self.encoder(image), state, goal], dim=1))
        return torch.tanh(self.move(h) + self.steer(goal))


class GotoAgent(Agent):
    def __init__(self, model: GotoPolicy) -> None:
        self.model = model
        self.device = next(model.parameters()).device

    @torch.no_grad()
    def act(self, step: Step) -> dict:
        """Flies the planner's goal, and on steps without one (a turn on the spot while searching) the planner's own move stands"""
        intent = step.teacher.intent if step.teacher is not None else None
        if intent is None:
            return step.label
        rgb, depth = frame(step.obs)
        tensors = [rgb, depth, drone_features(step.state), goal_features(step.state, intent)]
        move = self.model(*[torch.from_numpy(t)[None].to(self.device) for t in tensors])
        return tool_action(move[0].float().cpu().numpy())


class GotoSpec(PolicySpec):
    name = "goto"
    stride = 2

    def model(self) -> nn.Module:
        return GotoPolicy()

    def record(self, step: Step) -> dict[str, np.ndarray] | None:
        intent = step.teacher.intent if step.teacher is not None else None
        if intent is None:
            return None
        rgb, depth = frame(step.obs)
        return {
            "rgb": rgb, "depth": depth, "state": drone_features(step.state), "goal": goal_features(step.state, intent),
            "move": np.asarray(step.label["move"], dtype=np.float32),
        }

    def loss(self, model: nn.Module, batch: dict[str, torch.Tensor]) -> dict[str, torch.Tensor]:
        move = model(batch["rgb"], batch["depth"], batch["state"], batch["goal"])
        loss = F.mse_loss(move.float(), batch["move"])
        # the turn and climb matter most around obstacles, report them on their own
        return {"loss": loss, "forward": F.mse_loss(move[:, 0].float(), batch["move"][:, 0]), "yaw": F.mse_loss(move[:, 3].float(), batch["move"][:, 3])}

    def agent(self, model: nn.Module, mask_ids: dict) -> Agent:
        return GotoAgent(model)
