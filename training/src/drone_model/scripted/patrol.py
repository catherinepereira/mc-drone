"""
The planner for patrol and guard jobs and their arenas. It sweeps the region's patrol cells row by row, round after
round. With hunt it breaks off for any of its prey it sees inside the region (hostile mobs, every mob, or the kinds the
job names), closes to beam range, and holds the beam on it until it's down. With none in sight it first goes back to
look where prey was last seen, and after each sweep round it looks over the region from above its middle. It's never
told how many prey there are, a hunt arena ends once none is left.
In vision perception mobs come from the camera through perception.mobs, in scan perception the job hands them over
"""

from __future__ import annotations

import math
from pathlib import Path

import numpy as np

from ..perception.mobs import prey_ids
from .base import AIM_TOLERANCE, BEAM_RANGE, BOUNDS_GAP, CEILING_GAP, SEARCH_HEIGHT, Planner, aim_angle, aim_errors, tool_action

# the drone counts a cell as passed this far inside its visit radius, the server's check is on the exact pose
VISIT_MARGIN = 0.5
# steps spent heading for one cell before it's skipped this round, a pillar or tree can sit on it
CELL_LIMIT = 250
# a hunt holds the camera this far from its mob across the ground, and this high over its middle,
# out of reach of a zombie's arms and well inside beam range. A hit mob turns on the drone
ATTACK_STANDOFF = 4.0
HUNT_HEIGHT = 4.0
# extra height over a mob inside the standoff, backing away blind crosses the walls behind the drone
CLOSE_CLIMB = 2.0
# a mob seen within this many frames is worth chasing, older sightings are where it was
CHASE_FRAMES = 60
# a remembered mob's spot can sit this far outside the region and still count as in it
REGION_MARGIN = 1.0
# steps spent looking for a lost mob where it was last seen before it's forgotten, it can be behind a pillar
REVISIT_LIMIT = 150
# the lookout after each hunt sweep: the camera this high over the region's middle, turned on this many points around
# it, each held this many frames or given up on after LOOKOUT_LIMIT steps
LOOKOUT_HEIGHT = 7.0
LOOKOUT_VIEWS = 8
LOOKOUT_DWELL = 3
LOOKOUT_LIMIT = 60


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


class PatrolPlanner(Planner):
    def __init__(self, mask_ids: dict, depth_max: float = 64.0, reader=None, schematics: Path | None = None, core=None) -> None:
        super().__init__(mask_ids, depth_max, reader, core=core)
        self.mask_ids = mask_ids
        # the mask ids of the job's prey once the first step names it
        self.prey: set[int] = set()
        self.route: list[tuple[float, float]] = []
        self.rounds_done = 0
        self.cell_steps = 0
        self.target = None
        # the beam held on the target last step and still on it, the mod keeps it locked while the mob is in sight
        self.locked = False
        # the lost mob being looked for and the steps spent on it
        self.lost = None
        self.lost_steps = 0
        # the points around the region still to look at from the lookout, and the frames and steps on the current one
        self.lookout: list[tuple[float, float, float]] = []
        self.lookout_dwell = 0
        self.lookout_steps = 0

    def decide(self, state: dict) -> dict:
        job = state["job"]
        if not self.prey:
            self.prey = prey_ids(job.get("prey"), self.mask_ids)
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
            lost = self.pick_lost(job["region"])
            if lost is not None:
                self.locked = False
                return self.revisit(state, lost)
            if self.lookout:
                self.locked = False
                return self.look_out(state, job["region"])
        self.locked = False
        return self.patrol(state, job)

    def pick_target(self, state: dict, region):
        """The mob being chased while it's still seen, else the nearest prey seen lately inside the region"""
        lo, hi = np.asarray(region[:3]) - REGION_MARGIN, np.asarray(region[3:]) + 1 + REGION_MARGIN
        fresh = [
            m for m in self.mobs.mobs
            if m.kind in self.prey and self.mobs.frame - m.seen < CHASE_FRAMES and np.all(m.pos >= lo) and np.all(m.pos <= hi)
        ]
        if self.target is not None and any(m is self.target for m in fresh):
            return self.target
        self.target = min(fresh, key=lambda m: math.dist(m.pos, state["pos"]), default=None)
        return self.target

    def in_region(self, mob, region) -> bool:
        lo, hi = np.asarray(region[:3]) - REGION_MARGIN, np.asarray(region[3:]) + 1 + REGION_MARGIN
        return bool(np.all(mob.pos >= lo) and np.all(mob.pos <= hi))

    def pick_lost(self, region):
        """Prey still remembered but not seen lately, the one being looked for first, else the most recently seen"""
        lost = [m for m in self.mobs.mobs if m.kind in self.prey and self.in_region(m, region)]
        if self.lost is not None and any(m is self.lost for m in lost):
            return self.lost
        self.lost = max(lost, key=lambda m: m.seen, default=None)
        self.lost_steps = 0
        return self.lost

    def revisit(self, state: dict, mob) -> dict:
        """
        Look at where a lost mob was last seen, from the hunt's standoff. Once the camera sees past that spot the tracker
        lets the mob go, and one never found there is forgotten after REVISIT_LIMIT steps
        """
        self.lost_steps += 1
        if self.lost_steps > REVISIT_LIMIT:
            self.mobs.mobs.remove(mob)
            self.lost = None
            return self.patrol(state, state["job"])
        aim = tuple(float(v) for v in mob.pos)
        pos = state["pos"]
        away = np.asarray([pos[0] - aim[0], pos[2] - aim[2]])
        away = away / max(float(np.linalg.norm(away)), 1e-3) * ATTACK_STANDOFF
        # a mob last seen by the wall would put the standoff past the geofence
        b = self.bounds
        via = (
            float(np.clip(aim[0] + away[0], b[0] + BOUNDS_GAP, b[3] - BOUNDS_GAP)), aim[1] + HUNT_HEIGHT,
            float(np.clip(aim[2] + away[1], b[2] + BOUNDS_GAP, b[5] - BOUNDS_GAP)),
        )
        move, _ = self.fly_to_view(state, via, aim)
        return self.view(state, move, via, aim)

    def start_lookout(self, region) -> None:
        """Points on the ground around the region's middle, a ring two thirds of the way to its edge"""
        cx, cz = (region[0] + region[3] + 1) / 2, (region[2] + region[5] + 1) / 2
        r = min(region[3] - region[0], region[5] - region[2]) / 3
        ground = self.map.ground_near(cx, cz, default=region[1])
        self.lookout = [
            (cx + r * math.sin(a), ground, cz - r * math.cos(a)) for a in np.linspace(0, 2 * math.pi, LOOKOUT_VIEWS, endpoint=False)
        ]
        self.lookout_dwell = 0
        self.lookout_steps = 0

    def look_out(self, state: dict, region) -> dict:
        """Hover over the region's middle and turn the camera on each lookout point in turn"""
        cx, cz = (region[0] + region[3] + 1) / 2, (region[2] + region[5] + 1) / 2
        ground = self.map.ground_near(cx, cz, default=state["pos"][1] - SEARCH_HEIGHT)
        eye = state["camera"]["eyeHeight"]
        via = (cx, min(ground + LOOKOUT_HEIGHT, self.map.headroom(cx, cz) - CEILING_GAP) + eye, cz)
        aim = self.lookout[0]
        move, arrived = self.fly_to_view(state, via, aim)
        self.lookout_steps += 1
        if arrived and aim_angle(state, aim) < AIM_TOLERANCE:
            self.lookout_dwell += 1
        if self.lookout_dwell >= LOOKOUT_DWELL or self.lookout_steps > LOOKOUT_LIMIT:
            self.lookout.pop(0)
            self.lookout_dwell = 0
            self.lookout_steps = 0
        return self.view(state, move, via, aim)

    def hunt(self, state: dict, mob) -> dict:
        """
        Close in on the mob with the camera on it. The beam goes on once the crosshair is on its prey in range,
        and stays on while it holds, the mod keeps it locked as long as the mob is in sight
        """
        aim = tuple(float(v) for v in mob.pos)
        self.intent = {"mode": "attack", "aim": aim, "via": None, "block": None}
        close = math.hypot(aim[0] - state["pos"][0], aim[2] - state["pos"][2]) < ATTACK_STANDOFF - 0.8
        move, _ = self.fly(state, aim, standoff=ATTACK_STANDOFF, hover=HUNT_HEIGHT + (CLOSE_CLIMB if close else 0.0), back_off=False)
        eye = (state["pos"][0], state["pos"][1] + state["camera"]["eyeHeight"], state["pos"][2])
        self.locked = self.crosshair_on_prey() or (self.locked and math.dist(aim, eye) < BEAM_RANGE)
        return tool_action(move, "attack" if self.locked else "none")

    def crosshair_on_prey(self) -> bool:
        mask, depth, _ = self.core.crosshair(2)
        on = np.isin(mask, list(self.prey))
        return bool(on.any()) and float(depth[on].min()) <= BEAM_RANGE

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
                if job["hunt"]:
                    self.start_lookout(job["region"])
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
