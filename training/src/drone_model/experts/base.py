"""
What every scripted expert shares: the action format, aim and viewpoint geometry, and HonestExpert, which flies to goals
from the drone's own pose and what its camera has shown (see perception.worldmap.WorldMap)
"""

from __future__ import annotations

import math

import numpy as np

from ..perception.reader import mask_lut
from ..perception.worldmap import WorldMap, perceivable
from .navigate import waypoint

TOOLS = ("none", "break", "place", "open", "close")
REACH = 4.4
AIM_TOLERANCE = 3.0
TURN_GAIN = 1.0 / 15.0
SPIN_TICKS = 24
SWEEP_TICKS = 12
SEARCH_SPACING = 6.0
SEARCH_PITCH = 30.0
CRUISE_HEIGHT = 2.5
# keep the drone's feet this far under a known ceiling
CEILING_GAP = 0.9
# searching flies higher, it sees over hills and pedestals
SEARCH_HEIGHT = 3.5
# where the camera goes for placing: this far out from a side or bottom face's cell, or this high over the cell
# for a top face, leaning this far toward the drone
VIA_DIST = 1.3
TOP_VIA_HEIGHT = 2.0
TOP_LEAN = 0.8
# within this many blocks of a viewpoint the drone strafes to it with the camera on the aim point
VIEW_APPROACH = 1.5
# steps spent on one block before giving up on it, mining ore takes about 25
WORK_LIMIT = 150
# frames a survey holds its aim on each view, and steps before it moves on from a view it can't reach
SURVEY_DWELL = 4
SURVEY_LIMIT = 90


def tool_action(move, tool: str = "none", slot: int = 0, transfer: tuple[int, int] = (0, 0), block: str | None = None) -> dict:
    action = {"move": np.asarray(move, dtype=np.float32), "tool": TOOLS.index(tool), "slot": slot, "transfer": np.asarray(transfer, dtype=np.int64)}
    if block:
        action["block"] = block
    return action


def center(p) -> tuple[float, float, float]:
    return (p[0] + 0.5, p[1] + 0.5, p[2] + 0.5)


def wrap(degrees: float) -> float:
    return (degrees + 180.0) % 360.0 - 180.0


def aim_errors(state: dict, point) -> tuple[float, float, float]:
    """Yaw and pitch error in degrees from the drone camera to point, and the distance"""
    pos = state["pos"]
    dx = point[0] - pos[0]
    dy = point[1] - (pos[1] + state["camera"]["eyeHeight"])
    dz = point[2] - pos[2]
    horizontal = math.hypot(dx, dz)
    yaw_error = wrap(math.degrees(math.atan2(-dx, dz)) - state["yaw"])
    pitch_error = -math.degrees(math.atan2(dy, max(horizontal, 1e-3))) - state.get("pitch", 0.0)
    return yaw_error, pitch_error, math.sqrt(dx * dx + dy * dy + dz * dz)


def aim_angle(state: dict, point) -> float:
    """
    Degrees between where the camera looks and the direction to point.
    Unlike the yaw error it stays meaningful looking straight down, where yaw barely moves the crosshair
    """
    pos = state["pos"]
    to = np.array([point[0] - pos[0], point[1] - (pos[1] + state["camera"]["eyeHeight"]), point[2] - pos[2]])
    yaw, pitch = math.radians(state["yaw"]), math.radians(state.get("pitch", 0.0))
    forward = np.array([-math.sin(yaw) * math.cos(pitch), -math.sin(pitch), math.cos(yaw) * math.cos(pitch)])
    cos = float(forward @ to) / max(float(np.linalg.norm(to)), 1e-6)
    return math.degrees(math.acos(max(-1.0, min(1.0, cos))))


def place_viewpoint(state: dict, cell, d) -> tuple[float, float, float]:
    """
    Where the camera goes to place into cell against the support at cell + d: in front of a side or bottom face,
    and for a top face high over the cell, leaning toward the drone so the camera doesn't look straight down
    """
    c = center(cell)
    if d[1] != -1:
        return tuple(np.subtract(c, np.asarray(d) * VIA_DIST))
    lean = np.array([state["pos"][0] - c[0], state["pos"][2] - c[2]])
    lean = lean / max(np.linalg.norm(lean), 1e-6) * TOP_LEAN
    return (c[0] + lean[0], c[1] + TOP_VIA_HEIGHT, c[2] + lean[1])


def looking_at(state: dict, pos) -> bool:
    hit = state.get("lookingAt")
    return bool(hit) and tuple(hit["pos"]) == tuple(pos) and hit["dist"] <= REACH


def carried(state: dict, items: set[str] | None = None) -> list[int]:
    return [i for i, (name, count) in enumerate(state.get("inventory", [])) if count > 0 and (items is None or name in items)]


class HonestExpert:
    """Shared exploring, mapping, and flying. Subclasses decide what to do with what has been seen"""

    def __init__(self, mask_ids: dict, depth_max: float = 64.0, reader=None) -> None:
        """
        With a reader (a drone_model.perception.reader.Reader) the expert sees only what the block reader makes of the camera
        image and depth, without one it reads the mod's mask stream, which is ground truth
        """
        self.map = WorldMap(mask_ids)
        self.depth_max = depth_max
        self.reader = reader
        # what the planner wants this step, {"mode": "view" | "break" | "place", "aim": point, "via": point or None},
        # recorded as the goal for training the cell skill
        self.intent: dict | None = None
        # a learned controller (drone_model.policies.skill.SkillAgent) that flies, aims, and fires in place of the scripted one
        self.skill = None
        # a drone_model.perception.memory.VoxelMemory the reader's output goes into, and the cells that changed this step
        self.memory = None
        self.changes: list = []
        self.reader_lut = mask_lut(mask_ids) if reader is not None else None
        # the reader's class per pixel for the current frame
        self.classes: np.ndarray | None = None
        self.spin_left = SPIN_TICKS
        self.home: tuple[float, float] | None = None
        self.visited: list[tuple[float, float]] = []
        self.search_goal: tuple[float, float] | None = None
        # blocks the expert gave up on, and how long it has worked on the current one
        self.unreachable: set[tuple] = set()
        self.working_on: tuple | None = None
        self.work_steps = 0
        self.absent_steps = 0
        self.occluded_steps = 0
        self.last_pos: tuple | None = None
        self.last_up = 0.0
        self.stalled_climbs = 0
        self.still_steps = 0
        self.unstick_left = 0
        # called with the state each step before decide, an action it returns replaces the planner's (see energy.BatteryKeeper)
        self.errand = None

    def act(self, state: dict, obs: dict) -> dict:
        self.intent = None
        state = perceivable(state)
        self.bounds = state["bounds"]
        self.map.fence = self.bounds
        if self.home is None:
            self.home = (state["pos"][0], state["pos"][2])
        if self.reader is not None:
            classes, ripe = self.reader.read(obs["rgb"], obs["depth"])
            obs = {**obs, "mask": self.reader_lut[classes], "ripe": ripe}
            self.classes = classes
            if self.memory is not None:
                self.changes = self.memory.apply_events(state.get("events", [])) + self.memory.observe(state, obs["depth"], classes, ripe, self.depth_max)
        self.obs = obs
        self.map.update(state, obs["depth"], obs["mask"], self.depth_max, obs.get("ripe"))
        pos = state["pos"]
        # told to climb and didn't rise for a few steps, there is a ceiling just over the drone's 0.4-block-tall body.
        # One stalled step proves nothing, a tool action can hold the drone for a tick
        stalled = self.last_up > 0.3 and self.last_pos is not None and pos[1] - self.last_pos[1] < 0.02
        self.stalled_climbs = self.stalled_climbs + 1 if stalled else 0
        if self.stalled_climbs >= 3:
            self.map.bump_ceiling(pos[0], pos[2], pos[1] + 0.4)
        for event in state.get("events", []):
            if event.get("type") == "break":
                self.map.forget(tuple(event["pos"]))
        if self.unstick_left > 0:
            self.unstick_left -= 1
            self.last_pos = tuple(pos)
            self.last_up = 0.5
            return tool_action([-0.6, 0.0, 0.5, 0.6, 0.0])
        action = self.errand(state) if self.errand is not None else None
        if action is None:
            action = self.decide(state)
        self.watch_progress(state, action)
        self.last_up = float(action["move"][2])
        return action

    def watch_progress(self, state: dict, action: dict) -> None:
        """Back off and turn when told to move but the drone hasn't, it is pressed against something or boxed in"""
        pos = tuple(state["pos"])
        moved = self.last_pos is None or math.dist(pos, self.last_pos) > 0.03
        self.last_pos = pos
        pushing = max(abs(float(v)) for v in action["move"][:3]) > 0.3 and TOOLS[action["tool"]] == "none"
        self.still_steps = 0 if moved or not pushing else self.still_steps + 1
        if self.still_steps >= 8:
            self.still_steps = 0
            self.unstick_left = 8

    def decide(self, state: dict) -> dict:
        raise NotImplementedError

    def cruise_y(self, state: dict, height: float = CRUISE_HEIGHT) -> float:
        pos = state["pos"]
        # with no ground seen nearby, hold the current altitude
        ground = self.map.ground_near(pos[0], pos[2], default=pos[1] - height)
        return min(ground + height, self.map.headroom(pos[0], pos[2]) - CEILING_GAP)

    def search_points(self) -> list[tuple[float, float]]:
        """A grid inside the geofence, skipping spots already searched"""
        box = self.bounds
        # stay a couple of blocks inside the geofence so a drift doesn't cross it
        x0, z0, x1, z1 = box[0] + 2, box[2] + 2, box[3] - 2, box[5] - 2
        nx = max(1, round((x1 - x0) / SEARCH_SPACING))
        nz = max(1, round((z1 - z0) / SEARCH_SPACING))
        points = [(x0 + (x1 - x0) * (i + 0.5) / nx, z0 + (z1 - z0) * (j + 0.5) / nz) for i in range(nx) for j in range(nz)]
        return [p for p in points if all(math.dist(p, v) > SEARCH_SPACING / 2 for v in self.visited) and self.map.free(*p)]

    def explore(self, state: dict) -> dict:
        """Turn on the spot, then visit unsearched points inside the geofence nearest first, sweeping the view at each"""
        pos = state["pos"]
        here = (pos[0], pos[2])
        cruise = self.cruise_y(state, SEARCH_HEIGHT)
        up = float(np.clip(cruise - pos[1], -1, 1))
        pitch = float(np.clip((SEARCH_PITCH - state.get("pitch", 0.0)) * TURN_GAIN, -1, 1))
        if self.spin_left > 0:
            self.spin_left -= 1
            return tool_action([0, 0, up, 1.0, pitch])
        # a search point that turned out to sit inside a tree or pillar counts as searched
        if self.search_goal is not None and (math.dist(self.search_goal, here) < 1.5 or (self.search_goal != self.home and not self.map.free(*self.search_goal))):
            self.visited.append(self.search_goal)
            self.search_goal = None
            self.spin_left = SWEEP_TICKS
            return tool_action([0, 0, up, 1.0, pitch])
        if self.search_goal is None:
            points = self.search_points()
            if not points:
                # everything searched once, start over, something may have been hidden from every vantage
                self.visited.clear()
                points = self.search_points()
            self.search_goal = min(points, key=lambda p: math.dist(p, here)) if points else self.home
        goal = self.search_goal
        move, _ = self.fly(state, (goal[0], cruise, goal[1]), standoff=0.0, look_down=SEARCH_PITCH)
        return tool_action(move)

    def fly(self, state: dict, point, standoff: float, hover: float = 0.0, look_down: float | None = None, column=None) -> tuple[list, bool]:
        """Steer toward point until within standoff blocks horizontally, keeping the camera hover above it"""
        pos = state["pos"]
        self.map.ignore = column
        self.map.fly_y = pos[1]
        horizontal = math.hypot(point[0] - pos[0], point[2] - pos[2])
        eye_target = point[1] + hover
        if horizontal > standoff + 2.0:
            # travel above the local ground, hills between here and the target would block a low path
            eye_target = max(eye_target, self.cruise_y(state) + state["camera"]["eyeHeight"])
        eye_target = min(eye_target, self.map.headroom(pos[0], pos[2]) - CEILING_GAP + state["camera"]["eyeHeight"])
        up = float(np.clip(eye_target - (pos[1] + state["camera"]["eyeHeight"]), -1.0, 1.0))
        arrived = horizontal <= standoff + 0.3
        yaw_to_point, pitch_to_point, _ = aim_errors(state, point)
        if arrived:
            forward = -0.3 if horizontal < standoff - 0.8 else 0.0
            move = [forward, 0.0, up, np.clip(yaw_to_point * TURN_GAIN, -1, 1), np.clip(pitch_to_point * TURN_GAIN, -1, 1)]
        else:
            tx, tz = waypoint(state, (point[0], point[2]), self.map)
            yaw_error = wrap(math.degrees(math.atan2(-(tx - pos[0]), tz - pos[2])) - state["yaw"])
            forward = min(1.0, (horizontal - standoff) / 2.0) if abs(yaw_error) < 30.0 else 0.0
            pitch_error = (look_down - state.get("pitch", 0.0)) if look_down is not None else pitch_to_point
            move = [forward, 0.0, up, np.clip(yaw_error * TURN_GAIN, -1, 1), np.clip(pitch_error * TURN_GAIN, -1, 1)]
        self.map.ignore = None
        return move, arrived

    def fly_to_view(self, state: dict, via, aim, column=None) -> tuple[list, bool]:
        """
        Put the camera at via with it turned on aim.
        Close to via the drone strafes into place with the camera on aim the whole way, so the camera's target never jumps
        """
        pos = state["pos"]
        dx, dz = via[0] - pos[0], via[2] - pos[2]
        horizontal = math.hypot(dx, dz)
        if horizontal > VIEW_APPROACH:
            move, _ = self.fly(state, via, standoff=0.0, column=column)
            return move, False
        yaw = math.radians(state["yaw"])
        forward = dx * -math.sin(yaw) + dz * math.cos(yaw)
        right = dx * -math.cos(yaw) + dz * -math.sin(yaw)
        eye = state["camera"]["eyeHeight"]
        eye_target = min(via[1], self.map.headroom(pos[0], pos[2]) - CEILING_GAP + eye)
        yaw_error, pitch_error, _ = aim_errors(state, aim)
        move = [
            float(np.clip(forward, -1, 1)), float(np.clip(right, -1, 1)), float(np.clip(eye_target - (pos[1] + eye), -1, 1)),
            float(np.clip(yaw_error * TURN_GAIN, -1, 1)), float(np.clip(pitch_error * TURN_GAIN, -1, 1)),
        ]
        return move, horizontal <= 0.3

    def view(self, state: dict, move, via, aim) -> dict:
        """Go to via and look at aim, the scripted move or the learned skill's, recorded as a view intent"""
        self.intent = {"mode": "view", "aim": tuple(aim), "via": tuple(via), "block": None}
        if self.skill is not None:
            return self.skill.act(state, self.obs, self.intent)
        return tool_action(move)

    def dwell_on(self, state: dict, look) -> None:
        """Counts frames aimed at look, then moves on to the next of self.views after SURVEY_DWELL of them"""
        if aim_angle(state, look) < AIM_TOLERANCE or self.view_steps > SURVEY_LIMIT + 20:
            self.dwell += 1
        if self.dwell >= SURVEY_DWELL:
            self.views.pop(0)
            self.view_steps = 0
            self.dwell = 0

    def work_on(
        self, state: dict, block, tool: str, standoff: float, hover: float, point=None, expect: str | None = None, face: str | None = None,
        place: str | None = None, via=None,
    ) -> dict:
        """
        Fly to a block, aim at it (or at point), and use tool once the crosshair is on the block.
        With face set the crosshair has to be on that face of exactly this block, for placing where it counts.
        place names the block to put down, the drone swaps it into its first slot itself
        via is where the camera should be, in place of standoff and hover, for a face only visible from one side
        """
        block = tuple(block)
        if block != self.working_on:
            self.working_on = block
            self.work_steps = 0
            self.absent_steps = 0
            self.occluded_steps = 0
        self.work_steps += 1
        if self.work_steps > WORK_LIMIT:
            # a misread landmark or a spot the drone can't reach, look elsewhere
            self.unreachable.add(block)
            self.working_on = None
        point = point or center(block)
        self.intent = {"mode": tool, "aim": tuple(point), "via": tuple(via) if via is not None else None, "block": place}
        if self.skill is not None:
            return self.skill.act(state, self.obs, self.intent)
        if via is not None:
            move, arrived = self.fly_to_view(state, via, point, column=(block[0], block[2]))
        else:
            # something keeps getting in the way of the crosshair, rise and close in for a steeper look
            lift = min(4.0, self.occluded_steps * 0.15)
            move, arrived = self.fly(state, point, max(1.2, standoff - lift / 2), hover + lift, column=(block[0], block[2]))
        dist = math.dist(point, (state["pos"][0], state["pos"][1] + state["camera"]["eyeHeight"], state["pos"][2]))
        aimed = aim_angle(state, point) < AIM_TOLERANCE and dist <= REACH
        on_target = aimed and looking_at(state, block)
        # the remembered spot can be off by a block, the crosshair on the right kind of block nearby is good enough
        hit = state.get("lookingAt")
        if face is not None:
            on_target = aimed and looking_at(state, block) and hit.get("face") == face
        elif not on_target and expect and hit and hit["block"] == expect and hit["dist"] <= REACH:
            near = max(abs(a - b) for a, b in zip(hit["pos"], block)) <= 1
            on_target = near and aim_angle(state, point) < 2 * AIM_TOLERANCE
        blocked_view = hit is not None and hit["dist"] < dist - 0.6 and tuple(hit["pos"]) != block
        self.occluded_steps = self.occluded_steps + 1 if arrived and not on_target and blocked_view else max(0, self.occluded_steps - 1)
        # aimed right at the remembered spot and something else is there, or the ray passes through it
        if expect and face is None and arrived and aimed and not on_target:
            # past the center means past where any block there would have stopped it, a short crop included
            through = hit is None or hit["dist"] > dist + 0.25
            wrong = hit is not None and tuple(hit["pos"]) == block and hit["block"] != expect
            self.absent_steps = self.absent_steps + 1 if through or wrong else 0
            if self.absent_steps >= 3:
                self.unreachable.add(block)
                self.map.forget(block)
        # with a viewpoint the drone can be on target before it settles there, in a tight trench it may never settle
        fire = on_target and (arrived or via is not None)
        return tool_action(move, tool if fire else "none", block=place)

    def known(self, name: str) -> list[tuple]:
        return [c for c in self.map.landmarks(name) if c not in self.unreachable]

    def nearest(self, state: dict, cells) -> tuple | None:
        pos = state["pos"]
        return min(cells, key=lambda c: math.dist(center(c), pos), default=None)
