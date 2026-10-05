"""
Planners for the player's jobs. Each one sees through the block reader into a VoxelMemory and picks the next goal from
it: a view to look at part of a box, a block to break, or a cell to place a block into. The scripted flight under
work_on and view carries the goals out, or the learned cell skill does when one is attached
"""

from __future__ import annotations

import math
from pathlib import Path

import numpy as np

from .memory import AIR, VoxelMemory
from .reader import INDEX
from .tool_experts import (
    AIM_TOLERANCE, SURVEY_DWELL, SURVEY_LIMIT, HarvestExpert, HonestExpert, aim_errors, center, place_viewpoint, tool_action,
)

VIEW_SPACING = 4.0
VIEW_DIST = 4.0
TOP_HEIGHT = 4.0
# the face of the support block that faces the cell, by the direction from the cell to the support
FACES = {(0, -1, 0): "up", (1, 0, 0): "west", (-1, 0, 0): "east", (0, 0, 1): "north", (0, 0, -1): "south", (0, 1, 0): "down"}


def base_name(label: str) -> str:
    """A memory label or block state as a block name: no ripeness, no properties"""
    return label.split(" ")[0].split("[")[0]


def spaced(lo: int, hi: int) -> list[float]:
    """Points spread at most VIEW_SPACING apart along the inclusive block range lo..hi"""
    width = hi - lo + 1
    n = max(1, math.ceil(width / VIEW_SPACING))
    return [lo + width * (i + 0.5) / n for i in range(n)]


def box_views(box) -> list[tuple[tuple, tuple]]:
    """Viewpoints and aim points that cover a box's four sides and its top"""
    x0, y0, z0, x1, y1, z1 = box
    cx, cz = (x0 + x1 + 1) / 2, (z0 + z1 + 1) / 2
    heights = list(np.arange(y0 + 1.6, y1 + 1.0, VIEW_SPACING)) or [y0 + 1.6]
    views = []
    for h in heights:
        look_y = min(max(h, y0 + 0.5), y1 + 0.5)
        for x in spaced(x0, x1):
            views.append(((x, h, z1 + 1 + VIEW_DIST), (x, look_y, cz)))
            views.append(((x, h, z0 - VIEW_DIST), (x, look_y, cz)))
        for z in spaced(z0, z1):
            views.append(((x1 + 1 + VIEW_DIST, h, z), (cx, look_y, z)))
            views.append(((x0 - VIEW_DIST, h, z), (cx, look_y, z)))
    for x in spaced(x0, x1):
        for z in spaced(z0, z1):
            views.append(((x, y1 + 1 + TOP_HEIGHT, z - 1.5), (x, y1 + 0.5, z)))
    return views


class JobPlanner(HonestExpert):
    """Surveys boxes view by view, nearest view first, and keeps what the reader makes of them in memory"""

    def __init__(self, mask_ids: dict, depth_max: float = 64.0, reader=None) -> None:
        if reader is None:
            raise ValueError("job planners read blocks with the block reader, pass one")
        super().__init__(mask_ids, depth_max, reader)
        self.memory = VoxelMemory()
        self.views: list[tuple[tuple, tuple]] | None = None
        self.view_steps = 0
        self.dwell = 0

    def start_survey(self, state: dict, box) -> None:
        pos = state["pos"]
        todo = [v for v in box_views(box) if self.map.free(v[0][0], v[0][2])]
        ordered = []
        here = (pos[0], pos[2])
        while todo:
            nxt = min(todo, key=lambda v: math.dist(here, (v[0][0], v[0][2])))
            todo.remove(nxt)
            ordered.append(nxt)
            here = (nxt[0][0], nxt[0][2])
        self.views = ordered

    def survey(self, state: dict) -> dict:
        """Fly to the next view and hold the aim there for a few frames"""
        via, look = self.views[0]
        self.view_steps += 1
        move, arrived = self.fly_to_view(state, via, look)
        if arrived or self.view_steps > SURVEY_LIMIT:
            yaw_error, pitch_error, _ = aim_errors(state, look)
            if abs(yaw_error) < AIM_TOLERANCE and abs(pitch_error) < AIM_TOLERANCE or self.view_steps > SURVEY_LIMIT + 20:
                self.dwell += 1
            if self.dwell >= SURVEY_DWELL:
                self.views.pop(0)
                self.view_steps = 0
                self.dwell = 0
        return self.view(state, move, via, look)

    def believed(self, cell) -> str:
        return base_name(self.memory.label(cell))

    def idle(self) -> dict:
        return tool_action([0, 0, 0, 0, 0])


class BuildPlanner(JobPlanner):
    """
    Copy and build jobs. A copy reads its source box into memory and takes that read as the plan, a build loads the
    schematic it names. Then it clears what doesn't belong in the destination and places the plan bottom up,
    each block against a face of a block already there
    """

    def __init__(self, mask_ids: dict, depth_max: float = 64.0, reader=None, schematics: Path | None = None) -> None:
        super().__init__(mask_ids, depth_max, reader)
        self.schematics = schematics
        self.plan: dict[tuple[int, int, int], str] | None = None
        self.read_schematic = None
        self.site_seen = False
        self.placed: dict[tuple[int, int, int], str] = {}
        self.target: tuple[int, int, int] | None = None
        self.skipped: set[tuple[int, int, int]] = set()

    def decide(self, state: dict) -> dict:
        job = state["job"]
        for event in state.get("events", []):
            kind = event.get("type")
            if kind == "place":
                self.placed[tuple(event["pos"])] = base_name(event["block"])
            elif kind == "break":
                self.placed.pop(tuple(event["pos"]), None)
            elif kind == "place_failed" and self.target is not None:
                # out of that block in inventory mode, or the server refused the cell
                self.skipped.add(self.target)
        if self.plan is None:
            if job["kind"] == "build":
                self.plan = self.load_plan(job)
            else:
                if self.views is None:
                    self.start_survey(state, job["source"])
                if self.views:
                    return self.survey(state)
                self.plan = self.read_plan(job)
                self.views = None
        if not self.site_seen:
            if self.views is None:
                self.start_survey(state, job["dest"])
            if self.views:
                return self.survey(state)
            self.site_seen = True
        return self.build(state)

    def read_plan(self, job: dict) -> dict:
        """The source box as memory has it, moved onto the destination"""
        sx, sy, sz = job["source"][:3]
        lo, hi = job["source"][:3], job["source"][3:]
        dx, dy, dz = (d - s for d, s in zip(job["dest"][:3], (sx, sy, sz)))
        self.read_schematic = self.memory.schematic(lo, hi)
        return {
            (x + dx, y + dy, z + dz): base_name(label)
            for (x, y, z), label in self.memory.box(lo, hi).items()
            if not label.startswith("<")
        }

    def load_plan(self, job: dict) -> dict:
        from mcdrone.schematic import load

        if self.schematics is None:
            raise ValueError(f"build job for {job['schematic']} needs the schematics folder, pass --schematics")
        schematic = load(self.schematics / job["schematic"])
        x0, y0, z0 = job["dest"][:3]
        w, h, l = schematic.size
        return {
            (x0 + x, y0 + y, z0 + z): base_name(schematic.get(x, y, z))
            for x in range(w) for y in range(h) for z in range(l)
            if base_name(schematic.get(x, y, z)) != AIR
        }

    def done(self, cell) -> bool:
        want = self.plan[cell]
        return self.placed.get(cell) == want or (cell not in self.placed and self.believed(cell) == want)

    def solid(self, cell) -> bool:
        return cell in self.placed or self.believed(cell) != AIR

    def support(self, cell) -> tuple[tuple[int, int, int], str] | None:
        for d, face in FACES.items():
            n = (cell[0] + d[0], cell[1] + d[1], cell[2] + d[2])
            if n not in self.unreachable and self.solid(n):
                return n, face
        return None

    def build(self, state: dict) -> dict:
        lo, hi = self.job_box(state, "dest")
        for cell, label in sorted(self.memory.box(lo, hi).items(), key=lambda kv: -kv[0][1]):
            name = base_name(label)
            want = self.plan.get(cell)
            if cell in self.unreachable or (cell in self.placed and self.placed[cell] == want) or name == want:
                continue
            # top down, so nothing is left standing on a block about to go
            self.target = cell
            return self.work_on(state, cell, "break", standoff=2.5, hover=0.9, expect=name)
        todo = [c for c in self.plan if not self.done(c) and c not in self.skipped]
        candidates = [(c, s) for c in todo if (s := self.support(c)) is not None]
        if not candidates:
            return self.idle()
        lowest = min(c[1] for c, _ in candidates)
        pos = state["pos"]
        cell, (support, face) = min(((c, s) for c, s in candidates if c[1] == lowest), key=lambda cs: math.dist(center(cs[0]), pos))
        self.target = cell
        d = np.subtract(support, cell)
        point = tuple(np.add(center(support), -d * 0.49))
        return self.work_on(state, support, "place", standoff=0.0, hover=0.0, point=point, face=face, place=self.plan[cell], via=place_viewpoint(state, cell, d))

    @staticmethod
    def job_box(state: dict, key: str) -> tuple[tuple, tuple]:
        box = state["job"][key]
        return tuple(box[:3]), tuple(box[3:])


class MinePlanner(JobPlanner):
    """Mine jobs: looks the region over, breaks every block of the kind it read there, and looks again for any the digging uncovered"""

    def __init__(self, mask_ids: dict, depth_max: float = 64.0, reader=None) -> None:
        super().__init__(mask_ids, depth_max, reader)
        self.empty_surveys = 0

    def decide(self, state: dict) -> dict:
        job = state["job"]
        if job["block"] not in INDEX:
            raise ValueError(f"the block reader doesn't know {job['block']}, it can't find it to mine")
        if self.views is None:
            self.start_survey(state, job["region"])
        if self.views:
            return self.survey(state)
        lo, hi = job["region"][:3], job["region"][3:]
        targets = [c for c, label in self.memory.box(lo, hi).items() if base_name(label) == job["block"] and c not in self.unreachable]
        if targets:
            self.empty_surveys = 0
            return self.work_on(state, self.nearest(state, targets), "break", standoff=2.5, hover=0.9, expect=job["block"])
        if self.empty_surveys >= 2:
            return self.idle()
        self.empty_surveys += 1
        self.views = None
        return self.idle()


class HarvestPlanner(HarvestExpert):
    """The harvest expert with a voxel memory, so its reads show on the dashboard"""

    def __init__(self, mask_ids: dict, depth_max: float = 64.0, reader=None) -> None:
        super().__init__(mask_ids, depth_max, reader)
        self.memory = VoxelMemory()


def make_planner(job: dict, mask_ids: dict, reader, schematics: Path | None = None) -> HonestExpert:
    if job["kind"] in ("copy", "build"):
        return BuildPlanner(mask_ids, reader=reader, schematics=schematics)
    return {"mine": MinePlanner, "harvest": HarvestPlanner}[job["kind"]](mask_ids, reader=reader)
