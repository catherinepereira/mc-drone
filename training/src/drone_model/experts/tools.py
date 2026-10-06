"""
Scripted experts for every task, used to generate demos and as a reference in evaluation.
They know only the drone's own pose and what its camera has shown (see perception.worldmap.WorldMap):
they explore until the thing they need comes into view, then remember where it was
"""

from __future__ import annotations

import math

import numpy as np

from .navigate import waypoint
from ..perception.blocks import BUILD_PALETTE
from ..perception.worldmap import WorldMap, perceivable
from ..perception.reader import CLASSES, SKY, mask_lut

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
        action = self.decide(state)
        self.watch_progress(state, action)
        self.last_up = float(action["move"][2])
        return action

    def watch_progress(self, state: dict, action: dict) -> None:
        """Back off and turn when told to fly forward but the drone hasn't moved, it is pressed against something"""
        pos = tuple(state["pos"])
        moved = self.last_pos is None or math.dist(pos, self.last_pos) > 0.03
        self.last_pos = pos
        pushing = action["move"][0] > 0.3 and TOOLS[action["tool"]] == "none"
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
        yaw_error, pitch_error, _ = aim_errors(state, look)
        if abs(yaw_error) < AIM_TOLERANCE and abs(pitch_error) < AIM_TOLERANCE or self.view_steps > SURVEY_LIMIT + 20:
            self.dwell += 1
        if self.dwell >= SURVEY_DWELL:
            self.views.pop(0)
            self.view_steps = 0
            self.dwell = 0

    def work_on(
        self, state: dict, block, tool: str, standoff: float, hover: float, slot: int = 0, point=None, expect: str | None = None, face: str | None = None,
        place: str | None = None, via=None,
    ) -> dict:
        """
        Fly to a block, aim at it (or at point), and use tool once the crosshair is on the block.
        With face set the crosshair has to be on that face of exactly this block, for placing where it counts.
        place names the block to put down, the drone picks whichever slot holds it
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
        yaw_error, pitch_error, dist = aim_errors(state, point)
        aimed = abs(yaw_error) < AIM_TOLERANCE and abs(pitch_error) < AIM_TOLERANCE and dist <= REACH
        on_target = aimed and looking_at(state, block)
        # the remembered spot can be off by a block, the crosshair on the right kind of block nearby is good enough
        hit = state.get("lookingAt")
        if face is not None:
            on_target = aimed and looking_at(state, block) and hit.get("face") == face
        elif not on_target and expect and hit and hit["block"] == expect and hit["dist"] <= REACH:
            near = max(abs(a - b) for a, b in zip(hit["pos"], block)) <= 1
            on_target = near and abs(yaw_error) < 2 * AIM_TOLERANCE and abs(pitch_error) < 2 * AIM_TOLERANCE
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
        return tool_action(move, tool if fire else "none", slot=slot, block=place)

    def known(self, name: str) -> list[tuple]:
        return [c for c in self.map.landmarks(name) if c not in self.unreachable]

    def nearest(self, state: dict, cells) -> tuple | None:
        pos = state["pos"]
        return min(cells, key=lambda c: math.dist(center(c), pos), default=None)


class NavigateExpert(HonestExpert):
    def decide(self, state: dict) -> dict:
        marker = self.nearest(state, self.known("marker"))
        if marker is None:
            return self.explore(state)
        move, _ = self.fly(state, center(marker), standoff=0.0, column=(marker[0], marker[2]))
        return tool_action(move)


class DigExpert(HonestExpert):
    def decide(self, state: dict) -> dict:
        ore = self.nearest(state, self.known("coal_ore"))
        if ore is None:
            return self.explore(state)
        return self.work_on(state, ore, "break", standoff=2.5, hover=0.9, expect="minecraft:coal_ore")


class ContainerMixin:
    def deliver(self, state: dict, chest, items: set[str] | None) -> dict:
        """Open the chest, then move every matching stack from the drone into it"""
        container = state.get("container")
        if container and tuple(container["pos"]) == tuple(chest):
            slots = carried(state, items)
            return tool_action([0, 0, 0, 0, 0], transfer=(1, slots[0])) if slots else tool_action([0, 0, 0, 0, 0], "close")
        if container:
            return tool_action([0, 0, 0, 0, 0], "close")
        return self.work_on(state, chest, "open", standoff=2.0, hover=0.8, expect="minecraft:chest")


class MineDeliverExpert(HonestExpert, ContainerMixin):
    def decide(self, state: dict) -> dict:
        ore = self.nearest(state, self.known("coal_ore"))
        if ore is not None:
            if state.get("container"):
                return tool_action([0, 0, 0, 0, 0], "close")
            return self.work_on(state, ore, "break", standoff=2.5, hover=0.9, expect="minecraft:coal_ore")
        chest = self.nearest(state, self.known("chest"))
        coal = carried(state, {"minecraft:coal"})
        # drop off what's in hand, then keep exploring in case more ore is out of sight
        if chest is not None and (coal or state.get("container")):
            return self.deliver(state, chest, {"minecraft:coal"})
        return self.explore(state)


class ChestTransferExpert(HonestExpert, ContainerMixin):
    """Opens chests to learn which one holds the items, empties it, and fills the other"""

    def __init__(self, mask_ids: dict, depth_max: float = 64.0, reader=None) -> None:
        super().__init__(mask_ids, depth_max, reader)
        self.roles: dict[tuple, str] = {}

    def decide(self, state: dict) -> dict:
        container = state.get("container")
        if container:
            chest = tuple(container["pos"])
            full = [i for i, (_, count) in enumerate(container["slots"]) if count > 0]
            role = self.roles.setdefault(chest, "source" if full else "target")
            if role == "source":
                return tool_action([0, 0, 0, 0, 0], transfer=(2, full[0])) if full else tool_action([0, 0, 0, 0, 0], "close")
            if carried(state):
                return self.deliver(state, chest, None)
            return tool_action([0, 0, 0, 0, 0], "close")
        chests = self.known("chest")
        target = next((c for c in chests if self.roles.get(c) == "target"), None)
        if carried(state) and target is not None:
            return self.deliver(state, target, None)
        unknown = self.nearest(state, [c for c in chests if c not in self.roles])
        if unknown is not None:
            return self.work_on(state, unknown, "open", standoff=2.0, hover=0.8, expect="minecraft:chest")
        return self.explore(state)


class PlaceExpert(HonestExpert):
    def decide(self, state: dict) -> dict:
        pad = self.nearest(state, self.known("pad"))
        slot = next(iter(carried(state, {"minecraft:oak_planks"})), 0)
        if pad is None:
            return tool_action(self.explore(state)["move"], slot=slot)
        # aim at the middle of the pad's top face so the placed block lands on top of it
        point = (pad[0] + 0.5, pad[1] + 1.02, pad[2] + 0.5)
        return self.work_on(state, pad, "place", standoff=1.8, hover=2.5, slot=slot, point=point, expect="minecraft:lime_concrete")


BUILD_SIZE = 3
# a cell holds a block when it collected this many hits and at least this share of the busiest cell's
MIN_CELL_VOTES = 8
MIN_CELL_SHARE = 0.03
SURVEY_DIST = 4.5
SURVEY_DWELL = 4
SURVEY_LIMIT = 90


class BuildExpert(HonestExpert):
    """
    Finds the cyan reference base, circles it to read the build from its camera, then copies it onto the lime base
    layer by layer, placing each block on the top face of the one below
    """

    def __init__(self, mask_ids: dict, depth_max: float = 64.0, reader=None) -> None:
        super().__init__(mask_ids, depth_max, reader)
        self.map.track("reference", "minecraft:cyan_concrete")
        for block in BUILD_PALETTE:
            self.map.track(block, block, strict=True)
        self.reference: tuple[int, int, int] | None = None
        self.site: tuple[int, int, int] | None = None
        self.site_checked = False
        self.views: list[tuple[float, float, float]] | None = None
        self.view_steps = 0
        self.dwell = 0
        self.plan: list[tuple[tuple[int, int, int], str]] | None = None
        self.placed: dict[tuple[int, int, int], str] = {}
        self.target: tuple[tuple[int, int, int], str] | None = None

    def base_center(self, name: str) -> tuple[int, int, int] | None:
        """Center block of a 3x3 concrete base, from where its hits landed"""
        votes = self.map.votes[name]
        if sum(votes.values()) < 30:
            return None
        ys: dict[int, int] = {}
        for (_, y, _), n in votes.items():
            ys[y] = ys.get(y, 0) + n
        y = max(ys, key=ys.get)
        layer = {(c[0], c[2]): n for c, n in votes.items() if c[1] == y}

        def mean() -> tuple[float, float]:
            total = sum(layer.values())
            return sum(x * n for (x, _), n in layer.items()) / total, sum(z * n for (_, z), n in layer.items()) / total

        mx, mz = mean()
        if name == "reference":
            # the build stands on the base, so its blocks mark base cells the build itself hides from view
            for block in BUILD_PALETTE:
                for (x, by, z), n in self.map.votes[block].items():
                    if y < by <= y + BUILD_SIZE and abs(x - mx) <= 2.5 and abs(z - mz) <= 2.5:
                        layer[(x, z)] = layer.get((x, z), 0) + n
            mx, mz = mean()
        # a build on the base hides part of it, which drags the mean off center, so take the 3x3 window holding
        # the most hits, the outer ring always shows its sides
        def window(c):
            return sum(layer.get((c[0] + dx, c[1] + dz), 0) for dx in (-1, 0, 1) for dz in (-1, 0, 1))

        candidates = [(round(mx) + dx, round(mz) + dz) for dx in range(-2, 3) for dz in range(-2, 3)]
        best = max(candidates, key=lambda c: (window(c), -math.dist(c, (mx, mz))))
        return (best[0], y, best[1])

    def read_blueprint(self) -> list[tuple[tuple[int, int, int], str]]:
        """Offsets and blocks of the reference build, ground layer first"""
        cx, cy, cz = self.reference
        counts: dict[tuple[int, int, int], tuple[int, str]] = {}
        for dx in (-1, 0, 1):
            for dz in (-1, 0, 1):
                for dy in range(1, BUILD_SIZE + 1):
                    cell = (cx + dx, cy + dy, cz + dz)
                    best = max(BUILD_PALETTE, key=lambda b: self.map.votes[b].get(cell, 0))
                    counts[(dx, dy, dz)] = (self.map.votes[best].get(cell, 0), best)
        busiest = max((n for n, _ in counts.values()), default=0)
        solid = {k: b for k, (n, b) in counts.items() if n >= MIN_CELL_VOTES and n >= MIN_CELL_SHARE * busiest}
        cells = {}
        for dx in (-1, 0, 1):
            for dz in (-1, 0, 1):
                top = max((dy for (x, dy, z) in solid if (x, z) == (dx, dz)), default=0)
                # builds are columns, so everything under the top block is solid. A cell glimpsed through a gap
                # keeps the type it showed, one nobody saw takes the type above it
                for dy in range(top, 0, -1):
                    n, seen = counts[(dx, dy, dz)]
                    cells[(dx, dy, dz)] = solid.get((dx, dy, dz)) or (seen if n >= MIN_CELL_VOTES else cells.get((dx, dy + 1, dz)))
        return sorted(cells.items(), key=lambda kv: (kv[0][1], kv[0][0], kv[0][2]))

    def survey_points(self) -> list[tuple[float, float, float]]:
        cx, cy, cz = self.reference
        center = (cx + 0.5, cz + 0.5)
        points = [(center[0] + dx * SURVEY_DIST, cy + 2.6, center[1] + dz * SURVEY_DIST) for dx, dz in ((1, 0), (0, 1), (-1, 0), (0, -1))]
        points.append((center[0] + 2.5, cy + 5.5, center[1]))
        return points

    def decide(self, state: dict) -> dict:
        for event in state.get("events", []):
            if event.get("type") == "place":
                self.placed[tuple(event["pos"])] = event["block"]
        # both centers sharpen as more of each base comes into view, until the expert commits to them
        if self.plan is None:
            self.reference = self.base_center("reference")
        if not self.site_checked:
            self.site = self.base_center("pad")
        if self.reference is None:
            return self.explore(state)
        if self.plan is None:
            return self.survey(state)
        if self.site is None:
            return self.explore(state)
        if not self.site_checked:
            return self.check_site(state)
        return self.build(state)

    def check_site(self, state: dict) -> dict:
        """Look straight down at the build site once so every cell of its base is seen before building"""
        sx, sy, sz = self.site
        above = (sx + 0.5, sy + 5.0, sz + 1.5)
        move, _ = self.fly(state, above, standoff=0.0, look_down=70.0)
        if math.dist((above[0], above[2]), (state["pos"][0], state["pos"][2])) < 0.8:
            self.dwell += 1
            pitch = float(np.clip((70.0 - state.get("pitch", 0.0)) * TURN_GAIN, -1, 1))
            move = [0.0, 0.0, float(np.clip(above[1] - state["pos"][1], -1, 1)), 0.0, pitch]
            if self.dwell >= SURVEY_DWELL:
                self.dwell = 0
                self.site = self.base_center("pad")
                self.site_checked = True
        return self.view(state, move, above, (sx + 0.5, sy + 1.0, sz + 0.5))

    def survey(self, state: dict) -> dict:
        if self.views is None:
            self.views = [p for p in self.survey_points() if self.map.free(p[0], p[2])]
        if not self.views:
            self.plan = self.read_blueprint()
            return tool_action([0, 0, 0, 0, 0])
        view = self.views[0]
        cx, cy, cz = self.reference
        look = (cx + 0.5, cy + 2.0, cz + 0.5)
        self.view_steps += 1
        move, arrived = self.fly(state, view, standoff=0.0, hover=0.0, look_down=None)
        near = math.dist((view[0], view[2]), (state["pos"][0], state["pos"][2])) < 0.8
        if near or self.view_steps > SURVEY_LIMIT:
            yaw_error, pitch_error, _ = aim_errors(state, look)
            up = float(np.clip(view[1] - state["pos"][1], -1, 1))
            move = [0.0, 0.0, up, np.clip(yaw_error * TURN_GAIN, -1, 1), np.clip(pitch_error * TURN_GAIN, -1, 1)]
            self.dwell_on(state, look)
        return self.view(state, move, view, look)

    def slot_of(self, state: dict, block: str) -> int | None:
        return next(iter(carried(state, {block})), None)

    def build(self, state: dict) -> dict:
        sx, sy, sz = self.site
        wanted = {(sx + dx, sy + dy, sz + dz): b for (dx, dy, dz), b in self.plan}
        # a block that landed in the wrong spot or is the wrong kind comes out first
        for pos, block in self.placed.items():
            if wanted.get(pos) != block and self.near_site(pos):
                return self.work_on(state, pos, "break", standoff=2.5, hover=0.9, expect=block)
        todo = [(pos, b) for pos, b in wanted.items() if self.placed.get(pos) != b]
        if not todo:
            return tool_action([0, 0, 0, 0, 0])
        lowest = min(p[1] for p, _ in todo)
        pos, block = min((t for t in todo if t[0][1] == lowest), key=lambda t: math.dist(center(t[0]), state["pos"]))
        slot = self.slot_of(state, block)
        if slot is None:
            # out of this block, nothing more to do
            return tool_action([0, 0, 0, 0, 0])
        support = (pos[0], pos[1] - 1, pos[2])
        below = "minecraft:lime_concrete" if support[1] == sy else wanted.get(support)
        point = (pos[0] + 0.5, pos[1] + 0.02, pos[2] + 0.5)
        return self.work_on(state, support, "place", standoff=1.8, hover=2.5, slot=slot, point=point, expect=below, face="up")

    def near_site(self, pos) -> bool:
        sx, sy, sz = self.site
        return abs(pos[0] - sx) <= 2 and abs(pos[2] - sz) <= 2 and sy < pos[1] <= sy + BUILD_SIZE + 1

    def act(self, state: dict, obs: dict) -> dict:
        action = super().act(state, obs)
        for event in perceivable(state).get("events", []):
            if event.get("type") == "break":
                self.placed.pop(tuple(event["pos"]), None)
        return action


FIELD_VIEW_HEIGHT = 5.0
FIELD_VIEW_PITCH = 60.0
# steps spent looking for one plot on a sweep, and sweeps before the expert only watches for ripe crops
SWEEP_LIMIT = 60
MAX_SWEEPS = 2
# a wheat block is ripe when the reader calls this share of its pixels ripe
RIPE_SHARE = 0.4
MIN_CROP_PIXELS = 6


class HarvestExpert(HonestExpert):
    """
    Works a harvest job's field: looks it over from above and judges each wheat block ripe by its color.
    Harvesting is one cycle, break a ripe crop and replant its plot before moving on.
    With nothing ripe left it sweeps the field plot by plot, looks down at each one, and plants where the block reader
    sees bare farmland
    """

    def __init__(self, mask_ids: dict, depth_max: float = 64.0, reader=None) -> None:
        if reader is None:
            raise ValueError("the harvest expert judges ripeness with the block reader, pass one")
        super().__init__(mask_ids, depth_max, reader)
        self.map.track("farmland", "minecraft:farmland", strict=True)
        self.map.track("wheat", "minecraft:wheat", strict=True)
        # per wheat block, the reader's ripe pixels and all its pixels
        self.ripeness: dict[tuple[int, int, int], list[int]] = {}
        self.views: list[tuple[float, float, float]] | None = None
        self.view_steps = 0
        self.dwell = 0
        self.planted: set[tuple[int, int, int]] = set()
        self.broken: set[tuple[int, int, int]] = set()
        self.refused: set[tuple[int, int, int]] = set()
        # the crop being harvested and replanted
        self.cycle: tuple[int, int, int] | None = None
        # plots left to check on this sweep, and how many sweeps are done
        self.sweep: list[tuple[int, int, int]] | None = None
        self.sweep_steps = 0
        self.sweeps = 0

    def field(self, state: dict) -> list[int]:
        return state["job"]["region"]

    def in_field(self, state: dict, cell) -> bool:
        x0, y0, z0, x1, y1, z1 = self.field(state)
        return x0 <= cell[0] <= x1 and y0 - 1 <= cell[1] <= y1 and z0 <= cell[2] <= z1

    def note_ripeness(self) -> None:
        """Counts the reader's ripe pixels per wheat block from the frame map.update just read"""
        if self.map.last_hits is None or self.map.last_hits[2] is None:
            return
        inside, ids, ripe = self.map.last_hits
        wheat = ids == self.map.landmark_ids["wheat"]
        if not wheat.any():
            return
        golden = ripe[wheat] > 0.5
        cells, first = np.unique(inside[wheat], axis=0, return_inverse=True)
        ripe_px = np.bincount(first.ravel(), weights=golden, minlength=len(cells))
        all_px = np.bincount(first.ravel(), minlength=len(cells))
        for cell, r, n in zip(cells, ripe_px, all_px):
            key = (int(cell[0]), int(cell[1]), int(cell[2]))
            c = self.ripeness.setdefault(key, [0, 0])
            c[0] += int(r)
            c[1] += int(n)

    def farm_y(self) -> int | None:
        """The farmland's height, from where most of its hits landed"""
        ys: dict[int, int] = {}
        for (_, y, _), n in self.map.votes["farmland"].items():
            ys[y] = ys.get(y, 0) + n
        return max(ys, key=ys.get) if ys else None

    def ripe_cells(self, state: dict) -> list[tuple[int, int, int]]:
        out = []
        farm_y = self.farm_y()
        for cell, (r, n) in self.ripeness.items():
            if n >= MIN_CROP_PIXELS and r / n >= RIPE_SHARE and cell not in self.refused and cell not in self.unreachable and self.in_field(state, cell):
                # crops grow one above the farmland, a ray through a short crop can land in the farmland under it
                if self.map.votes["wheat"].get(cell, 0) > 0 and farm_y is not None and cell[1] == farm_y + 1:
                    out.append(cell)
        return out

    def decide(self, state: dict) -> dict:
        self.note_ripeness()
        for event in state.get("events", []):
            pos = tuple(event.get("pos", ()))
            if event.get("type") == "break":
                self.ripeness.pop(pos, None)
                self.planted.discard(pos)
                self.broken.add(pos)
            elif event.get("type") == "place":
                self.planted.add(pos)
                self.broken.discard(pos)
            elif event.get("type") == "break_failed" and "ripe" in event.get("reason", "") and self.working_on:
                self.refused.add(self.working_on)
        if self.views is None or self.views:
            return self.survey(state)
        seed = state["job"].get("seed", "minecraft:wheat_seeds")
        has_seed = bool(carried(state, {seed}))
        if self.cycle is None:
            ripe = self.ripe_cells(state)
            self.cycle = self.nearest(state, ripe) if ripe else None
        if self.cycle is not None:
            action = self.harvest(state, seed, has_seed)
            if action is not None:
                return action
        if has_seed and self.sweeps < MAX_SWEEPS:
            if self.sweep is None:
                self.sweep = self.sweep_plots(state)
            if self.sweep:
                return self.check_plot(state, seed)
            self.sweep = None
            self.sweeps += 1
        # look the field over again for crops that ripened or were missed
        self.views = None
        return self.survey(state)

    def harvest(self, state: dict, seed: str, has_seed: bool) -> dict | None:
        """Break the cycle's crop, then plant its plot. None once the cycle is over"""
        crop = self.cycle
        plot = (crop[0], crop[1] - 1, crop[2])
        if crop in self.planted or crop in self.unreachable or plot in self.unreachable or crop in self.refused:
            self.cycle = None
            return None
        if crop not in self.broken:
            return self.work_on(state, crop, "break", standoff=2.0, hover=2.0, expect="minecraft:wheat")
        if not has_seed:
            self.cycle = None
            return None
        return self.plant(state, plot, seed)

    def plant(self, state: dict, plot, seed: str) -> dict:
        point = (plot[0] + 0.5, plot[1] + 0.95, plot[2] + 0.5)
        via = place_viewpoint(state, (plot[0], plot[1] + 1, plot[2]), (0, -1, 0))
        return self.work_on(state, plot, "place", standoff=0.0, hover=0.0, point=point, face="up", place=seed, via=via)

    def sweep_plots(self, state: dict) -> list[tuple[int, int, int]]:
        """Every cell of the field at farmland height, row by row with every other row reversed"""
        x0, y0, z0, x1, y1, z1 = self.field(state)
        farm_y = self.farm_y()
        y = farm_y if farm_y is not None else y0
        rows = [[(x, y, z) for z in range(z0, z1 + 1)] for x in range(x0, x1 + 1)]
        ordered = [c for i, row in enumerate(rows) for c in (row if i % 2 == 0 else row[::-1])]
        return [c for c in ordered if (c[0], c[1] + 1, c[2]) not in self.planted]

    def center_class(self) -> str | None:
        """What the reader sees under the crosshair, the most common class in the middle of the frame"""
        if self.classes is None:
            return None
        h, w = self.classes.shape
        patch = self.classes[h // 2 - 2 : h // 2 + 3, w // 2 - 2 : w // 2 + 3].ravel()
        patch = patch[patch != SKY]
        return CLASSES[int(np.bincount(patch).argmax())] if len(patch) else None

    def check_plot(self, state: dict, seed: str) -> dict:
        """Look down at the next plot of the sweep, plant it if the reader sees bare farmland, else move on"""
        plot = self.sweep[0]
        crop = (plot[0], plot[1] + 1, plot[2])
        point = (plot[0] + 0.5, plot[1] + 0.95, plot[2] + 0.5)
        via = place_viewpoint(state, crop, (0, -1, 0))
        self.sweep_steps += 1
        move, arrived = self.fly_to_view(state, via, point)
        yaw_error, pitch_error, _ = aim_errors(state, point)
        aimed = arrived and abs(yaw_error) < AIM_TOLERANCE and abs(pitch_error) < AIM_TOLERANCE
        seen = self.center_class() if aimed else None
        done = crop in self.planted or plot in self.unreachable or self.sweep_steps > SWEEP_LIMIT
        if done or (seen is not None and seen != "minecraft:farmland"):
            self.sweep.pop(0)
            self.sweep_steps = 0
        elif seen == "minecraft:farmland":
            return self.plant(state, plot, seed)
        return self.view(state, move, via, point)

    def survey(self, state: dict) -> dict:
        """Look the field over from a little above each side, so every cell is seen at a steep angle"""
        x0, y0, z0, x1, y1, z1 = self.field(state)
        cx, cz = (x0 + x1 + 1) / 2, (z0 + z1 + 1) / 2
        if self.views is None:
            half = max(x1 - x0, z1 - z0) / 2
            y = y1 + FIELD_VIEW_HEIGHT
            self.views = [(cx + dx * half, y, cz + dz * half) for dx, dz in ((1, 0), (0, 1), (-1, 0), (0, -1))]
        view = self.views[0]
        self.view_steps += 1
        move, _ = self.fly(state, view, standoff=0.0, look_down=FIELD_VIEW_PITCH)
        if math.dist((view[0], view[2]), (state["pos"][0], state["pos"][2])) < 0.8 or self.view_steps > SURVEY_LIMIT:
            yaw_error, pitch_error, _ = aim_errors(state, (cx, y0 + 1, cz))
            move = [0.0, 0.0, float(np.clip(view[1] - state["pos"][1], -1, 1)), np.clip(yaw_error * TURN_GAIN, -1, 1), np.clip(pitch_error * TURN_GAIN, -1, 1)]
            self.dwell_on(state, (cx, y0 + 1, cz))
        return self.view(state, move, view, (cx, y0 + 1, cz))


EXPERTS = {
    "navigate_to": NavigateExpert,
    "dig_block": DigExpert,
    "place_block": PlaceExpert,
    "chest_transfer": ChestTransferExpert,
    "mine_and_deliver": MineDeliverExpert,
    "replicate_build": BuildExpert,
    "harvest_crops": HarvestExpert,
    "harvest_region": HarvestExpert,
}


def make_expert(task: str, mask_ids: dict, depth_max: float = 64.0, reader=None) -> HonestExpert:
    return EXPERTS[task](mask_ids, depth_max, reader)
