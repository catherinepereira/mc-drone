"""
Planners for the player's jobs. Each one sees through the block reader into a VoxelMemory and picks the next goal from
it: a view to look at part of a box, a block to break, or a cell to place a block into. The scripted flight under
work_on and view carries the goals out, or the learned cell skill does when one is attached
"""

from __future__ import annotations

import math
from collections import Counter
from pathlib import Path

import numpy as np

from ..perception.memory import AIR, MIN_VOTES, Cell, VoxelMemory
from ..perception.reader import CLASSES, INDEX, OTHER, SKY
from .tools import (
    SURVEY_LIMIT, TOP_LEAN, TOP_VIA_HEIGHT, VIA_DIST, HarvestExpert, HonestExpert, center,
    tool_action,
)

VIEW_SPACING = 4.0
VIEW_DIST = 4.0
TOP_HEIGHT = 4.0
GAP_VIEW_DIST = 2.2
# a viewpoint is where the camera goes, the drone's feet are about this far below it
VIA_EYE = 0.2
CARRIED_SHARE = 0.25
# a scanned cell counts as this many reads, far more than frames add
SCAN_VOTES = 1000.0
# the age a crop is ripe at, where it isn't 7
RIPE_AGE = {"minecraft:beetroots": 3}
# a mining job digs a block if at least this share of its reads said the job's block, even when most said something else
DOUBT_SHARE = 0.15
# trenches two wide every four blocks leave two-wide walls, so every block of a dug box has a face in a trench.
# Two wide because the drone needs more than a block of room to fly down one
TRENCH_WIDTH = 2
MAX_STRIKES = 3
TRENCH_PERIOD = 4
# the camera this far above a block it breaks from above, or this far out from a side it breaks through
DIG_HEIGHT = 2.2
DIG_SIDE = 1.8
# the face of the support block that faces the cell, by the direction from the cell to the support
FACES = {(0, -1, 0): "up", (1, 0, 0): "west", (-1, 0, 0): "east", (0, 0, 1): "north", (0, 0, -1): "south", (0, 1, 0): "down"}


def neighbors(cell) -> list[tuple[int, int, int]]:
    x, y, z = cell
    return [(x + 1, y, z), (x - 1, y, z), (x, y + 1, z), (x, y - 1, z), (x, y, z + 1), (x, y, z - 1)]


def base_name(label: str) -> str:
    """A memory label or block state as a block name: no ripeness, no properties"""
    return label.split(" ")[0].split("[")[0]


def block_age(block: str) -> int | None:
    """The age property of a block state such as minecraft:wheat[age=7], None without one"""
    for prop in block.partition("[")[2].rstrip("]").split(","):
        key, _, value = prop.partition("=")
        if key == "age":
            return int(value)
    return None


def read_scans(job: dict, schematics: Path | None) -> dict[str, dict[tuple[int, int, int], str]]:
    """Block states of each box the server scanned for the job, by box name and then by cell"""
    if not job.get("scan"):
        return {}
    from mcdrone.schematic import load

    if schematics is None:
        raise ValueError("a scanned job needs the schematics folder, pass --schematics")
    out = {}
    for key, rel in job["scan"].items():
        schematic = load(schematics / rel)
        x0, y0, z0 = job[key][:3]
        w, h, l = schematic.size
        out[key] = {(x0 + x, y0 + y, z0 + z): schematic.get(x, y, z) for x in range(w) for y in range(h) for z in range(l)}
    return out


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

    def __init__(self, mask_ids: dict, depth_max: float = 64.0, reader=None, schematics: Path | None = None) -> None:
        if reader is None:
            raise ValueError("job planners read blocks with the block reader, pass one")
        super().__init__(mask_ids, depth_max, reader)
        self.schematics = schematics
        self.memory = VoxelMemory()
        # scan perception: exact block names of the job's boxes read from the world, and which boxes they cover
        self.scanned: dict[tuple[int, int, int], str] = {}
        self.scanned_boxes: set[str] | None = None
        self.views: list[tuple[tuple, tuple]] | None = None
        self.view_steps = 0
        self.dwell = 0
        self.dug: set[tuple[int, int, int]] = set()
        # the block being dug for its own sake (ore, or a material for a build) and what it was read as
        self.target_cell: tuple[int, int, int] | None = None
        self.target_block: str | None = None
        self.trenches: dict[tuple, list[tuple[int, int, int]]] = {}

    def load_scans(self, job: dict) -> None:
        """
        Loads the boxes the server scanned, once. Each cell goes into memory as a certain read, so everything that works
        from memory works the same, and its exact name into scanned
        """
        if self.scanned_boxes is not None:
            return
        self.scanned_boxes = set()
        for key, cells in read_scans(job, self.schematics).items():
            for cell, block in cells.items():
                name = base_name(block)
                self.scanned[cell] = name
                votes = self.memory.cells.setdefault(cell, Cell()).votes
                votes[:] = 0
                votes[SKY if name == AIR else INDEX.get(name, OTHER)] = SCAN_VOTES
            self.scanned_boxes.add(key)

    def scanned_in(self, box, wanted) -> list[tuple[int, int, int]]:
        """Scanned cells in box holding one of the wanted blocks and not dug out since"""
        lo, hi = box[:3], box[3:]
        return [
            c for c, name in self.scanned.items()
            if name in wanted and c not in self.dug and c not in self.unreachable and all(lo[i] <= c[i] <= hi[i] for i in range(3))
        ]

    def shaft_blocker(self, cell, box) -> tuple[int, int, int] | None:
        """
        The next block to dig on a two-wide shaft from the top of box down to a buried cell: the cell's column and the
        one beside it, highest first, so the drone has room to follow it down
        """
        x0, x1, top = box[0], box[3], box[4]
        side = cell[0] + 1 if cell[0] + 1 <= x1 else cell[0] - 1
        for y in range(top, cell[1], -1):
            for x in (cell[0], side):
                c = (x, y, cell[2])
                if x0 <= x <= x1 and not self.empty(c):
                    return c
        side_cell = (side, cell[1], cell[2])
        return side_cell if x0 <= side <= x1 and not self.empty(side_cell) else None

    def start_survey(self, state: dict, box) -> None:
        pos = state["pos"]
        todo = [v for v in box_views(box) if self.free_at(v[0])]
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
            self.dwell_on(state, look)
        return self.view(state, move, via, look)

    def believed(self, cell) -> str:
        return base_name(self.memory.label(cell))

    def idle(self) -> dict:
        return tool_action([0, 0, 0, 0, 0])

    def note_breaks(self, state: dict) -> None:
        for event in state.get("events", []):
            if event.get("type") == "break":
                self.dug.add(tuple(event["pos"]))

    def unseen(self, cell) -> bool:
        c = self.memory.cells.get(cell)
        return (c is None or c.votes.sum() < MIN_VOTES) and self.map.air.get(cell, 0) < 3

    def empty(self, cell) -> bool:
        return cell in self.dug or (self.believed(cell) == AIR and not self.unseen(cell))

    def next_dig(self, state: dict, box) -> tuple[int, int, int] | None:
        """The next trench cell to dig in box, the highest layer first and the nearest within it"""
        key = tuple(box)
        if key not in self.trenches:
            x0, y0, z0, x1, y1, z1 = box
            zs = [z for z in range(z0, z1 + 1) if (z - z0) % TRENCH_PERIOD < TRENCH_WIDTH]
            self.trenches[key] = [(x, y, z) for y in range(y1, y0 - 1, -1) for z in zs for x in range(x0, x1 + 1)]
        left = [c for c in self.trenches[key] if not self.empty(c) and c not in self.unreachable]
        if not left:
            return None
        top = max(c[1] for c in left)
        return self.stick(state, [c for c in left if c[1] == top])

    def committed(self, state: dict, targets) -> tuple[int, int, int] | None:
        """
        The target being dug until it is broken or given up on, then the next one from targets.
        A buried block's read can flicker between frames, this keeps the drone on it
        """
        if self.target_cell is not None and (self.target_cell in self.dug or self.target_cell in self.unreachable):
            self.target_cell = None
        if self.target_cell is None and targets:
            self.target_cell = self.stick(state, targets)
            self.target_block = base_name(self.memory.label(self.target_cell))
        return self.target_cell

    def stick(self, state: dict, cells) -> tuple[int, int, int] | None:
        """The cell already being worked on if it is still a choice, else the nearest, so moving doesn't flip the target"""
        return self.working_on if self.working_on in cells else self.nearest(state, cells)

    def dig(self, state: dict, cell, expect: str | None, box=None) -> dict:
        """
        Break cell through a face that is open, with the camera in front of it.
        A cell with no open face inside box gets a shaft dug down to it first
        """
        c = center(cell)

        def open_side(n) -> bool:
            # above the top of the box is open air for digging, even before the camera has seen it
            return self.empty(n) or (box is not None and n[1] > box[4])

        if box is not None and not any(open_side(n) for n in neighbors(cell)):
            blocker = self.shaft_blocker(cell, box)
            if blocker is not None and blocker != cell:
                return self.dig(state, blocker, None, box)
        for d in ((0, 1, 0), (1, 0, 0), (-1, 0, 0), (0, 0, 1), (0, 0, -1)):
            if not open_side((cell[0] + d[0], cell[1] + d[1], cell[2] + d[2])):
                continue
            if d[1] == 1:
                # from above, leaning along x so a trench's walls stay out of the way
                lean = 0.6 if state["pos"][0] >= c[0] else -0.6
                via = (c[0] + lean, c[1] + DIG_HEIGHT, c[2])
            else:
                # only as far out as the open cells go, a two-wide trench has a wall right behind
                room = 1 + int(self.empty((cell[0] + 2 * d[0], cell[1], cell[2] + 2 * d[2])))
                out = min(DIG_SIDE, float(room))
                via = (c[0] + d[0] * out, c[1] + 0.3, c[2] + d[2] * out)
            point = (c[0] + d[0] * 0.49, c[1] + d[1] * 0.49, c[2] + d[2] * 0.49)
            return self.work_on(state, cell, "break", standoff=0.0, hover=0.0, point=point, expect=expect, via=via)
        return self.work_on(state, cell, "break", standoff=2.5, hover=0.9, expect=expect)

    def close_views(self, cells, box) -> list[tuple[tuple, tuple]]:
        """Up to two close views of each cell, from above or from sides that face open air"""
        lo, hi = box[:3], box[3:]
        views = []
        for cell in cells:
            sides = []
            c = center(cell)
            for d in ((0, 1, 0), (1, 0, 0), (-1, 0, 0), (0, 0, 1), (0, 0, -1)):
                n = (cell[0] + d[0], cell[1] + d[1], cell[2] + d[2])
                outside = not all(lo[i] <= n[i] <= hi[i] for i in range(3))
                if not outside and self.believed(n) != AIR:
                    continue
                if d[1] == 1:
                    via = (c[0] + 0.8, c[1] + GAP_VIEW_DIST, c[2])
                else:
                    via = (c[0] + d[0] * GAP_VIEW_DIST, c[1] + 0.2, c[2] + d[2] * GAP_VIEW_DIST)
                if self.free_at(via):
                    sides.append((via, c))
            views += sides[:2]
        return views

    def free_at(self, p) -> bool:
        """Whether the planner can fly to p, judged at p's own height, not wherever the drone is now"""
        saved = self.map.fly_y
        self.map.fly_y = p[1] - VIA_EYE
        try:
            return self.map.free(p[0], p[2])
        finally:
            self.map.fly_y = saved

    def share(self, cell, block: str) -> float:
        """The share of a cell's votes for block"""
        c = self.memory.cells.get(cell)
        total = 0.0 if c is None else float(c.votes.sum())
        return 0.0 if total == 0 else float(c.votes[INDEX[block]]) / total

    @staticmethod
    def region_cells(box) -> list[tuple[int, int, int]]:
        x0, y0, z0, x1, y1, z1 = box
        return [(x, y, z) for x in range(x0, x1 + 1) for y in range(y0, y1 + 1) for z in range(z0, z1 + 1)]


class BuildPlanner(JobPlanner):
    """
    Copy and build jobs. A copy reads its source box into memory and takes that read as the plan, a build loads the
    schematic it names. Then it clears what doesn't belong in the destination and places the plan bottom up,
    each block against a face of a block already there
    """

    def __init__(self, mask_ids: dict, depth_max: float = 64.0, reader=None, schematics: Path | None = None) -> None:
        super().__init__(mask_ids, depth_max, reader, schematics)
        self.plan: dict[tuple[int, int, int], str] | None = None
        self.read_schematic = None
        self.site_seen = False
        self.placed: dict[tuple[int, int, int], str] = {}
        self.target: tuple[int, int, int] | None = None
        self.skipped: set[tuple[int, int, int]] = set()
        self.gaps_checked = False
        self.gather_seen = False
        self.out_of: set[str] = set()
        # source cells being looked at again, the blocks already rechecked, and the source to destination offset
        self.recheck: list[tuple[int, int, int]] = []
        self.rechecked: set[str] = set()
        self.offset = (0, 0, 0)
        # how many times each support was given up on, it gets another try after each block placed, up to MAX_STRIKES
        self.strikes: Counter = Counter()

    def decide(self, state: dict) -> dict:
        job = state["job"]
        self.note_breaks(state)
        for event in state.get("events", []):
            kind = event.get("type")
            if kind == "place":
                self.placed[tuple(event["pos"])] = base_name(event["block"])
                self.retry_given_up()
            elif kind == "break":
                self.placed.pop(tuple(event["pos"]), None)
            elif kind == "place_failed" and self.target is not None:
                reason = event.get("reason", "")
                if reason.startswith("out of ") and "gather" in job:
                    # gathering resupplies it
                    pass
                elif reason.startswith("out of "):
                    block = reason.removeprefix("out of ")
                    # every cell wanting that block would fail the same way. The first time, the read is the likelier
                    # mistake, so look at those source cells again up close
                    if block not in self.rechecked and job["kind"] == "copy" and "source" not in self.scanned_boxes:
                        self.start_recheck(block)
                    else:
                        self.out_of.add(block)
                elif "in the way" not in reason:
                    # the server refused the cell, a safe region or outside the job
                    self.skipped.add(self.target)
        self.load_scans(job)
        if self.plan is None:
            if job["kind"] == "build":
                self.plan = self.load_plan(job)
            elif "source" in self.scanned_boxes:
                self.plan = self.scanned_plan(job)
            else:
                if self.views is None:
                    self.start_survey(state, job["source"])
                if self.views:
                    return self.survey(state)
                if not self.gaps_checked:
                    # cells under read blocks that no view reached get a close look through an open side
                    self.gaps_checked = True
                    self.views = self.gap_views(job["source"])
                    if self.views:
                        return self.survey(state)
                self.plan = self.read_plan(job, state)
                self.views = None
        if self.recheck:
            if self.views:
                return self.survey(state)
            self.finish_recheck()
        if not self.site_seen:
            if self.views is None:
                self.start_survey(state, job["dest"])
            if self.views:
                return self.survey(state)
            self.site_seen = True
        return self.build(state)

    def gaps(self, box) -> list[tuple[int, int, int]]:
        """Unseen cells right under a block read in the box"""
        lo, hi = box[:3], box[3:]
        read = self.memory.box(lo, hi)
        return sorted({(x, y - 1, z) for (x, y, z) in read if y - 1 >= lo[1] and (x, y - 1, z) not in read and self.unseen((x, y - 1, z))})

    def gap_views(self, box) -> list[tuple[tuple, tuple]]:
        return self.close_views(self.gaps(box), box)

    def start_recheck(self, block: str) -> None:
        """Forget what was read for the source cells planned as block, then look at each one up close"""
        self.rechecked.add(block)
        dx, dy, dz = self.offset
        cells = [(x - dx, y - dy, z - dz) for (x, y, z), b in self.plan.items() if b == block and not self.done((x, y, z))]
        # only cells with a close view start over, the rest keep what was read
        self.recheck = [c for c in cells if self.close_views([c], self.source)]
        for cell in self.recheck:
            if cell in self.memory.cells:
                self.memory.cells[cell].votes[:] = 0
        self.views = self.close_views(self.recheck, self.source)
        if not self.recheck:
            self.out_of.add(block)

    def finish_recheck(self) -> None:
        dx, dy, dz = self.offset
        for (x, y, z) in self.recheck:
            label = self.memory.label((x, y, z))
            dest = (x + dx, y + dy, z + dz)
            if label == AIR or label.startswith("<"):
                self.plan.pop(dest, None)
            else:
                self.plan[dest] = base_name(label)
        self.recheck = []
        self.views = None

    def read_plan(self, job: dict, state: dict) -> dict:
        """
        The source box as memory has it, moved onto the destination.
        A block the drone doesn't carry loses to a carried one with a fair share of the cell's votes, the materials it was
        given are evidence of what the build is made of.
        A cell no view reached under a read block takes the block above it, columns with a hole underneath are rare
        """
        carried = {name for name, count in state.get("inventory", []) if count > 0} if "gather" not in job else set()
        sx, sy, sz = job["source"][:3]
        lo, hi = job["source"][:3], job["source"][3:]
        dx, dy, dz = (d - s for d, s in zip(job["dest"][:3], (sx, sy, sz)))
        self.offset = (dx, dy, dz)
        self.source = job["source"]
        read = {}
        for c, label in self.memory.box(lo, hi).items():
            if label.startswith("<"):
                continue
            name = base_name(label)
            read[c] = (self.carried_alternative(c, carried) or name) if carried and name not in carried else name
        for cell in sorted(self.gaps(job["source"]), key=lambda c: -c[1]):
            for y in range(cell[1], lo[1] - 1, -1):
                below = (cell[0], y, cell[2])
                if below in read or not self.unseen(below):
                    break
                read[below] = read[(cell[0], y + 1, cell[2])]
        self.read_schematic = self.memory.schematic(lo, hi)
        return {(x + dx, y + dy, z + dz): name for (x, y, z), name in read.items()}

    def carried_alternative(self, cell, carried: set[str]) -> str | None:
        """The carried block with the most votes in cell, if it has at least CARRIED_SHARE of the leader's"""
        votes = self.memory.cells[cell].votes
        options = [i for i, name in enumerate(CLASSES) if name in carried]
        best = max(options, key=lambda i: votes[i], default=None)
        if best is not None and votes[best] > 0 and votes[best] >= CARRIED_SHARE * votes.max():
            return CLASSES[best]
        return None

    def scanned_plan(self, job: dict) -> dict:
        """The scanned source moved onto the destination, any block, exact names"""
        sx, sy, sz = job["source"][:3]
        dx, dy, dz = (d - s for d, s in zip(job["dest"][:3], (sx, sy, sz)))
        self.offset = (dx, dy, dz)
        self.source = job["source"]
        return {
            (x + dx, y + dy, z + dz): name
            for (x, y, z), name in self.scanned.items()
            if name != AIR and all(job["source"][i] <= (x, y, z)[i] <= job["source"][i + 3] for i in range(3))
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

    def retry_given_up(self) -> None:
        """A newly placed block changes what the drone can reach, so supports given up on get another try"""
        for cell in self.unreachable:
            self.strikes[cell] += 1
        self.unreachable = {c for c in self.unreachable if self.strikes[c] >= MAX_STRIKES}

    def open_spot(self, p) -> bool:
        """Room for the camera at p: its block is empty and the planner can fly there"""
        cell = (math.floor(p[0]), math.floor(p[1]), math.floor(p[2]))
        return not self.solid(cell) and not self.solid((cell[0], cell[1] - 1, cell[2])) and self.free_at(p)

    def placement(self, state: dict, cell) -> tuple[tuple[int, int, int], str, tuple] | None:
        """A support for cell, the face to aim at, and a viewpoint with room for the camera"""
        c = center(cell)
        for d, face in FACES.items():
            n = (cell[0] + d[0], cell[1] + d[1], cell[2] + d[2])
            if n in self.unreachable or not self.solid(n):
                continue
            if face != "up":
                via = tuple(np.subtract(c, np.asarray(d) * VIA_DIST))
                if self.open_spot(via):
                    return n, face, via
                continue
            # over a top face, leaning toward the drone first, then the other ways, higher if those are blocked
            toward = np.array([state["pos"][0] - c[0], state["pos"][2] - c[2]])
            toward = toward / max(np.linalg.norm(toward), 1e-6)
            leans = [toward, *(np.array(v, dtype=float) for v in ((1, 0), (-1, 0), (0, 1), (0, -1)))]
            for height in (TOP_VIA_HEIGHT, TOP_VIA_HEIGHT + 1.5):
                for lean in leans:
                    via = (c[0] + lean[0] * TOP_LEAN, c[1] + height, c[2] + lean[1] * TOP_LEAN)
                    if self.open_spot(via):
                        return n, face, via
        return None

    def build(self, state: dict) -> dict:
        lo, hi = self.job_box(state, "dest")
        for cell, label in sorted(self.memory.box(lo, hi).items(), key=lambda kv: -kv[0][1]):
            # a scanned name is exact, the memory only has the reader's classes
            name = self.scanned[cell] if cell in self.scanned and cell not in self.dug and cell not in self.placed else base_name(label)
            want = self.plan.get(cell)
            if cell in self.unreachable or (cell in self.placed and self.placed[cell] == want) or name == want:
                continue
            # top down, so nothing is left standing on a block about to go
            self.target = cell
            return self.work_on(state, cell, "break", standoff=2.5, hover=0.9, expect=name)
        todo = [c for c in self.plan if not self.done(c) and c not in self.skipped and self.plan[c] not in self.out_of]
        if "gather" in state["job"]:
            action = self.gather(state, todo)
            if action is not None:
                return action
        pos = state["pos"]
        # the lowest layer first and the nearest cell in it, checking viewpoints only until one has room
        for cell in sorted(todo, key=lambda c: (c[1], math.dist(center(c), pos))):
            found = self.placement(state, cell)
            if found is None:
                continue
            support, face, via = found
            self.target = cell
            d = np.subtract(support, cell)
            point = tuple(np.add(center(support), -d * 0.49))
            return self.work_on(state, support, "place", standoff=0.0, hover=0.0, point=point, face=face, place=self.plan[cell], via=via)
        return self.idle()

    def gather(self, state: dict, todo) -> dict | None:
        """
        Mine the gather box for blocks the rest of the build needs and the drone doesn't carry: ones it can see first,
        then trenches to uncover more. None when nothing is missing or the box has nothing left to give
        """
        have = Counter()
        for name, count in state.get("inventory", []):
            have[name] += count
        need = Counter(self.plan[c] for c in todo) - have
        if not need:
            return None
        box = state["job"]["gather"]
        lo, hi = box[:3], box[3:]
        if "gather" in self.scanned_boxes:
            cell = self.committed(state, self.scanned_in(box, need))
            return self.dig(state, cell, self.target_block, box) if cell is not None else None
        if not self.gather_seen:
            if self.views is None:
                self.start_survey(state, box)
            if self.views:
                return self.survey(state)
            self.gather_seen = True
            self.views = None
        visible = [c for c, label in self.memory.box(lo, hi).items() if base_name(label) in need and c not in self.unreachable]
        cell = self.committed(state, visible)
        if cell is not None:
            return self.dig(state, cell, self.target_block)
        cell = self.next_dig(state, box)
        if cell is not None:
            # every trench block comes out whatever it is, a misread mustn't make the drone give up on it
            return self.dig(state, cell, None)
        return None

    @staticmethod
    def job_box(state: dict, key: str) -> tuple[tuple, tuple]:
        box = state["job"][key]
        return tuple(box[:3]), tuple(box[3:])


class MinePlanner(JobPlanner):
    """
    Mine jobs: looks the region over and breaks every block of the kind it can see there. With none in sight it digs
    trenches through the region, which uncovers buried ones, and breaks those as they come into view
    """

    def __init__(self, mask_ids: dict, depth_max: float = 64.0, reader=None, schematics: Path | None = None) -> None:
        super().__init__(mask_ids, depth_max, reader, schematics)
        self.empty_surveys = 0
        self.walls_checked = False

    def decide(self, state: dict) -> dict:
        job = state["job"]
        self.note_breaks(state)
        self.load_scans(job)
        if "region" in self.scanned_boxes:
            # the scan knows every block of the kind, buried or not, any block the game has
            cell = self.committed(state, self.scanned_in(job["region"], {job["block"]}))
            return self.dig(state, cell, job["block"], job["region"]) if cell is not None else self.idle()
        if job["block"] not in INDEX:
            raise ValueError(f"the block reader doesn't know {job['block']}, it can't find it to mine")
        if self.views is None:
            self.start_survey(state, job["region"])
        if self.views:
            return self.survey(state)
        lo, hi = job["region"][:3], job["region"][3:]
        targets = [c for c, label in self.memory.box(lo, hi).items() if base_name(label) == job["block"] and c not in self.unreachable]
        cell = self.committed(state, targets)
        if cell is not None:
            self.empty_surveys = 0
            return self.dig(state, cell, job["block"])
        cell = self.next_dig(state, job["region"])
        if cell is not None:
            # every trench block comes out whatever it is, a misread mustn't make the drone give up on it
            return self.dig(state, cell, None)
        if not self.walls_checked:
            # the views from outside barely see into the trenches, look at each wall block not yet seen well up close
            self.walls_checked = True
            box = job["region"]
            walls = [
                c for c in self.region_cells(box)
                if not self.empty(c) and self.unseen(c) and any(self.empty(n) for n in neighbors(c))
            ]
            self.views = self.close_views(walls, box)
            if self.views:
                return self.survey(state)
        # the reader can take buried ore for stone, dig what had a fair share of votes for the block before giving up
        doubtful = [c for c in self.region_cells(job["region"]) if not self.empty(c) and c not in self.unreachable and self.share(c, job["block"]) >= DOUBT_SHARE]
        if doubtful:
            return self.dig(state, self.stick(state, doubtful), None)
        if self.empty_surveys >= 2:
            return self.idle()
        self.empty_surveys += 1
        self.views = None
        return self.idle()


class HarvestPlanner(HarvestExpert):
    """
    The harvest expert with a voxel memory, so its reads show on the dashboard.
    With a scanned field it skips the survey, takes ripeness from each crop's age, and plants the plots the scan found bare
    without looking at them first
    """

    def __init__(self, mask_ids: dict, depth_max: float = 64.0, reader=None, schematics: Path | None = None) -> None:
        super().__init__(mask_ids, depth_max, reader)
        self.memory = VoxelMemory()
        self.schematics = schematics
        # block states of the field by cell, empty in vision perception
        self.field_scan: dict[tuple[int, int, int], str] | None = None

    def decide(self, state: dict) -> dict:
        if self.field_scan is None:
            self.field_scan = read_scans(state["job"], self.schematics).get("region", {})
            if self.field_scan:
                self.views = []
        return super().decide(state)

    def ripe_cells(self, state: dict) -> list[tuple[int, int, int]]:
        if not self.field_scan:
            return super().ripe_cells(state)
        crop = state["job"]["crop"]
        ripe_age = RIPE_AGE.get(crop, 7)
        done = self.broken | self.planted | self.refused | self.unreachable
        return [c for c, block in self.field_scan.items() if base_name(block) == crop and block_age(block) == ripe_age and c not in done]

    def sweep_plots(self, state: dict) -> list[tuple[int, int, int]]:
        if not self.field_scan:
            return super().sweep_plots(state)
        bare = [
            c for c, block in self.field_scan.items()
            if base_name(block) == "minecraft:farmland" and self.field_scan.get((c[0], c[1] + 1, c[2]), AIR) == AIR
        ]
        # and plots harvested with no seed left to replant them
        bare += [(c[0], c[1] - 1, c[2]) for c in self.broken]
        return sorted(set(bare), key=lambda c: (c[0], c[2] if c[0] % 2 == 0 else -c[2]))

    def check_plot(self, state: dict, seed: str) -> dict:
        if not self.field_scan:
            return super().check_plot(state, seed)
        self.sweep = [p for p in self.sweep if (p[0], p[1] + 1, p[2]) not in self.planted and p not in self.unreachable]
        return self.plant(state, self.sweep[0], seed) if self.sweep else tool_action([0, 0, 0, 0, 0])

    def survey(self, state: dict) -> dict:
        if not self.field_scan:
            return super().survey(state)
        self.views = []
        return tool_action([0, 0, 0, 0, 0])


def make_planner(job: dict, mask_ids: dict, reader, schematics: Path | None = None) -> HonestExpert:
    if job["kind"] in ("copy", "build"):
        return BuildPlanner(mask_ids, reader=reader, schematics=schematics)
    if job["kind"] == "mine":
        return MinePlanner(mask_ids, reader=reader, schematics=schematics)
    return HarvestPlanner(mask_ids, reader=reader, schematics=schematics)
