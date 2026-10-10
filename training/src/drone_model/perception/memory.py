"""
The drone's working memory: a voxel map of what the block reader has seen.
Each cell keeps votes per block class and ripe votes, the class with the most votes is what the drone believes is
there. Every update returns the cells whose belief changed, so the dashboard can show how the memory evolves
"""

from __future__ import annotations

from dataclasses import dataclass, field

import numpy as np

from .worldmap import backproject
from .reader import CLASSES, CROPS, MOBS, OTHER, SKY

AIR = "minecraft:air"
# blocks a mob can stand in or swim through, and the reader's catch-all, which is too vague to call solid
NOT_SOLID = {AIR, "<other>", "minecraft:water", "mcdrone:marker", *CROPS}
# a cell needs this many votes before the drone believes anything is there
MIN_VOTES = 4
# votes a cell keeps when its block is broken, so later frames win quickly
FORGET = 0
# a hit counts fully up to this many blocks away and less beyond, far views at grazing angles misread more
NEAR = 5.0
FAR_WEIGHT = 0.25


def base_name(label: str) -> str:
    """A memory label or block state as a block name: no ripeness, no properties"""
    return label.split(" ")[0].split("[")[0]


@dataclass
class Cell:
    votes: np.ndarray = field(default_factory=lambda: np.zeros(len(CLASSES), dtype=np.float32))
    ripe: int = 0
    unripe: int = 0
    seen: int = -1

    def belief(self) -> str:
        """The block the drone thinks is here, air until enough votes agree on something"""
        if self.votes.sum() < MIN_VOTES:
            return AIR
        best = int(self.votes.argmax())
        return AIR if best == SKY else CLASSES[best] if best != OTHER else "<other>"

    @property
    def is_ripe(self) -> bool | None:
        n = self.ripe + self.unripe
        return None if n == 0 else self.ripe > self.unripe


@dataclass
class Change:
    cell: tuple[int, int, int]
    before: str
    after: str
    cause: str

    def to_json(self) -> dict:
        return {"cell": list(self.cell), "before": self.before, "after": self.after, "cause": self.cause}


class VoxelMemory:
    def __init__(self) -> None:
        self.cells: dict[tuple[int, int, int], Cell] = {}
        self.step = 0
        # cells by believed block name, brought up to date for the cells touched since the last lookup
        self.by_block: dict[str, set[tuple[int, int, int]]] = {}
        self.indexed: dict[tuple[int, int, int], str] = {}
        self.touched: set[tuple[int, int, int]] = set()

    def label(self, key: tuple[int, int, int]) -> str:
        cell = self.cells.get(key)
        if cell is None:
            return AIR
        name = cell.belief()
        if name in ("minecraft:wheat", "minecraft:carrots", "minecraft:potatoes", "minecraft:beetroots") and cell.is_ripe is not None:
            return name + (" ripe" if cell.is_ripe else " growing")
        return name

    def touch(self, key: tuple[int, int, int]) -> None:
        """Marks a cell whose votes were set outside observe and apply_events, so lookups by block see the change"""
        self.touched.add(key)

    def cells_of(self, block: str) -> list[tuple[int, int, int]]:
        """Every cell the drone believes holds the block kind, whatever its state or ripeness"""
        for key in self.touched:
            old = self.indexed.pop(key, None)
            if old is not None:
                self.by_block[old].discard(key)
            cell = self.cells.get(key)
            name = base_name(cell.belief()) if cell is not None else AIR
            if name != AIR:
                self.indexed[key] = name
                self.by_block.setdefault(name, set()).add(key)
        self.touched.clear()
        return sorted(self.by_block.get(block, ()))

    def solid(self, key: tuple[int, int, int]) -> bool:
        """Whether the drone believes a block that nothing can stand inside fills the cell"""
        cell = self.cells.get(key)
        return cell is not None and cell.belief() not in NOT_SOLID

    def observe(self, state: dict, depth: np.ndarray, classes: np.ndarray, ripe: np.ndarray | None, depth_max: float = 64.0) -> list[Change]:
        """Adds one frame of reader output, returns the cells whose belief changed. ripe is None when the frame has no ripeness"""
        self.step += 1
        # mobs move, they aren't blocks
        view = backproject(state, depth, depth_max, valid=(classes != SKY) & ~np.isin(classes, MOBS))
        cls = classes[np.ix_(view.rows, view.cols)][view.hit]
        rp = ripe[np.ix_(view.rows, view.cols)][view.hit] if ripe is not None else np.full(len(cls), np.nan)
        dist = depth[np.ix_(view.rows, view.cols)][view.hit]
        keep = view.interior
        inside, cls, rp = view.inside[keep], cls[keep], rp[keep]
        weight = np.clip(NEAR / np.maximum(dist[keep], NEAR), FAR_WEIGHT, 1.0)
        # a short block (a young crop) can hold both ends of the step, it was hit, not crossed
        crossed = view.outside[keep][np.any(view.outside[keep] != inside, axis=1)]
        touched = {tuple(int(v) for v in c) for c in np.unique(inside, axis=0)}
        # rays that crossed a remembered block on the way to something else saw it gone
        cleared = {tuple(int(v) for v in c) for c in np.unique(crossed, axis=0)} & self.cells.keys()
        touched |= cleared
        self.touched |= touched
        before = {k: self.label(k) for k in touched}
        for key, n in zip(*np.unique(crossed, axis=0, return_counts=True)):
            key = tuple(int(v) for v in key)
            if key in cleared:
                self.cells[key].votes[SKY] += int(n)
        crop_ids = [CLASSES.index(c) for c in ("minecraft:wheat", "minecraft:carrots", "minecraft:potatoes", "minecraft:beetroots")]
        for (x, y, z), c, r, w in zip(inside, cls, rp, weight):
            key = (int(x), int(y), int(z))
            cell = self.cells.setdefault(key, Cell())
            cell.votes[c] += w
            cell.seen = self.step
            if c in crop_ids and not np.isnan(r):
                if r > 0.5:
                    cell.ripe += 1
                else:
                    cell.unripe += 1
        return [Change(k, before[k], self.label(k), "seen") for k in sorted(touched) if before[k] != self.label(k)]

    def apply_events(self, events: list[dict]) -> list[Change]:
        """Breaks and placements the drone made, which it knows without looking"""
        changes = []
        for event in events:
            kind = event.get("type")
            if kind not in ("break", "place") or "pos" not in event:
                continue
            key = tuple(event["pos"])
            self.touched.add(key)
            before = self.label(key)
            cell = self.cells.setdefault(key, Cell())
            cell.votes[:] = FORGET
            cell.ripe = cell.unripe = 0
            if kind == "place":
                idx = CLASSES.index(event["block"]) if event["block"] in CLASSES else OTHER
                cell.votes[idx] = MIN_VOTES
            changes.append(Change(key, before, self.label(key), "broke" if kind == "break" else "placed"))
        return changes

    def box(self, lo, hi) -> dict[tuple[int, int, int], str]:
        """Believed blocks in an inclusive box, air left out"""
        out = {}
        for key in self.cells:
            if all(lo[i] <= key[i] <= hi[i] for i in range(3)):
                name = self.label(key)
                if name != AIR:
                    out[key] = name
        return out

    def schematic(self, lo, hi):
        """The box as a Sponge schematic, the copy job's read of its source"""
        from mcdrone.schematic import Schematic

        w, h, l = (hi[i] - lo[i] + 1 for i in range(3))
        s = Schematic.empty(w, h, l)
        for (x, y, z), name in self.box(lo, hi).items():
            if not name.startswith("<"):
                s.blocks[y - lo[1], z - lo[2], x - lo[0]] = name.split(" ")[0]
        return s
