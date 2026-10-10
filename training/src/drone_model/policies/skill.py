"""
The cell skill: one learned controller for every job. Given a goal (look at a point from a viewpoint, break or
place at a point, or hit the mob at a point) it flies, aims, and decides when to fire the tool, from the camera image
and depth alone.
A planner (the experts' decide logic over the voxel memory) picks the goals
"""

from __future__ import annotations

import math

import numpy as np
import torch
from torch import nn
from torch.nn import functional as F

from ..framework.spec import Agent, PolicySpec, Step
from ..torch_utils import load_weights, pick_device
from .seq import DEPTH_MAX, conv, frame_features, state_features

MODES = ("view", "break", "place", "attack")
GOAL_DIM = 11
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


class SkillAgent(Agent):
    """A trained SkillPolicy, flying a planner's goals in the brain or its teacher's in the framework"""

    def __init__(self, model: SkillPolicy) -> None:
        self.model = model
        self.device = next(model.parameters()).device

    @classmethod
    def load(cls, checkpoint, device: torch.device | None = None) -> "SkillAgent":
        device = device or pick_device()
        model = SkillPolicy().to(device)
        load_weights(model, checkpoint, device)
        return cls(model.eval())

    def act(self, step: Step) -> dict:
        """The skill flies the teacher's goal, and on steps without one (an errand, a pause) the teacher's own action stands"""
        intent = step.teacher.intent if step.teacher is not None else None
        return self.fly(step.state, step.obs, intent) if intent is not None else step.label

    @torch.no_grad()
    def fly(self, state: dict, obs: dict, intent: dict) -> dict:
        from ..scripted.base import tool_action

        rgb, depth = frame(obs)
        tensors = [
            torch.from_numpy(rgb)[None], torch.from_numpy(depth)[None], torch.from_numpy(state_features(state))[None],
            torch.from_numpy(goal_features(state, intent))[None],
        ]
        move, fire = self.model(*[t.to(self.device) for t in tensors])
        move = move[0].float().cpu().numpy()
        firing = intent["mode"] != "view" and float(torch.sigmoid(fire[0])) > 0.5
        return tool_action(move, intent["mode"] if firing else "none", block=intent.get("block"))


class SkillSpec(PolicySpec):
    """
    Learns from the job planners: every step where the planner has a goal becomes a row, labeled with the scripted
    controller's clean action. Under DAgger the skill flies a share of those steps and the planner still labels them
    """

    name = "skill"
    stride = 2
    smoothness = 0.2

    def model(self) -> nn.Module:
        return SkillPolicy()

    def record(self, step: Step) -> dict[str, np.ndarray] | None:
        from ..scripted.base import TOOLS

        intent = step.teacher.intent if step.teacher is not None else None
        if intent is None:
            return None
        rgb, depth = frame(step.obs)
        fired = TOOLS[step.label["tool"]] in ("break", "place", "attack")
        return {
            "rgb": rgb, "depth": depth, "state": state_features(step.state), "goal": goal_features(step.state, intent),
            "move": np.asarray(step.label["move"], dtype=np.float32), "fire": np.float32(1.0 if fired else 0.0),
        }

    def loss(self, model: nn.Module, b: dict[str, torch.Tensor]) -> dict[str, torch.Tensor]:
        move, fire = model(b["rgb"], b["depth"], b["state"], b["goal"])
        # firing only matters for goals that use the tool, the goal ends in the mode's one-hot
        acting = b["goal"][:, -len(MODES)] < 0.5
        move_loss = F.mse_loss(move.float(), b["move"])
        fire_loss = F.binary_cross_entropy_with_logits(fire.float()[acting], b["fire"][acting]) if acting.any() else move_loss * 0
        pred = (fire.float() > 0) & acting
        fired = b["fire"] > 0.5
        recall = (pred & fired).sum() / fired.sum().clamp(min=1)
        precision = (pred & fired).sum() / pred.sum().clamp(min=1)
        return {"loss": move_loss + fire_loss, "move": move_loss, "fire": fire_loss, "fire_recall": recall, "fire_precision": precision}

    def look(self, model: nn.Module, batch: dict[str, torch.Tensor]) -> torch.Tensor:
        return model(batch["rgb"], batch["depth"], batch["state"], batch["goal"])[0][:, 3:5]

    def agent(self, model: nn.Module, mask_ids: dict) -> Agent:
        return SkillAgent(model)

