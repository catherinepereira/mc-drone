"""
Mobs the drone has seen lately and their kinds. Pixels the mask calls a mob (the mod's mask, or the block reader's
classes mapped into mask ids) are back-projected through depth and grouped into mobs, one kind at a time. Hits that land
in cells the drone's voxel memory has as solid blocks are dropped first: the reader's mob outline spilling onto the
ground, or the reader misreading a block. Each mob stays where it was last seen until the camera looks at that spot and
sees past it or sees a block there, or it goes unseen for a long while
"""

from __future__ import annotations

import math
from collections.abc import Callable
from dataclasses import dataclass, field

import numpy as np

from .worldmap import backproject, project

# hits closer than this belong to the same mob, and a sighting this close to a mob of another kind is that mob misread
GROUP_DIST = 1.2
# a sighting this close to a remembered mob is that mob, moved
MATCH_DIST = 2.5
# a sighting needs this many sampled pixels, a stray pixel or two is reader noise
MIN_HITS = 3
# frames the camera looked at a mob's spot and saw past it before the mob counts as gone
GONE_LOOKS = 3
# a mob unseen this many frames is forgotten
FORGET_FRAMES = 400
# a killed mob's body lingers for its death animation, sightings there are ignored this many frames
CORPSE_FRAMES = 30
CORPSE_DIST = 1.5
# a remembered mob with more than this share of its last sighting's cells now solid on the map was a misread
MISREAD_SHARE = 0.5


def hostile_ids(mask_ids: dict) -> set[int]:
    """Mask ids of every hostile mob type, the monster category"""
    base = mask_ids["entityBase"]
    return {base + j for j, category in enumerate(mask_ids.get("categories", [])) if category == "monster"}


def mob_ids(mask_ids: dict) -> dict[int, str]:
    """Mask id to entity name for every kind of mob: the mob categories, which leave out items, players, and drones, and villagers"""
    base = mask_ids["entityBase"]
    categories = mask_ids.get("categories", [])
    return {
        base + j: name for j, name in enumerate(mask_ids["entities"])
        if (j < len(categories) and categories[j] != "misc") or name == "minecraft:villager"
    }


def prey_ids(prey, mask_ids: dict) -> set[int]:
    """The mask ids a job's prey covers: "hostile", "all", or a list of entity names, see the mod's task.Prey"""
    mobs = mob_ids(mask_ids)
    if prey is None or prey == "hostile":
        return hostile_ids(mask_ids) & set(mobs)
    if prey == "all":
        return set(mobs)
    names = {p if ":" in p else f"minecraft:{p}" for p in prey}
    return {i for i, name in mobs.items() if name in names}


# compared by identity, a mob is the same mob wherever it moves
@dataclass(eq=False)
class Mob:
    pos: np.ndarray  # the middle of its hits, the side of its body facing the camera
    seen: int  # frame it was last seen
    votes: dict[int, int] = field(default_factory=dict)  # sightings per mask id, the reader can misread a frame
    misses: int = 0
    # the blocks its last sighting's hits landed in, a misread of a block before the map knew that block
    cells: np.ndarray = field(default_factory=lambda: np.zeros((0, 3), dtype=np.int64))

    @property
    def kind(self) -> int:
        """The mask id it was seen as most"""
        return max(self.votes, key=self.votes.get)

    def saw(self, pos: np.ndarray, kind: int, frame: int, cells: np.ndarray | None = None) -> None:
        self.pos, self.seen, self.misses = pos, frame, 0
        if cells is not None:
            self.cells = cells
        self.votes[kind] = self.votes.get(kind, 0) + 1


class MobTracker:
    def __init__(self, ids) -> None:
        """ids are the mask ids to track"""
        self.ids = np.asarray(sorted(ids), dtype=np.int64)
        self.id_set = {int(i) for i in self.ids}
        self.mobs: list[Mob] = []
        self.corpses: list[tuple[np.ndarray, int]] = []
        self.frame = 0

    def update(
        self, state: dict, depth: np.ndarray, mask: np.ndarray, depth_max: float, solid: Callable[[tuple[int, int, int]], bool] | None = None,
    ) -> None:
        """
        solid says whether the drone's map has a solid block in a cell. Hits inside solid blocks leave every sighting,
        and a remembered mob goes once the map has most of its last sighting's cells solid
        """
        self.frame += 1
        self.corpses = [(p, until) for p, until in self.corpses if until > self.frame]
        view = backproject(state, depth, depth_max, valid=np.isin(mask, self.ids))
        kinds = mask[np.ix_(view.rows, view.cols)][view.hit].astype(np.int64)
        sightings = []
        for k in np.unique(kinds):
            of_kind = np.flatnonzero(kinds == k)
            if solid is not None:
                of_kind = of_kind[[not solid(tuple(int(v) for v in c)) for c in view.inside[of_kind]]]
            for g in group(view.points[of_kind]):
                idx = of_kind[g]
                if len(idx) >= MIN_HITS:
                    sightings.append((int(k), view.points[idx], view.inside[idx]))
        matched: set[int] = set()
        for kind, points, cells in sightings:
            center = points.mean(axis=0)
            if any(np.linalg.norm(center - p) < CORPSE_DIST for p, _ in self.corpses):
                continue
            free = [i for i in range(len(self.mobs)) if i not in matched]
            dist = {i: float(np.linalg.norm(self.mobs[i].pos - center)) for i in free}
            near = [i for i in free if self.mobs[i].kind == kind and dist[i] < MATCH_DIST] or [i for i in free if dist[i] < GROUP_DIST]
            if near:
                i = min(near, key=dist.get)
            else:
                self.mobs.append(Mob(center, self.frame))
                i = len(self.mobs) - 1
            self.mobs[i].saw(center, kind, self.frame, cells)
            matched.add(i)
        for i, mob in enumerate(self.mobs):
            if i in matched:
                continue
            px = project(state, mob.pos, depth.shape)
            if px is None:
                continue
            seen = float(depth[px[0], px[1]])
            # looked right at where it was and the ray went on past it, or stopped there on what the reader calls a block,
            # looking down at a spot a mob left the floor is right behind it
            if seen > px[2] + 1.0 or (abs(seen - px[2]) <= 1.0 and int(mask[px[0], px[1]]) not in self.id_set):
                mob.misses += 1
        self.mobs = [
            m for m in self.mobs
            if m.misses < GONE_LOOKS and self.frame - m.seen < FORGET_FRAMES and not (solid is not None and misread(m.cells, solid))
        ]

    def scanned(self, sightings: list[tuple]) -> None:
        """
        Scan perception: the job hands over every mob in its region each step, as (position, mask id). Each keeps its
        Mob, matched to the nearest one of its kind remembered, so a chase stays on the same mob
        """
        self.frame += 1
        old, self.mobs = self.mobs, []
        for p, kind in sightings:
            pos = np.asarray(p, dtype=np.float64)
            same = [m for m in old if m.kind == kind]
            near = min(same, key=lambda m: np.linalg.norm(m.pos - pos), default=None)
            if near is None or np.linalg.norm(near.pos - pos) >= MATCH_DIST:
                near = Mob(pos, self.frame)
            else:
                old.remove(near)
            near.saw(pos, kind, self.frame)
            self.mobs.append(near)

    def killed(self, pos) -> None:
        """Forgets the mob nearest pos, its body stays in view while it dies"""
        if not self.mobs:
            return
        p = np.asarray(pos, dtype=np.float64)
        mob = min(self.mobs, key=lambda m: np.linalg.norm(m.pos - p))
        self.mobs.remove(mob)
        self.corpses.append((mob.pos, self.frame + CORPSE_FRAMES))


def misread(cells: np.ndarray, solid: Callable[[tuple[int, int, int]], bool]) -> bool:
    """Whether more than MISREAD_SHARE of the cells hold solid blocks on the map"""
    return len(cells) > 0 and float(np.mean([solid(tuple(int(v) for v in c)) for c in cells])) > MISREAD_SHARE


def group(points: np.ndarray) -> list[np.ndarray]:
    """Greedy grouping of hit points, each joins the first group whose middle is within GROUP_DIST. Returns indices"""
    groups: list[list[int]] = []
    centers: list[np.ndarray] = []
    for j, p in enumerate(points):
        for i, c in enumerate(centers):
            if math.dist(p, c) < GROUP_DIST:
                groups[i].append(j)
                centers[i] = c + (p - c) / len(groups[i])
                break
        else:
            groups.append([j])
            centers.append(p.astype(np.float64))
    return [np.asarray(g, dtype=np.int64) for g in groups]
