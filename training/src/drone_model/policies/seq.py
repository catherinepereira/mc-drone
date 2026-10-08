"""
Recurrent policy for the tool tasks. A CNN reads RGB, depth, and the semantic mask, an LSTM carries what the drone has
seen across the episode (the reference build, where the site was), and three heads pick the flight vector, the tool,
and the inventory slot. Blocks in the mask and items in the inventory share one embedding table, so choosing a slot is
matching an item against what the camera showed
"""

from __future__ import annotations

import math

import numpy as np
import torch
from torch import nn
from torch.nn import functional as F

from ..framework.spec import Agent, PolicySpec, Step
from ..perception.blocks import BUILD_PALETTE

# block and item names the policy tells apart, everything else is "other"
VOCAB = (
    "<empty>",
    "<other>",
    *BUILD_PALETTE,
    "minecraft:cyan_concrete",
    "minecraft:lime_concrete",
    "minecraft:light_gray_concrete",
    "minecraft:stone_bricks",
    "minecraft:grass_block",
    "minecraft:oak_log",
    "minecraft:oak_leaves",
    "minecraft:stone",
    "minecraft:coal_ore",
    "minecraft:chest",
)
INDEX = {name: i for i, name in enumerate(VOCAB)}
SLOTS = 27
EMBED = 8
# velocity (3), sin and cos of yaw, pitch / 90, geofence position (3), mining progress, container open, selected slot / 26
STATE_DIM = 12
MOVE_DIM = 5
HEIGHT, WIDTH = 60, 80
DEPTH_MAX = 64.0
COUNT_SCALE = 16.0
# the tools the policy picks from, the drone's first five, it was trained before attack existed
TOOLS = ("none", "break", "place", "open", "close")
# place and break fire on a handful of steps per episode, weight them up against "none"
TOOL_WEIGHTS = (1.0, 20.0, 20.0, 2.0, 2.0)


def vocab_index(name: str | None) -> int:
    if not name or name == "minecraft:air":
        return 0
    return INDEX.get(name, 1)


def mask_lookup(mask_ids: dict) -> np.ndarray:
    """Maps the mod's mask ids (0 is air, then mask_ids["blocks"] in order) to VOCAB indices"""
    return np.asarray([0] + [vocab_index(name) for name in mask_ids["blocks"]], dtype=np.uint8)


def conv(cin: int, cout: int, k: int, s: int) -> nn.Sequential:
    return nn.Sequential(nn.Conv2d(cin, cout, k, s, k // 2), nn.GroupNorm(8, cout), nn.ReLU(inplace=True))


class SeqToolPolicy(nn.Module):
    def __init__(self, hidden: int = 384) -> None:
        super().__init__()
        self.hidden = hidden
        self.embed = nn.Embedding(len(VOCAB), EMBED)
        self.encoder = nn.Sequential(
            conv(4 + EMBED, 32, 5, 2),
            conv(32, 64, 3, 2),
            conv(64, 96, 3, 2),
            conv(96, 128, 3, 2),
            nn.AdaptiveAvgPool2d((3, 4)),
            nn.Flatten(),
            nn.Linear(128 * 12, 256),
            nn.ReLU(inplace=True),
        )
        self.slot_encoder = nn.Sequential(nn.Linear(EMBED + 1, 32), nn.ReLU(inplace=True))
        self.inventory = nn.Sequential(nn.Linear(SLOTS * 32, 128), nn.ReLU(inplace=True))
        prev_dim = MOVE_DIM + len(TOOLS)
        self.core = nn.LSTM(256 + 128 + STATE_DIM + prev_dim, hidden, batch_first=True)
        self.move = nn.Sequential(nn.Linear(hidden, 128), nn.ReLU(inplace=True), nn.Linear(128, MOVE_DIM), nn.Tanh())
        self.tool = nn.Sequential(nn.Linear(hidden, 128), nn.ReLU(inplace=True), nn.Linear(128, len(TOOLS)))
        self.slot_query = nn.Linear(hidden, 32)

    def forward(self, rgb, depth, mask, state, items, counts, prev, hc=None):
        """
        Every input is (B, T, ...): rgb uint8 (H, W, 3), depth uint8 (H, W) as depth / DEPTH_MAX * 255,
        mask (H, W) VOCAB indices, state (STATE_DIM), items (SLOTS) VOCAB indices, counts (SLOTS),
        prev (MOVE_DIM + len(TOOLS)) the previous action.
        Returns move (B, T, 5), tool logits (B, T, len(TOOLS)), slot logits (B, T, SLOTS), and the LSTM state
        """
        b, t = rgb.shape[:2]
        image = torch.cat(
            [
                rgb.flatten(0, 1).permute(0, 3, 1, 2).float() / 255.0,
                depth.flatten(0, 1).unsqueeze(1).float() / 255.0,
                self.embed(mask.flatten(0, 1).long()).permute(0, 3, 1, 2),
            ],
            dim=1,
        )
        seen = self.encoder(image).view(b, t, -1)
        slots = self.slot_encoder(torch.cat([self.embed(items.long()), (counts.float() / COUNT_SCALE).unsqueeze(-1)], dim=-1))
        inventory = self.inventory(slots.flatten(2))
        out, hc = self.core(torch.cat([seen, inventory, state, prev], dim=-1), hc)
        slot_logits = (slots * self.slot_query(out).unsqueeze(2)).sum(-1)
        # an empty slot can't be the one to place from
        slot_logits = slot_logits.masked_fill(counts == 0, -1e4)
        return self.move(out), self.tool(out), slot_logits, hc


def state_features(state: dict) -> np.ndarray:
    """The non-privileged state the policy reads, from a bridge or recorded state dict"""
    vel = state.get("vel", [0.0, 0.0, 0.0])
    yaw = math.radians(state.get("yaw", 0.0))
    box, pos = state.get("bounds"), state.get("pos")
    fence = [(pos[i] - box[i]) / (box[i + 3] - box[i]) * 2 - 1 for i in range(3)] if box and pos else [0.0, 0.0, 0.0]
    breaking = state.get("breaking")
    return np.asarray(
        [*vel, math.sin(yaw), math.cos(yaw), state.get("pitch", 0.0) / 90.0, *fence,
         breaking["progress"] if breaking else 0.0, 1.0 if state.get("container") else 0.0, state.get("selectedSlot", 0) / (SLOTS - 1)],
        dtype=np.float32,
    )


def inventory_features(state: dict) -> tuple[np.ndarray, np.ndarray]:
    items = np.zeros(SLOTS, dtype=np.uint8)
    counts = np.zeros(SLOTS, dtype=np.float32)
    for i, (name, count) in enumerate((state.get("inventory") or [])[:SLOTS]):
        if count > 0:
            items[i] = vocab_index(name)
            counts[i] = count
    return items, counts


def frame_features(rgb: np.ndarray, depth: np.ndarray, mask: np.ndarray, lookup: np.ndarray) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """Full-size frames down to the policy's HEIGHT x WIDTH, nearest neighbor so mask ids stay ids"""
    sy = rgb.shape[0] // HEIGHT
    sx = rgb.shape[1] // WIDTH
    rgb = rgb.reshape(HEIGHT, sy, WIDTH, sx, 3).mean(axis=(1, 3)).astype(np.uint8)
    depth = (np.clip(depth[::sy, ::sx], 0, DEPTH_MAX) / DEPTH_MAX * 255).astype(np.uint8)
    mask = lookup[np.minimum(mask[::sy, ::sx], len(lookup) - 1)]
    return rgb, depth, mask


def prev_features(move, tool: int) -> np.ndarray:
    out = np.zeros(MOVE_DIM + len(TOOLS), dtype=np.float32)
    out[:MOVE_DIM] = move
    out[MOVE_DIM + tool] = 1.0
    return out


def executed_features(action: dict | None) -> np.ndarray:
    """The previous step's action as the policy reads it, nothing on the first step"""
    if action is None:
        return prev_features(np.zeros(MOVE_DIM, dtype=np.float32), 0)
    return prev_features(np.asarray(action["move"], dtype=np.float32), int(action["tool"]))


class SeqAgent(Agent):
    """Runs a trained SeqToolPolicy one env step at a time, carrying the LSTM state across the episode"""

    def __init__(self, model: SeqToolPolicy, mask_ids: dict) -> None:
        self.model = model
        self.device = next(model.parameters()).device
        self.lookup = mask_lookup(mask_ids)
        self.reset()

    def reset(self) -> None:
        self.hc = None
        self.prev = executed_features(None)

    @torch.no_grad()
    def act(self, step: Step) -> dict:
        rgb, depth, mask = frame_features(step.obs["rgb"], step.obs["depth"], step.obs["mask"], self.lookup)
        items, counts = inventory_features(step.state)
        inputs = [rgb, depth, mask, state_features(step.state), items, counts, self.prev]
        tensors = [torch.from_numpy(np.asarray(a))[None, None].to(self.device) for a in inputs]
        move, tool, slot, self.hc = self.model(*tensors, self.hc)
        action = {
            "move": move[0, 0].float().cpu().numpy(), "tool": int(tool[0, 0].argmax()), "slot": int(slot[0, 0].argmax()),
            "transfer": np.zeros(2, dtype=np.int64),
        }
        self.executed(action)
        return action

    def executed(self, action: dict) -> None:
        # the next step sees what actually flew, which under DAgger can be the teacher's action
        self.prev = executed_features(action)


class SeqSpec(PolicySpec):
    """Trains on whole episodes, so the LSTM learns to carry what it saw early (the reference build) to where it acts on it"""

    name = "seq"
    sequence = True
    # 8 full episodes overflow a 12 GB GPU with the game open, and Windows then pages video memory to RAM
    batch_size = 4

    def model(self) -> nn.Module:
        return SeqToolPolicy()

    def record(self, step: Step) -> dict[str, np.ndarray]:
        rgb, depth, mask = frame_features(step.obs["rgb"], step.obs["depth"], step.obs["mask"], mask_lookup(step.mask_ids))
        items, counts = inventory_features(step.state)
        return {
            "rgb": rgb, "depth": depth, "mask": mask, "state": state_features(step.state), "items": items, "counts": counts,
            "prev": executed_features(step.prev), "move": np.asarray(step.label["move"], dtype=np.float32),
            "tool": np.int64(step.label["tool"]), "slot": np.int64(step.label["slot"]),
        }

    def loss(self, model: nn.Module, b: dict[str, torch.Tensor]) -> dict[str, torch.Tensor]:
        move, tool, slot, _ = model(b["rgb"], b["depth"], b["mask"], b["state"], b["items"], b["counts"], b["prev"])
        valid = b["valid"]
        move_loss = F.mse_loss(move[valid].float(), b["move"][valid])
        weights = torch.tensor(TOOL_WEIGHTS, device=tool.device)
        tool_loss = F.cross_entropy(tool[valid].float(), b["tool"][valid], weight=weights)
        # the slot matters most on the step a block goes down, and the teacher holds the next block's slot while lining up
        placing = valid & (b["tool"] == TOOLS.index("place"))
        # outside the build phase the teacher's slot is a default that can point at an empty slot, skip those
        filled = valid & (b["counts"].gather(2, b["slot"].unsqueeze(-1)).squeeze(-1) > 0)
        slot_all = F.cross_entropy(slot[filled].float(), b["slot"][filled]) if filled.any() else move_loss * 0
        slot_place = F.cross_entropy(slot[placing].float(), b["slot"][placing]) if placing.any() else slot_all * 0
        place_recall = (tool[placing].argmax(-1) == b["tool"][placing]).float().mean() if placing.any() else move_loss * 0
        return {
            "loss": move_loss + tool_loss + slot_place + 0.1 * slot_all, "move": move_loss, "tool": tool_loss, "slot": slot_place,
            "place_recall": place_recall,
        }

    def agent(self, model: nn.Module, mask_ids: dict) -> Agent:
        return SeqAgent(model, mask_ids)
