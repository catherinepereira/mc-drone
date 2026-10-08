"""
The block reader: a small U-Net that looks at the camera image and depth and says, per pixel, which block it is and
whether a crop is ripe. It is trained on the mod's mask and state streams and needs neither when it runs
"""

from __future__ import annotations

import numpy as np
import torch
from torch import nn
from torch.nn import functional as F

from ..torch_utils import load_weights, pick_device
from .blocks import BUILD_PALETTE

SKY, OTHER = 0, 1
# what the reader tells apart: the build palette, the arenas' bases and floors, terrain, and what jobs work with
CLASSES = (
    "<sky>",
    "<other>",
    *BUILD_PALETTE,
    "minecraft:cyan_concrete",
    "minecraft:lime_concrete",
    "minecraft:light_gray_concrete",
    "minecraft:gray_concrete",
    "minecraft:stone_bricks",
    "minecraft:grass_block",
    "minecraft:dirt",
    "minecraft:stone",
    "minecraft:tuff",
    "minecraft:gravel",
    "minecraft:oak_log",
    "minecraft:oak_leaves",
    "minecraft:coal_ore",
    "minecraft:chest",
    "minecraft:farmland",
    "minecraft:water",
    "minecraft:wheat",
    "minecraft:carrots",
    "minecraft:potatoes",
    "minecraft:beetroots",
    "minecraft:glowstone",
    "minecraft:dripstone_block",
    "mcdrone:marker",
)
INDEX = {name: i for i, name in enumerate(CLASSES)}
CROPS = ("minecraft:wheat", "minecraft:carrots", "minecraft:potatoes", "minecraft:beetroots")
# ripe label for pixels that aren't a crop, left out of the ripeness loss
NOT_CROP = 255
DEPTH_MAX = 64.0


def state_tables(state_names: list[str]) -> tuple[np.ndarray, np.ndarray]:
    """
    Lookup tables from the state stream (0 sky, 1 + state id) to a class index and a ripe flag (1 ripe, 0 growing,
    NOT_CROP for anything else). A crop is ripe at the highest age its block has
    """
    classes = np.full(len(state_names) + 1, OTHER, dtype=np.uint8)
    ripe = np.full(len(state_names) + 1, NOT_CROP, dtype=np.uint8)
    classes[0] = SKY
    max_age: dict[str, int] = {}
    for name in state_names:
        block, _, props = name.partition("[")
        if block in CROPS:
            for p in props.rstrip("]").split(","):
                if p.startswith("age="):
                    max_age[block] = max(max_age.get(block, 0), int(p[4:]))
    for i, name in enumerate(state_names):
        block, _, props = name.partition("[")
        classes[i + 1] = INDEX.get(block, OTHER)
        if block in CROPS:
            age = next((int(p[4:]) for p in props.rstrip("]").split(",") if p.startswith("age=")), 0)
            ripe[i + 1] = 1 if age == max_age[block] else 0
    return classes, ripe


def block(cin: int, cout: int) -> nn.Sequential:
    return nn.Sequential(
        nn.Conv2d(cin, cout, 3, padding=1), nn.GroupNorm(8, cout), nn.ReLU(inplace=True),
        nn.Conv2d(cout, cout, 3, padding=1), nn.GroupNorm(8, cout), nn.ReLU(inplace=True),
    )


class BlockReader(nn.Module):
    """U-Net over rgb and depth, outputs class logits (B, len(CLASSES), H, W) and ripe logits (B, 1, H, W)"""

    def __init__(self, width: int = 32) -> None:
        super().__init__()
        w = width
        self.enc1 = block(4, w)
        self.enc2 = block(w, w * 2)
        self.enc3 = block(w * 2, w * 4)
        self.enc4 = block(w * 4, w * 8)
        self.up3 = nn.ConvTranspose2d(w * 8, w * 4, 2, stride=2)
        self.dec3 = block(w * 8, w * 4)
        self.up2 = nn.ConvTranspose2d(w * 4, w * 2, 2, stride=2)
        self.dec2 = block(w * 4, w * 2)
        self.up1 = nn.ConvTranspose2d(w * 2, w, 2, stride=2)
        self.dec1 = block(w * 2, w)
        self.classes = nn.Conv2d(w, len(CLASSES), 1)
        self.ripe = nn.Conv2d(w, 1, 1)

    def forward(self, rgb: torch.Tensor, depth: torch.Tensor) -> tuple[torch.Tensor, torch.Tensor]:
        """rgb (B, H, W, 3) uint8, depth (B, H, W) in blocks, H and W divisible by 8"""
        x = torch.cat([rgb.permute(0, 3, 1, 2).float() / 255.0, (depth.float().clamp(0, DEPTH_MAX) / DEPTH_MAX).unsqueeze(1)], dim=1)
        e1 = self.enc1(x)
        e2 = self.enc2(F.max_pool2d(e1, 2))
        e3 = self.enc3(F.max_pool2d(e2, 2))
        e4 = self.enc4(F.max_pool2d(e3, 2))
        d3 = self.dec3(torch.cat([self.up3(e4), e3], dim=1))
        d2 = self.dec2(torch.cat([self.up2(d3), e2], dim=1))
        d1 = self.dec1(torch.cat([self.up1(d2), e1], dim=1))
        return self.classes(d1), self.ripe(d1)


def mask_lut(mask_ids: dict) -> np.ndarray:
    """Reader classes to the mod's mask ids, so code written for the mask stream can run on the reader's output"""
    id_of = {name: i + 1 for i, name in enumerate(mask_ids["blocks"])}
    # some solid block for "<other>", it still counts as something in the way
    other = id_of.get("minecraft:bedrock", 1)
    return np.asarray([0, other, *(id_of.get(name, other) for name in CLASSES[2:])], dtype=np.uint16)


class Reader:
    """A trained BlockReader for one frame at a time, returning class indices and ripe probabilities"""

    def __init__(self, checkpoint, device: torch.device | None = None) -> None:
        self.device = device or pick_device()
        self.model = BlockReader().to(self.device)
        load_weights(self.model, checkpoint, self.device)
        self.model.eval()

    @torch.no_grad()
    def read(self, rgb: np.ndarray, depth: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
        logits, ripe = self.model(torch.from_numpy(rgb)[None].to(self.device), torch.from_numpy(depth)[None].to(self.device))
        return logits[0].argmax(0).cpu().numpy().astype(np.uint8), torch.sigmoid(ripe[0, 0]).cpu().numpy()
