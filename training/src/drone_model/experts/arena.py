"""
Scripted experts for the training arenas, used to generate demos and as a reference in evaluation.
They know only the drone's own pose and what its camera has shown (see perception.worldmap.WorldMap):
they explore until the thing they need comes into view, then remember where it was
"""

from __future__ import annotations

import math
from collections import Counter

import numpy as np

from ..perception.blocks import BUILD_PALETTE
from ..perception.memory import VoxelMemory
from ..perception.reader import CLASSES, SKY
from ..perception.worldmap import perceivable
from .goto import GotoPlanner
from .patrol import PatrolPlanner
from .base import (
    AIM_TOLERANCE, SURVEY_DWELL, SURVEY_LIMIT, TURN_GAIN, HonestExpert, aim_angle, aim_errors, carried, center, place_viewpoint, tool_action,
)


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
        if pad is None:
            return self.explore(state)
        # aim at the middle of the pad's top face so the placed block lands on top of it
        point = (pad[0] + 0.5, pad[1] + 1.02, pad[2] + 0.5)
        return self.work_on(state, pad, "place", standoff=1.8, hover=2.5, point=point, expect="minecraft:lime_concrete", place="minecraft:oak_planks")


BUILD_SIZE = 3
# a cell holds a block when it collected this many hits and at least this share of the busiest cell's
MIN_CELL_VOTES = 8
MIN_CELL_SHARE = 0.03
SURVEY_DIST = 4.5


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
        if not carried(state, {block}):
            # out of this block, nothing more to do
            return tool_action([0, 0, 0, 0, 0])
        support = (pos[0], pos[1] - 1, pos[2])
        below = "minecraft:lime_concrete" if support[1] == sy else wanted.get(support)
        point = (pos[0] + 0.5, pos[1] + 0.02, pos[2] + 0.5)
        return self.work_on(state, support, "place", standoff=1.8, hover=2.5, point=point, expect=below, face="up", place=block)

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


class HarvestExpert(HonestExpert):
    """
    Works a harvest job's field from its voxel memory, the block reader's classes and ripeness back-projected into cells.
    Looks the field over from above, then harvests one cycle at a time, break a ripe crop and replant its plot.
    With nothing ripe left it sweeps the plots not known to hold a crop, looks down at each one, and plants where the
    reader sees bare farmland
    """

    def __init__(self, mask_ids: dict, depth_max: float = 64.0, reader=None) -> None:
        if reader is None:
            raise ValueError("the harvest expert judges ripeness with the block reader, pass one")
        super().__init__(mask_ids, depth_max, reader)
        self.memory = VoxelMemory()
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

    def crop(self, state: dict) -> str:
        return state["job"].get("crop", "minecraft:wheat")

    def farm_y(self, state: dict) -> int | None:
        """The farmland's height, where most of the field's farmland cells are"""
        x0, y0, z0, x1, y1, z1 = self.field(state)
        ys = Counter(c[1] for c, label in self.memory.box((x0, y0 - 1, z0), (x1, y1, z1)).items() if label == "minecraft:farmland")
        return ys.most_common(1)[0][0] if ys else None

    def ripe_cells(self, state: dict) -> list[tuple[int, int, int]]:
        farm_y = self.farm_y(state)
        if farm_y is None:
            return []
        x0, _, z0, x1, _, z1 = self.field(state)
        ripe = self.crop(state) + " ripe"
        # crops grow one above the farmland
        cells = self.memory.box((x0, farm_y + 1, z0), (x1, farm_y + 1, z1))
        return [c for c, label in cells.items() if label == ripe and c not in self.refused and c not in self.unreachable]

    def decide(self, state: dict) -> dict:
        for event in state.get("events", []):
            pos = tuple(event.get("pos", ()))
            if event.get("type") == "break":
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
        """The plots of the field the memory doesn't know a crop on, row by row with every other row reversed"""
        x0, y0, z0, x1, y1, z1 = self.field(state)
        farm_y = self.farm_y(state)
        y = farm_y if farm_y is not None else y0
        crop = self.crop(state)
        rows = [[(x, y, z) for z in range(z0, z1 + 1)] for x in range(x0, x1 + 1)]
        ordered = [c for i, row in enumerate(rows) for c in (row if i % 2 == 0 else row[::-1])]
        return [c for c in ordered if (c[0], y + 1, c[2]) not in self.planted and not self.memory.label((c[0], y + 1, c[2])).startswith(crop)]

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
        aimed = arrived and aim_angle(state, point) < AIM_TOLERANCE
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
    "patrol_area": PatrolPlanner,
    "hunt_mobs": PatrolPlanner,
    "goto_point": GotoPlanner,
    "find_block": GotoPlanner,
    "follow_mob": GotoPlanner,
}


def make_expert(task: str, mask_ids: dict, depth_max: float = 64.0, reader=None) -> HonestExpert:
    return EXPERTS[task](mask_ids, depth_max, reader)
