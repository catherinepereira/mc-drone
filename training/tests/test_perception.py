import math

import numpy as np

from drone_model.perception.worldmap import DRONE_FRAMES, WorldMap

MASK_IDS = {"blocks": ["minecraft:stone"], "entities": ["mcdrone:drone"], "entityBase": 32768}


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


def test_a_seen_drone_blocks_its_column_for_a_while():
    map_ = WorldMap(MASK_IDS)
    state = {"pos": [0.5, 3.0, 0.5], "yaw": 0.0, "pitch": 0.0, "camera": {"eyeHeight": 0.0, "fov": 70.0, "windowAspect": 1.0}}
    drone = np.full((16, 16), 32768, np.int64)
    map_.update(state, np.full((16, 16), 3.0, np.float32), drone, 64.0)
    assert map_.drones and not map_.heights and not map_.ceilings
    map_.fly_y = 3.0
    ahead = (0, 3)
    assert ahead in map_.drones and map_.blocked(ahead) and not map_.free(0.5, 3.5)
    # out of the way above or below the flying height
    map_.fly_y = 9.0
    assert not map_.blocked(ahead)
    map_.fly_y = 3.0
    for _ in range(DRONE_FRAMES):
        map_.update(state, np.full((16, 16), 64.0, np.float32), np.zeros((16, 16), np.int64), 64.0)
    assert not map_.blocked(ahead)
