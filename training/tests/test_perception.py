import math

import numpy as np

from drone_model.perception.worldmap import DRONE_FRAMES, WorldMap

MASK_IDS = {"blocks": ["minecraft:stone"], "entities": ["mcdrone:drone", "minecraft:zombie", "minecraft:cow"], "categories": ["misc", "monster", "creature"], "entityBase": 32768}


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


def test_hostile_pixels_become_one_tracked_mob_until_the_camera_sees_past_it():
    from drone_model.perception.mobs import GONE_LOOKS, MobTracker, hostile_ids

    tracker = MobTracker(hostile_ids(MASK_IDS))
    state = {"pos": [0.5, 3.0, 0.5], "yaw": 0.0, "pitch": 0.0, "camera": {"eyeHeight": 0.0, "fov": 70.0, "windowAspect": 1.0}}
    depth = np.full((16, 16), 64.0, np.float32)
    mask = np.zeros((16, 16), np.int64)
    # a zombie straight ahead, a cow beside it
    depth[6:10, 6:10] = 4.0
    mask[6:10, 6:10] = 32769
    depth[6:10, 12:16] = 4.0
    mask[6:10, 12:16] = 32770
    tracker.update(state, depth, mask, 64.0)
    assert len(tracker.mobs) == 1
    assert np.allclose(tracker.mobs[0].pos, [0.5, 3.0, 4.5], atol=0.5)
    for _ in range(GONE_LOOKS):
        tracker.update(state, np.full((16, 16), 64.0, np.float32), np.zeros((16, 16), np.int64), 64.0)
    assert not tracker.mobs


def test_project_lands_on_the_pixel_backproject_came_from():
    from drone_model.perception.worldmap import backproject, project

    state = {"pos": [2.0, 5.0, -1.0], "yaw": 30.0, "pitch": 20.0, "camera": {"eyeHeight": 0.2, "fov": 70.0, "windowAspect": 1.6}}
    depth = np.full((24, 32), 6.0, np.float32)
    view = backproject(state, depth, 64.0, stride=1)
    row, col, z = project(state, view.points[5 * 32 + 7], depth.shape)
    assert (row, col) == (5, 7) and abs(z - 6.0) < 1e-6


def test_reader_labels_monsters_hostile_and_other_mobs_passive():
    from drone_model.perception.reader import HOSTILE, PASSIVE, SKY, entity_table

    names = ["mcdrone:drone", "minecraft:zombie", "minecraft:cow", "minecraft:item"]
    table = entity_table(names, {"minecraft:zombie": "monster", "minecraft:cow": "creature", "minecraft:item": "misc"})
    assert list(table) == [SKY, HOSTILE, PASSIVE, SKY]
