"""Small CNN policy over RGB + depth plus the non-privileged state vector"""

from __future__ import annotations

import torch
from torch import nn

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
