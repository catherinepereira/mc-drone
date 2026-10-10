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
    # seen again, a chase keeps the same mob
    first = tracker.mobs[0]
    tracker.update(state, depth, mask, 64.0)
    assert tracker.mobs == [first]
    for _ in range(GONE_LOOKS):
        tracker.update(state, np.full((16, 16), 64.0, np.float32), np.zeros((16, 16), np.int64), 64.0)
    assert not tracker.mobs


def test_a_mob_read_on_a_block_the_map_knows_is_solid_is_a_misread():
    from drone_model.perception.mobs import MobTracker, hostile_ids

    state = {"pos": [0.5, 3.0, 0.5], "yaw": 0.0, "pitch": 0.0, "camera": {"eyeHeight": 0.0, "fov": 70.0, "windowAspect": 1.0}}
    depth = np.full((16, 16), 64.0, np.float32)
    mask = np.zeros((16, 16), np.int64)
    depth[6:10, 6:10] = 4.0
    mask[6:10, 6:10] = 32769
    # the zombie pixels land on the face of the block 4 ahead
    stone = {(0, 3, 4), (0, 2, 4), (0, 4, 4), (-1, 3, 4), (1, 3, 4)}
    on_stone = MobTracker(hostile_ids(MASK_IDS))
    on_stone.update(state, depth, mask, 64.0, solid=lambda cell: cell in stone)
    assert not on_stone.mobs
    in_the_open = MobTracker(hostile_ids(MASK_IDS))
    in_the_open.update(state, depth, mask, 64.0, solid=lambda cell: False)
    assert len(in_the_open.mobs) == 1
    # tracked before the map had votes for the block, gone once it has them
    in_the_open.update(state, np.full((16, 16), 64.0, np.float32), np.zeros((16, 16), np.int64), 64.0, solid=lambda cell: cell in stone)
    assert not in_the_open.mobs


def test_memory_calls_a_voted_block_solid_and_a_crop_not():
    from drone_model.perception.memory import MIN_VOTES, Cell, VoxelMemory
    from drone_model.perception.reader import INDEX

    memory = VoxelMemory()
    for key, block in (((0, 0, 0), "minecraft:stone_bricks"), ((1, 0, 0), "minecraft:wheat")):
        cell = Cell()
        cell.votes[INDEX[block]] = MIN_VOTES
        memory.cells[key] = cell
    assert memory.solid((0, 0, 0))
    assert not memory.solid((1, 0, 0))
    assert not memory.solid((2, 0, 0))


def test_a_mob_whose_spot_now_reads_as_a_block_goes_after_a_few_looks():
    from drone_model.perception.mobs import GONE_LOOKS, MobTracker, hostile_ids

    tracker = MobTracker(hostile_ids(MASK_IDS))
    state = {"pos": [0.5, 3.0, 0.5], "yaw": 0.0, "pitch": 0.0, "camera": {"eyeHeight": 0.0, "fov": 70.0, "windowAspect": 1.0}}
    depth = np.full((16, 16), 64.0, np.float32)
    mask = np.zeros((16, 16), np.int64)
    depth[6:10, 6:10] = 4.0
    mask[6:10, 6:10] = 32769
    tracker.update(state, depth, mask, 64.0)
    assert len(tracker.mobs) == 1
    # the zombie walked off, the camera stops on the block that was behind it at nearly the same depth
    mask[6:10, 6:10] = 5
    depth[6:10, 6:10] = 4.6
    for _ in range(GONE_LOOKS):
        tracker.update(state, depth, mask, 64.0)
    assert not tracker.mobs


def test_project_lands_on_the_pixel_backproject_came_from():
    from drone_model.perception.worldmap import backproject, project

    state = {"pos": [2.0, 5.0, -1.0], "yaw": 30.0, "pitch": 20.0, "camera": {"eyeHeight": 0.2, "fov": 70.0, "windowAspect": 1.6}}
    depth = np.full((24, 32), 6.0, np.float32)
    view = backproject(state, depth, 64.0, stride=1)
    row, col, z = project(state, view.points[5 * 32 + 7], depth.shape)
    assert (row, col) == (5, 7) and abs(z - 6.0) < 1e-6


def test_reader_labels_each_mob_kind_and_nothing_else():
    from drone_model.perception.reader import INDEX, SKY, entity_table

    names = ["mcdrone:drone", "minecraft:zombie", "minecraft:cow", "minecraft:item", "minecraft:spider"]
    assert list(entity_table(names)) == [SKY, INDEX["minecraft:zombie"], INDEX["minecraft:cow"], SKY, SKY]


def test_a_zombie_beside_a_cow_stays_two_mobs_of_their_kinds():
    from drone_model.perception.mobs import MobTracker, mob_ids

    tracker = MobTracker(mob_ids(MASK_IDS))
    state = {"pos": [0.5, 3.0, 0.5], "yaw": 0.0, "pitch": 0.0, "camera": {"eyeHeight": 0.0, "fov": 70.0, "windowAspect": 1.0}}
    depth = np.full((16, 16), 64.0, np.float32)
    mask = np.zeros((16, 16), np.int64)
    depth[6:10, 4:8] = 4.0
    mask[6:10, 4:8] = 32769
    depth[6:10, 8:12] = 4.0
    mask[6:10, 8:12] = 32770
    tracker.update(state, depth, mask, 64.0)
    assert sorted(m.kind for m in tracker.mobs) == [32769, 32770]


def test_prey_picks_hostile_mobs_every_mob_or_named_kinds():
    from drone_model.perception.mobs import prey_ids

    assert prey_ids("hostile", MASK_IDS) == {32769}
    assert prey_ids(None, MASK_IDS) == {32769}
    assert prey_ids("all", MASK_IDS) == {32769, 32770}
    assert prey_ids(["minecraft:cow"], MASK_IDS) == {32770}
    assert prey_ids(["cow"], MASK_IDS) == {32770}


def test_a_misread_block_gives_way_once_later_reads_outvote_it():
    from drone_model.perception.memory import MIN_VOTES, Cell, VoxelMemory
    from drone_model.perception.reader import INDEX

    memory = VoxelMemory()
    cell = Cell()
    cell.votes[INDEX["minecraft:cobblestone"]] = MIN_VOTES
    memory.cells[(3, 1, 4)] = cell
    memory.touch((3, 1, 4))
    assert memory.cells_of("minecraft:cobblestone") == [(3, 1, 4)]
    cell.votes[INDEX["minecraft:stone"]] = 2 * MIN_VOTES
    memory.touch((3, 1, 4))
    assert memory.cells_of("minecraft:cobblestone") == []
    assert memory.cells_of("minecraft:stone") == [(3, 1, 4)]


def test_planners_built_on_one_core_share_its_memory_map_and_mobs():
    from drone_model.perception.core import DroneCore
    from drone_model.scripted.goto import GotoPlanner
    from drone_model.scripted.patrol import PatrolPlanner

    core = DroneCore(MASK_IDS)
    goto, patrol = GotoPlanner(MASK_IDS, core=core), PatrolPlanner(MASK_IDS, core=core)
    assert goto.memory is patrol.memory is core.memory
    assert goto.map is patrol.map is core.map
    assert goto.mobs is patrol.mobs is core.mobs


def test_a_ground_truth_frame_fills_the_memory_and_the_mob_tracker():
    from drone_model.perception.core import DroneCore

    core = DroneCore(MASK_IDS)
    state = {"pos": [0.5, 3.0, 0.5], "yaw": 0.0, "pitch": 0.0, "camera": {"eyeHeight": 0.0, "fov": 70.0, "windowAspect": 1.0}}
    depth = np.full((16, 16), 6.0, np.float32)
    mask = np.ones((16, 16), np.int64)
    # a zombie in front of a stone wall
    depth[6:10, 6:10] = 4.0
    mask[6:10, 6:10] = 32769
    for _ in range(8):
        core.see(state, {"depth": depth, "mask": mask})
    assert core.memory.label((4, 7, 6)) == "minecraft:stone"
    assert len(core.mobs.mobs) == 1
