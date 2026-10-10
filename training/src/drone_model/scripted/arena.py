"""
Scripted experts for the training arenas, used to generate demos and as a reference in evaluation.
They know only the drone's own pose and what its camera has shown, through the drone's perception core
(perception.core): they explore until the block they need is in its memory, then go to it
"""

from __future__ import annotations

import math
from collections import Counter

import numpy as np

from ..perception.reader import CLASSES, SKY
from .base import (
    AIM_TOLERANCE, SURVEY_LIMIT, TURN_GAIN, Planner, aim_angle, aim_errors, carried, center, place_viewpoint, tool_action,
)


class NavigateExpert(Planner):
    def decide(self, state: dict) -> dict:
        marker = self.nearest(state, self.known("mcdrone:marker"))
        if marker is None:
            return self.explore(state)
        move, _ = self.fly(state, center(marker), standoff=0.0, column=(marker[0], marker[2]))
        return tool_action(move)


class DigExpert(Planner):
    def decide(self, state: dict) -> dict:
        ore = self.nearest(state, self.known("minecraft:coal_ore"))
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


class MineDeliverExpert(Planner, ContainerMixin):
    def decide(self, state: dict) -> dict:
        ore = self.nearest(state, self.known("minecraft:coal_ore"))
        if ore is not None:
            if state.get("container"):
                return tool_action([0, 0, 0, 0, 0], "close")
            return self.work_on(state, ore, "break", standoff=2.5, hover=0.9, expect="minecraft:coal_ore")
        chest = self.nearest(state, self.known("minecraft:chest"))
        coal = carried(state, {"minecraft:coal"})
        # drop off what's in hand, then keep exploring in case more ore is out of sight
        if chest is not None and (coal or state.get("container")):
            return self.deliver(state, chest, {"minecraft:coal"})
        return self.explore(state)


class ChestTransferExpert(Planner, ContainerMixin):
    """Opens chests to learn which one holds the items, empties it, and fills the other"""

    def __init__(self, mask_ids: dict, depth_max: float = 64.0, reader=None, core=None) -> None:
        super().__init__(mask_ids, depth_max, reader, core=core)
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
        chests = self.known("minecraft:chest")
        target = next((c for c in chests if self.roles.get(c) == "target"), None)
        if carried(state) and target is not None:
            return self.deliver(state, target, None)
        unknown = self.nearest(state, [c for c in chests if c not in self.roles])
        if unknown is not None:
            return self.work_on(state, unknown, "open", standoff=2.0, hover=0.8, expect="minecraft:chest")
        return self.explore(state)


class PlaceExpert(Planner):
    def decide(self, state: dict) -> dict:
        pad = self.nearest(state, self.known("minecraft:lime_concrete"))
        if pad is None:
            return self.explore(state)
        # aim at the middle of the pad's top face so the placed block lands on top of it
        point = (pad[0] + 0.5, pad[1] + 1.02, pad[2] + 0.5)
        return self.work_on(state, pad, "place", standoff=1.8, hover=2.5, point=point, expect="minecraft:lime_concrete", place="minecraft:oak_planks")


FIELD_VIEW_HEIGHT = 5.0
FIELD_VIEW_PITCH = 60.0
# steps spent looking for one plot on a sweep, and sweeps before the expert only watches for ripe crops
SWEEP_LIMIT = 60
MAX_SWEEPS = 2


class HarvestExpert(Planner):
    """
    Works a harvest job's field from its voxel memory, the block reader's classes and ripeness back-projected into cells.
    Looks the field over from above, then harvests one cycle at a time, break a ripe crop and replant its plot.
    With nothing ripe left it sweeps the plots not known to hold a crop, looks down at each one, and plants where the
    reader sees bare farmland
    """

    def __init__(self, mask_ids: dict, depth_max: float = 64.0, reader=None, core=None, schematics=None) -> None:
        if reader is None:
            raise ValueError("the harvest expert judges ripeness with the block reader, pass one")
        super().__init__(mask_ids, depth_max, reader, core=core, schematics=schematics)
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
        patch = self.core.crosshair(5)[2].ravel()
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
}


def make_expert(task: str, mask_ids: dict, depth_max: float = 64.0, reader=None, core=None) -> Planner:
    return EXPERTS[task](mask_ids, depth_max, reader, core=core)
