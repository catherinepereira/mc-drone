import numpy as np

from drone_model.perception.memory import VoxelMemory
from drone_model.perception.reader import CLASSES, SKY

BRICKS = CLASSES.index("minecraft:bricks")
WHEAT = CLASSES.index("minecraft:wheat")
STATE = {"pos": [0.5, 3.0, 0.5], "yaw": 0.0, "pitch": 90.0, "camera": {"eyeHeight": 0.0, "fov": 40.0, "windowAspect": 1.0}}


def frame(cls_index: int, ripe: float = 0.0):
    """Looking straight down from 3 blocks over a floor whose top is at y 1, so every pixel lands in block y 0"""
    depth = np.full((16, 16), 2.0, np.float32)
    return depth, np.full((16, 16), cls_index, np.uint8), np.full((16, 16), ripe, np.float32)


def test_seen_blocks_become_beliefs_with_changes():
    memory = VoxelMemory()
    depth, cls, ripe = frame(BRICKS)
    changes = memory.observe(STATE, depth, cls, ripe)
    assert changes and all(c.after == "minecraft:bricks" and c.cause == "seen" for c in changes)
    assert memory.label(changes[0].cell) == "minecraft:bricks"
    # seeing the same thing again changes nothing
    assert memory.observe(STATE, depth, cls, ripe) == []


def test_crops_carry_ripeness_and_events_update_without_looking():
    memory = VoxelMemory()
    depth, cls, ripe = frame(WHEAT, ripe=0.9)
    cell = memory.observe(STATE, depth, cls, ripe)[0].cell
    assert memory.label(cell) == "minecraft:wheat ripe"
    broke = memory.apply_events([{"type": "break", "pos": list(cell)}])
    assert broke[0].before == "minecraft:wheat ripe" and broke[0].after == "minecraft:air" and broke[0].cause == "broke"
    placed = memory.apply_events([{"type": "place", "pos": list(cell), "block": "minecraft:wheat"}])
    assert placed[0].after.startswith("minecraft:wheat")


def test_sky_adds_nothing():
    memory = VoxelMemory()
    depth, cls, ripe = frame(SKY)
    assert memory.observe(STATE, depth, cls, ripe) == [] and not memory.cells


def test_harvest_reads_ripe_crops_and_bare_plots_from_memory():
    from drone_model.scripted.arena import HarvestExpert
    from drone_model.perception.memory import Cell

    expert = HarvestExpert({"blocks": [], "entities": ["mcdrone:drone", "minecraft:zombie", "minecraft:cow"], "categories": ["misc", "monster", "creature"], "entityBase": 32768}, reader=object())
    state = {"job": {"region": [0, 0, 0, 1, 1, 0], "crop": "minecraft:wheat"}}

    def believe(cell, name, ripe=0, unripe=0):
        c = expert.memory.cells.setdefault(cell, Cell())
        c.votes[CLASSES.index(name)] = 10
        c.ripe, c.unripe = ripe, unripe

    for x in (0, 1):
        believe((x, 0, 0), "minecraft:farmland")
    believe((0, 1, 0), "minecraft:wheat", ripe=8, unripe=1)
    assert expert.farm_y(state) == 0
    assert expert.ripe_cells(state) == [(0, 1, 0)]
    # the plot under the crop isn't swept, the bare one is
    assert expert.sweep_plots(state) == [(1, 0, 0)]
    expert.memory.cells[(0, 1, 0)].ripe = 0
    assert expert.ripe_cells(state) == []
