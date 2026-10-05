import math

import numpy as np

from drone_model.perception import WorldMap

MASK_IDS = {"blocks": ["minecraft:stone"]}


def look(map_: WorldMap, pitch: float, depth: float, eye_y: float = 3.0) -> None:
    """One frame from (0.5, eye_y, 0.5) facing +z, every pixel hitting stone at the same depth"""
    state = {"pos": [0.5, eye_y, 0.5], "yaw": 0.0, "pitch": pitch, "camera": {"eyeHeight": 0.0, "fov": 70.0, "windowAspect": 1.0}}
    map_.update(state, np.full((16, 16), depth, np.float32), np.ones((16, 16), np.uint8), 64.0)


def test_roof_seen_from_below_is_a_ceiling():
    map_ = WorldMap(MASK_IDS)
    look(map_, pitch=-60.0, depth=4.0)
    assert map_.ceilings and not map_.heights
    map_.fly_y = 2.0
    assert not any(map_.blocked(c) for c in map_.ceilings)
    map_.fly_y = min(map_.ceilings.values())
    assert all(map_.blocked(c) for c in map_.ceilings if map_.ceilings[c] == map_.fly_y)


def test_ceiling_under_a_known_floor_is_ignored():
    map_ = WorldMap(MASK_IDS)
    map_.ceilings[(0, 3)] = 4.0
    map_.heights[(0, 3)] = 9.0
    assert map_.ceiling((0, 3)) == math.inf
    map_.fly_y = 10.0
    assert not map_.blocked((0, 3))
    map_.fly_y = 5.0
    assert map_.blocked((0, 3))


def test_headroom_reads_the_column_overhead():
    map_ = WorldMap(MASK_IDS)
    map_.ceilings[(0, 0)] = 7.0
    map_.ceilings[(3, 3)] = 4.0
    assert map_.headroom(0.5, 0.5) == 7.0
    assert map_.headroom(1.5, 1.5) == math.inf
