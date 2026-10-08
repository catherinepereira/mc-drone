"""
Hostile mobs the drone has seen lately. Pixels the mask calls a hostile mob (the mod's mask, or the block reader's
classes mapped into mask ids) are back-projected through depth and grouped into mobs. Each mob stays where it was last
seen until the camera looks at that spot and sees past it, or it goes unseen for a long while
"""

from __future__ import annotations

import math
from dataclasses import dataclass

import numpy as np

from .worldmap import backproject, project

# hits closer than this belong to the same mob
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


def hostile_ids(mask_ids: dict) -> set[int]:
    """Mask ids of every hostile mob type, the monster category"""
    base = mask_ids["entityBase"]
    return {base + j for j, category in enumerate(mask_ids.get("categories", [])) if category == "monster"}


# compared by identity, a mob is the same mob wherever it moves
@dataclass(eq=False)
class Mob:
    pos: np.ndarray  # the middle of its hits, the side of its body facing the camera
    seen: int  # frame it was last seen
    misses: int = 0


class MobTracker:
    def __init__(self, ids: set[int]) -> None:
        self.ids = np.asarray(sorted(ids), dtype=np.int64)
        self.mobs: list[Mob] = []
        self.corpses: list[tuple[np.ndarray, int]] = []
        self.frame = 0

    def update(self, state: dict, depth: np.ndarray, mask: np.ndarray, depth_max: float) -> None:
        self.frame += 1
        self.corpses = [(p, until) for p, until in self.corpses if until > self.frame]
        view = backproject(state, depth, depth_max, valid=np.isin(mask, self.ids))
        sightings = [g for g in group(view.points) if len(g) >= MIN_HITS]
        matched: set[int] = set()
        for points in sightings:
            center = points.mean(axis=0)
            if any(np.linalg.norm(center - p) < CORPSE_DIST for p, _ in self.corpses):
                continue
            near = [i for i, m in enumerate(self.mobs) if i not in matched and np.linalg.norm(m.pos - center) < MATCH_DIST]
            if near:
                i = min(near, key=lambda i: np.linalg.norm(self.mobs[i].pos - center))
                self.mobs[i] = Mob(center, self.frame)
            else:
                self.mobs.append(Mob(center, self.frame))
                i = len(self.mobs) - 1
            matched.add(i)
        for i, mob in enumerate(self.mobs):
            if i in matched:
                continue
            px = project(state, mob.pos, depth.shape)
            # looked right at where it was and the ray went on past it
            if px is not None and depth[px[0], px[1]] > px[2] + 1.0:
                mob.misses += 1
        self.mobs = [m for m in self.mobs if m.misses < GONE_LOOKS and self.frame - m.seen < FORGET_FRAMES]

    def scanned(self, positions: list) -> None:
        """
        Scan perception: the job hands over every hostile mob in its region each step. Each keeps its Mob, matched to
        the nearest one remembered, so a chase stays on the same mob
        """
        self.frame += 1
        old, self.mobs = self.mobs, []
        for p in positions:
            pos = np.asarray(p, dtype=np.float64)
            near = min(old, key=lambda m: np.linalg.norm(m.pos - pos), default=None)
            if near is not None and np.linalg.norm(near.pos - pos) < MATCH_DIST:
                old.remove(near)
                near.pos, near.seen, near.misses = pos, self.frame, 0
                self.mobs.append(near)
            else:
                self.mobs.append(Mob(pos, self.frame))

    def killed(self, pos) -> None:
        """Forgets the mob nearest pos, its body stays in view while it dies"""
        if not self.mobs:
            return
        p = np.asarray(pos, dtype=np.float64)
        mob = min(self.mobs, key=lambda m: np.linalg.norm(m.pos - p))
        self.mobs.remove(mob)
        self.corpses.append((mob.pos, self.frame + CORPSE_FRAMES))


def group(points: np.ndarray) -> list[np.ndarray]:
    """Greedy grouping of hit points, each joins the first group whose middle is within GROUP_DIST"""
    groups: list[list[np.ndarray]] = []
    centers: list[np.ndarray] = []
    for p in points:
        for i, c in enumerate(centers):
            if math.dist(p, c) < GROUP_DIST:
                groups[i].append(p)
                centers[i] = c + (p - c) / len(groups[i])
                break
        else:
            groups.append([p])
            centers.append(p.astype(np.float64))
    return [np.asarray(g) for g in groups]
