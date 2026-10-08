"""
The planner for patrol and guard jobs and their arenas. It sweeps the region's patrol cells row by row, round after
round. With hunt it breaks off for any hostile mob it sees inside the region, closes to beam range, and holds the
beam on it until it's down, then picks the sweep up again. In vision perception mobs come from the camera through
perception.mobs, in scan perception the job hands them over
"""

from __future__ import annotations

import math
from pathlib import Path

import numpy as np

from ..perception.mobs import MobTracker, hostile_ids
from .base import BEAM_RANGE, SEARCH_HEIGHT, HonestExpert, aim_errors, tool_action

# the drone counts a cell as passed this far inside its visit radius, the server's check is on the exact pose
VISIT_MARGIN = 0.5
# steps spent heading for one cell before it's skipped this round, a pillar or tree can sit on it
CELL_LIMIT = 250
# a hunt holds the camera this far from its mob across the ground, and this high over its middle,
# out of reach of a zombie's arms and well inside beam range. A hit mob turns on the drone
ATTACK_STANDOFF = 4.0
HUNT_HEIGHT = 4.0
# a mob seen within this many frames is worth chasing, older sightings are where it was
CHASE_FRAMES = 60
# a remembered mob's spot can sit this far outside the region and still count as in it
REGION_MARGIN = 1.0


def sweep(cells: list, start: tuple[float, float]) -> list[tuple[float, float]]:
    """The cells in rows along x, alternating direction, from the corner nearest start"""
    xs = sorted({c[0] for c in cells})
    if math.dist(start, (xs[-1], start[1])) < math.dist(start, (xs[0], start[1])):
        xs.reverse()
    zs = sorted({c[1] for c in cells})
    flip = math.dist(start, (start[0], zs[-1])) < math.dist(start, (start[0], zs[0]))
    order = []
    for i, x in enumerate(xs):
        row = sorted((c for c in cells if c[0] == x), key=lambda c: c[1], reverse=flip != (i % 2 == 1))
        order.extend((float(c[0]), float(c[1])) for c in row)
    return order


class PatrolPlanner(HonestExpert):
    def __init__(self, mask_ids: dict, depth_max: float = 64.0, reader=None, schematics: Path | None = None) -> None:
        super().__init__(mask_ids, depth_max, reader)
        self.hostile = hostile_ids(mask_ids)
        self.mobs = MobTracker(self.hostile)
        self.route: list[tuple[float, float]] = []
        self.rounds_done = 0
        self.cell_steps = 0
        self.target = None
        # the beam held on the target last step and still on it, the mod keeps it locked while the mob is in sight
        self.locked = False

    def decide(self, state: dict) -> dict:
        job = state["job"]
        if "scan" in job:
            self.mobs.scanned([m["pos"] for m in state.get("mobs", [])])
        else:
            self.mobs.update(state, self.obs["depth"], self.obs["mask"], self.depth_max)
        events = state.get("events", [])
        if any(e.get("type") == "attack_failed" for e in events):
            self.locked = False
        for event in events:
            if event.get("type") == "attack" and event.get("killed") and self.target is not None:
                self.mobs.killed(self.target.pos)
                self.target = None
                self.locked = False
        if job["hunt"]:
            mob = self.pick_target(state, job["region"])
            if mob is not None:
                return self.hunt(state, mob)
        self.locked = False
        return self.patrol(state, job)

    def pick_target(self, state: dict, region):
        """The mob being chased while it's still seen, else the nearest one seen lately inside the region"""
        lo, hi = np.asarray(region[:3]) - REGION_MARGIN, np.asarray(region[3:]) + 1 + REGION_MARGIN
        fresh = [m for m in self.mobs.mobs if self.mobs.frame - m.seen < CHASE_FRAMES and np.all(m.pos >= lo) and np.all(m.pos <= hi)]
        if self.target is not None and any(m is self.target for m in fresh):
            return self.target
        self.target = min(fresh, key=lambda m: math.dist(m.pos, state["pos"]), default=None)
        return self.target

    def hunt(self, state: dict, mob) -> dict:
        """
        Close in on the mob with the camera on it. The beam goes on once the crosshair is on a hostile mob in range,
        and stays on while it holds, the mod keeps it locked as long as the mob is in sight
        """
        aim = tuple(float(v) for v in mob.pos)
        self.intent = {"mode": "attack", "aim": aim, "via": None, "block": None}
        if self.skill is not None:
            return self.skill.act(state, self.obs, self.intent)
        move, _ = self.fly(state, aim, standoff=ATTACK_STANDOFF, hover=HUNT_HEIGHT)
        eye = (state["pos"][0], state["pos"][1] + state["camera"]["eyeHeight"], state["pos"][2])
        self.locked = self.crosshair_on_hostile() or (self.locked and math.dist(aim, eye) < BEAM_RANGE)
        return tool_action(move, "attack" if self.locked else "none")

    def crosshair_on_hostile(self) -> bool:
        mask, depth = self.obs["mask"], self.obs["depth"]
        h, w = depth.shape
        center = (slice(h // 2 - 1, h // 2 + 1), slice(w // 2 - 1, w // 2 + 1))
        on = np.isin(mask[center], list(self.hostile))
        return bool(on.any()) and float(depth[center][on].min()) <= BEAM_RANGE

    def patrol(self, state: dict, job: dict) -> dict:
        """Fly the sweep, looking down ahead for mobs. Patrols with no rounds to fly (a hunt arena) sweep until it ends"""
        pos = state["pos"]
        here = (pos[0], pos[2])
        if not self.route:
            if job["rounds"] and self.rounds_done >= job["rounds"]:
                return tool_action([0, 0, 0, 0, 0])
            self.route = sweep(job["cells"], here)
            self.cell_steps = 0
        cell = self.route[0]
        self.cell_steps += 1
        if math.dist(here, cell) <= job["visitRadius"] - VISIT_MARGIN or self.cell_steps > CELL_LIMIT:
            self.route.pop(0)
            self.cell_steps = 0
            if not self.route:
                self.rounds_done += 1
            return self.patrol(state, job)
        goal = self.reachable(cell, job["visitRadius"] - VISIT_MARGIN)
        cruise = self.cruise_y(state, SEARCH_HEIGHT) + state["camera"]["eyeHeight"]
        ground = self.map.ground_near(goal[0], goal[1], default=pos[1] - SEARCH_HEIGHT)
        via, aim = (goal[0], cruise, goal[1]), (goal[0], ground, goal[1])
        # the camera stays on the cell's ground the whole way, which looks down ahead for mobs
        _, pitch_error, _ = aim_errors(state, aim)
        move, _ = self.fly(state, via, standoff=0.0, look_down=state.get("pitch", 0.0) + pitch_error)
        return self.view(state, move, via, aim)

    def reachable(self, cell: tuple[float, float], radius: float) -> tuple[float, float]:
        """The cell's center, or the free spot nearest it within radius when something stands on the center"""
        if self.map.free(*cell):
            return cell
        for r in np.arange(0.5, radius + 0.01, 0.5):
            for a in np.linspace(0, 2 * math.pi, 12, endpoint=False):
                p = (cell[0] + r * math.cos(a), cell[1] + r * math.sin(a))
                if self.map.free(*p):
                    return p
        return cell
