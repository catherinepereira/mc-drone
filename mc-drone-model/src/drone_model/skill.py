"""
The cell skill: one learned controller for every job. Given a goal (look at a point from a viewpoint, or break or
place at a point) it flies, aims, and decides when to fire the tool, from the camera image and depth alone.
A planner (the experts' decide logic over the voxel memory) picks the goals
"""

from __future__ import annotations

import math

import numpy as np
import torch
from torch import nn

from .seq_model import DEPTH_MAX, frame_features, state_features

MODES = ("view", "break", "place")
GOAL_DIM = 10
STATE_DIM = 12
GOAL_SCALE = 8.0


def goal_features(state: dict, intent: dict) -> np.ndarray:
    """Aim point and viewpoint relative to the camera in the drone's heading frame (forward, right, up), and the mode"""
    pos = state["pos"]
    eye = np.array([pos[0], pos[1] + state["camera"]["eyeHeight"], pos[2]])
    yaw = math.radians(state["yaw"])
    forward = np.array([-math.sin(yaw), 0.0, math.cos(yaw)])
    right = np.array([-math.cos(yaw), 0.0, -math.sin(yaw)])

    def local(p) -> list[float]:
        d = np.asarray(p, dtype=np.float64) - eye
        return [float(d @ forward) / GOAL_SCALE, float(d @ right) / GOAL_SCALE, float(d[1]) / GOAL_SCALE]

    via = intent.get("via")
    mode = [1.0 if intent["mode"] == m else 0.0 for m in MODES]
    return np.asarray([*local(intent["aim"]), *(local(via) if via else [0.0, 0.0, 0.0]), 1.0 if via else 0.0, *mode], dtype=np.float32)


AIM_DIM = 5


def aim_features(depth: torch.Tensor, state: torch.Tensor, goal: torch.Tensor) -> torch.Tensor:
    """
    Where the crosshair points relative to the aim point, from the goal, the camera pitch, and the depth at the
    crosshair: yaw and pitch error, distance, how far past or short of the aim point the crosshair lands, and in reach
    """
    f, r, u = goal[:, 0] * GOAL_SCALE, goal[:, 1] * GOAL_SCALE, goal[:, 2] * GOAL_SCALE
    pitch = state[:, 5] * (math.pi / 2)
    yaw_error = torch.atan2(r, f)
    pitch_error = -torch.atan2(u, torch.hypot(f, r)) - pitch
    dist = torch.sqrt(f * f + r * r + u * u)
    h, w = depth.shape[1:]
    crosshair = depth[:, h // 2 - 1 : h // 2 + 1, w // 2 - 1 : w // 2 + 1].float().mean(dim=(1, 2)) / 255.0 * DEPTH_MAX
    # the scripted controller turns by the error over 15 degrees, at this scale that's the identity
    span = math.radians(15)
    return torch.stack([yaw_error / span, pitch_error / span, dist / 4.0, ((crosshair - dist) / 2.0).clamp(-2, 2), (dist <= 4.4).float()], dim=1)


def conv(cin: int, cout: int, k: int, s: int) -> nn.Sequential:
    return nn.Sequential(nn.Conv2d(cin, cout, k, s, k // 2), nn.GroupNorm(8, cout), nn.ReLU(inplace=True))


class SkillPolicy(nn.Module):
    def __init__(self) -> None:
        super().__init__()
        self.encoder = nn.Sequential(
            conv(4, 32, 5, 2), conv(32, 64, 3, 2), conv(64, 96, 3, 2), conv(96, 128, 3, 2),
            nn.AdaptiveAvgPool2d((3, 4)), nn.Flatten(), nn.Linear(128 * 12, 256), nn.ReLU(inplace=True),
        )
        self.head = nn.Sequential(
            nn.Linear(256 + STATE_DIM + GOAL_DIM + AIM_DIM, 256), nn.ReLU(inplace=True), nn.Linear(256, 128), nn.ReLU(inplace=True)
        )
        self.move = nn.Linear(128, 5)
        # a direct path from the aim and goal to the move, proportional steering needs no depth to learn
        self.steer = nn.Linear(GOAL_DIM + AIM_DIM, 5)
        self.fire = nn.Linear(128, 1)

    def forward(self, rgb, depth, state, goal) -> tuple[torch.Tensor, torch.Tensor]:
        """rgb (B, H, W, 3) uint8, depth (B, H, W) uint8 as depth / 64 * 255, the rest float vectors"""
        image = torch.cat([rgb.permute(0, 3, 1, 2).float() / 255.0, depth.unsqueeze(1).float() / 255.0], dim=1)
        aim = aim_features(depth, state, goal)
        h = self.head(torch.cat([self.encoder(image), state, goal, aim], dim=1))
        return torch.tanh(self.move(h) + self.steer(torch.cat([goal, aim], dim=1))), self.fire(h).squeeze(1)


def frame(obs: dict) -> tuple[np.ndarray, np.ndarray]:
    """The camera image and depth at the skill's resolution"""
    rgb, depth, _ = frame_features(obs["rgb"], obs["depth"], np.zeros(obs["depth"].shape, dtype=np.uint16), np.zeros(1, dtype=np.uint8))
    return rgb, depth


class SkillAgent:
    """A trained SkillPolicy behind the experts' skill hook"""

    def __init__(self, checkpoint, device: torch.device | None = None) -> None:
        self.device = device or torch.device("cuda" if torch.cuda.is_available() else "cpu")
        self.model = SkillPolicy().to(self.device)
        self.model.load_state_dict(torch.load(checkpoint, map_location=self.device, weights_only=True)["model"])
        self.model.eval()

    @torch.no_grad()
    def act(self, state: dict, obs: dict, intent: dict) -> dict:
        from .tool_experts import tool_action

        rgb, depth = frame(obs)
        tensors = [
            torch.from_numpy(rgb)[None], torch.from_numpy(depth)[None], torch.from_numpy(state_features(state))[None],
            torch.from_numpy(goal_features(state, intent))[None],
        ]
        move, fire = self.model(*[t.to(self.device) for t in tensors])
        move = move[0].float().cpu().numpy()
        firing = intent["mode"] != "view" and float(torch.sigmoid(fire[0])) > 0.5
        return tool_action(move, intent["mode"] if firing else "none", block=intent.get("block"))


def with_skill(expert, checkpoint):
    """Hands an expert's flying, aiming, and firing to a trained skill, its planner keeps choosing the goals"""
    if checkpoint is not None:
        expert.skill = SkillAgent(checkpoint)
    return expert
