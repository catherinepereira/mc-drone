"""
What a scripted expert is allowed to know: its own pose plus what its camera has seen.
Each frame's depth and semantic mask are back-projected into world points. A hit below the camera raises that
column's known floor, a hit above it lowers the column's known ceiling, and target blocks (ore, chests, the marker,
the pad) are remembered once seen.
Nothing here reads the arena layout
"""

from __future__ import annotations

import math
from dataclasses import dataclass

import numpy as np

# privileged fields an expert must never read
HIDDEN_STATE = ("arena", "marker")
CLEARANCE = 0.55
PIXEL_STRIDE = 2
# a column blocks the planner when its top is above the drone's feet by more than this
STEP_MARGIN = -0.1
# or when its ceiling is below the drone's feet plus this, the drone is 0.4 tall
HEAD_MARGIN = 0.5


def perceivable(state: dict) -> dict:
    return {k: v for k, v in state.items() if k not in HIDDEN_STATE}


class WorldMap:
    """Column heights and landmarks built from the drone's own frames"""

    def __init__(self, mask_ids: dict) -> None:
        names = mask_ids["blocks"]
        self.id_of = {name: i + 1 for i, name in enumerate(names)}
        self.landmark_ids = {
            "coal_ore": self.id_of.get("minecraft:coal_ore"),
            "chest": self.id_of.get("minecraft:chest"),
            "marker": self.id_of.get("mcdrone:marker"),
            "pad": self.id_of.get("minecraft:lime_concrete"),
        }
        self.heights: dict[tuple[int, int], float] = {}
        # lowest block seen above the camera per column, a roof, an overhang, or the upper part of a pillar
        self.ceilings: dict[tuple[int, int], float] = {}
        # votes per block for each landmark kind, a grazing ray can land a hit in the block below
        self.votes: dict[str, dict[tuple[int, int, int], int]] = {k: {} for k in self.landmark_ids}
        self.strict: set[str] = set()
        self.last_hits: tuple | None = None
        # how many rays crossed each block on the way to something else, evidence the block is empty
        self.air: dict[tuple[int, int, int], int] = {}
        # the drone's feet height while planning, columns below it are passable
        self.fly_y = math.inf
        # the column being approached, its own block shouldn't block the path to it
        self.ignore: tuple[int, int] | None = None
        # the task's geofence as x0, y0, z0, x1, y1, z1, the planner treats everything outside as blocked
        self.fence: list[float] | None = None

    def track(self, name: str, block: str, strict: bool = False) -> None:
        """
        Start counting votes for another block kind, it has to be in mask_ids.
        Strict kinds only count hits well inside a face, for telling apart blocks stacked right next to each other
        """
        self.landmark_ids[name] = self.id_of.get(block)
        self.votes.setdefault(name, {})
        if strict:
            self.strict.add(name)

    # planner interface, see expert.plan and expert.waypoint
    @property
    def boxes(self) -> list:
        return [c for c in self.heights.keys() | self.ceilings.keys() if self.blocked(c)]

    def ceiling(self, cell: tuple[int, int]) -> float:
        """The column's ceiling, ignored once the floor is known to reach it (the column is solid from the ground up)"""
        top = self.ceilings.get(cell, math.inf)
        return top if top >= self.heights.get(cell, -math.inf) else math.inf

    def blocked(self, cell: tuple[int, int]) -> bool:
        if cell == self.ignore:
            return False
        return self.heights.get(cell, -math.inf) > self.fly_y + STEP_MARGIN or self.ceiling(cell) < self.fly_y + HEAD_MARGIN

    def headroom(self, x: float, z: float) -> float:
        """Lowest known ceiling over the drone's footprint at x, z"""
        cells = {(math.floor(x + dx), math.floor(z + dz)) for dx in (-0.3, 0.3) for dz in (-0.3, 0.3)}
        return min(self.ceiling(c) for c in cells)

    def free(self, x: float, z: float) -> bool:
        f = self.fence
        if f is not None and not (f[0] + CLEARANCE <= x <= f[3] - CLEARANCE and f[2] + CLEARANCE <= z <= f[5] - CLEARANCE):
            return False
        cx, cz = math.floor(x), math.floor(z)
        for dx in (-1, 0, 1):
            for dz in (-1, 0, 1):
                cell = (cx + dx, cz + dz)
                if not self.blocked(cell):
                    continue
                nx = min(max(x, cell[0]), cell[0] + 1)
                nz = min(max(z, cell[1]), cell[1] + 1)
                if math.hypot(x - nx, z - nz) < CLEARANCE:
                    return False
        return True

    def clear_line(self, ax: float, az: float, bx: float, bz: float) -> bool:
        steps = max(1, int(math.hypot(bx - ax, bz - az) / 0.25))
        return all(self.free(ax + (bx - ax) * t / steps, az + (bz - az) * t / steps) for t in range(steps + 1))

    def ground_near(self, x: float, z: float, default: float, radius: int = 2) -> float:
        """Median known column height around x, z, which tracks hills but ignores a lone pillar"""
        cx, cz = math.floor(x), math.floor(z)
        tops = [self.heights[(cx + dx, cz + dz)] for dx in range(-radius, radius + 1) for dz in range(-radius, radius + 1) if (cx + dx, cz + dz) in self.heights]
        return float(np.median(tops)) if tops else default

    def bump_ceiling(self, x: float, z: float, y: float) -> None:
        """The drone's top hit something at height y while climbing, mark the block there as ceiling"""
        for dx in (-0.3, 0.3):
            for dz in (-0.3, 0.3):
                cell = (math.floor(x + dx), math.floor(z + dz))
                self.ceilings[cell] = min(self.ceilings.get(cell, math.inf), float(math.floor(y + 0.05)))

    def landmarks(self, name: str) -> list[tuple[int, int, int]]:
        """One block per column for a landmark kind, the height that collected the most hits"""
        votes = self.votes[name]
        best: dict[tuple[int, int], tuple[int, tuple[int, int, int]]] = {}
        for cell, n in votes.items():
            key = (cell[0], cell[2])
            if key not in best or n > best[key][0]:
                best[key] = (n, cell)
        # drop a cell when a neighbor got far more hits, edge noise leaves faint copies next to the real block
        kept = []
        for n, cell in best.values():
            strongest = max(
                (votes.get((cell[0] + dx, cell[1] + dy, cell[2] + dz), 0) for dx in (-1, 0, 1) for dy in (-1, 0, 1) for dz in (-1, 0, 1)),
                default=0,
            )
            if n >= 0.3 * strongest:
                kept.append(cell)
        return kept

    def forget(self, cell: tuple[int, int, int]) -> None:
        """A mined block is gone, its column drops back to whatever is under it once seen again"""
        # neighbors go too, they hold the edge-noise copies of the same block
        for votes in self.votes.values():
            for key in [k for k in votes if max(abs(k[i] - cell[i]) for i in range(3)) <= 1]:
                del votes[key]
        if self.heights.get((cell[0], cell[2]), -math.inf) >= cell[1] + 1:
            self.heights[(cell[0], cell[2])] = cell[1]
        if self.ceilings.get((cell[0], cell[2])) == cell[1]:
            del self.ceilings[(cell[0], cell[2])]

    def update(self, state: dict, depth: np.ndarray, mask: np.ndarray, depth_max: float, pixels: np.ndarray | None = None) -> None:
        """pixels is any per-pixel value to keep with each hit in last_hits, such as the reader's ripe probability"""
        ids_full = mask.astype(np.int64)
        view = backproject(state, depth, depth_max, valid=ids_full != 0)
        eye, inside, interior = view.eye, view.inside, view.interior
        ids = ids_full[np.ix_(view.rows, view.cols)][view.hit]
        # the block, mask id, and per-pixel value of every sampled hit
        self.last_hits = (inside, ids, pixels[np.ix_(view.rows, view.cols)][view.hit] if pixels is not None else None)

        overhead = inside[:, 1] + 0.5 > eye[1]
        floor = inside[~overhead]
        columns, first = np.unique(floor[:, [0, 2]], axis=0, return_inverse=True)
        tops = np.full(len(columns), -np.inf)
        np.maximum.at(tops, first.ravel(), floor[:, 1] + 1.0)
        for (x, z), top in zip(columns, tops):
            cell = (int(x), int(z))
            self.heights[cell] = max(self.heights.get(cell, -math.inf), float(top))
        roof = inside[overhead]
        columns, first = np.unique(roof[:, [0, 2]], axis=0, return_inverse=True)
        bottoms = np.full(len(columns), np.inf)
        np.minimum.at(bottoms, first.ravel(), roof[:, 1].astype(np.float64))
        for (x, z), bottom in zip(columns, bottoms):
            cell = (int(x), int(z))
            self.ceilings[cell] = min(self.ceilings.get(cell, math.inf), float(bottom))
        # a ray that crossed a block on its way to a surface saw it empty, strict kinds lose votes there so a block
        # that went away (a harvested crop) stops being remembered once the drone looks again
        # a short block (a young crop) can hold both ends of the step, it was hit, not crossed
        moved = interior & np.any(view.outside != inside, axis=1)
        crossed, crossed_n = np.unique(view.outside[moved], axis=0, return_counts=True)
        for (x, y, z), n in zip(crossed, crossed_n):
            key = (int(x), int(y), int(z))
            self.air[key] = self.air.get(key, 0) + int(n)
        if self.strict:
            for name in self.strict:
                votes = self.votes[name]
                for (x, y, z), n in zip(crossed, crossed_n):
                    key = (int(x), int(y), int(z))
                    if key in votes:
                        votes[key] -= int(n)
                        if votes[key] <= 0:
                            del votes[key]
        for name, block_id in self.landmark_ids.items():
            if block_id is None:
                continue
            chosen = (ids == block_id) & interior if name in self.strict else ids == block_id
            cells, counts = np.unique(inside[chosen], axis=0, return_counts=True)
            votes = self.votes[name]
            for (x, y, z), n in zip(cells, counts):
                key = (int(x), int(y), int(z))
                votes[key] = votes.get(key, 0) + int(n)


@dataclass
class View:
    """Sampled pixels of one frame back-projected into the world"""

    eye: np.ndarray
    rows: np.ndarray
    cols: np.ndarray
    hit: np.ndarray  # (len(rows), len(cols)) bool, the sampled pixels that hit something
    inside: np.ndarray  # (N, 3) int, the block each hit landed in
    interior: np.ndarray  # (N,) bool, hits at least 0.15 from both edges of the face they landed on
    outside: np.ndarray  # (N, 3) int, the air block each ray crossed just before its hit


def backproject(state: dict, depth: np.ndarray, depth_max: float, valid: np.ndarray | None = None, stride: int = PIXEL_STRIDE) -> View:
    """Every stride-th pixel with depth under depth_max (and valid, when given) as the block it shows"""
    cam = state["camera"]
    pos = state["pos"]
    eye = np.array([pos[0], pos[1] + cam["eyeHeight"], pos[2]])
    yaw = math.radians(state["yaw"])
    pitch = math.radians(state.get("pitch", 0.0))
    forward = np.array([-math.sin(yaw) * math.cos(pitch), -math.sin(pitch), math.cos(yaw) * math.cos(pitch)])
    right = np.array([-math.cos(yaw), 0.0, -math.sin(yaw)])
    up = np.cross(right, forward)

    h, w = depth.shape
    out_aspect = w / h
    tan_y = math.tan(math.radians(cam["fov"]) / 2)
    tan_x = tan_y * min(cam["windowAspect"], out_aspect)
    tan_ye = tan_x / out_aspect
    rows = np.arange(0, h, stride)
    cols = np.arange(0, w, stride)
    ny = 1 - (rows + 0.5) / h * 2
    nx = (cols + 0.5) / w * 2 - 1
    d = depth[np.ix_(rows, cols)]
    hit = d < depth_max - 0.01
    if valid is not None:
        hit &= valid[np.ix_(rows, cols)]
    dirs = forward[None, None, :] + right[None, None, :] * (nx[None, :, None] * tan_x) + up[None, None, :] * (ny[:, None, None] * tan_ye)
    points = eye[None, None, :] + dirs * d[..., None]
    unit = dirs / np.linalg.norm(dirs, axis=-1, keepdims=True)
    # step into the surface so a hit on a face lands in the block that owns it, far enough to absorb
    # reconstruction error near block edges
    inside = np.floor(points + unit * 0.15).astype(np.int64)[hit]
    outside = np.floor(points - unit * 0.15).astype(np.int64)[hit]
    local = points[hit] - inside
    edge_dist = np.sort(np.minimum(np.abs(local), np.abs(1 - local)), axis=1)
    return View(eye, rows, cols, hit, inside, edge_dist[:, 1] > 0.15, outside)
