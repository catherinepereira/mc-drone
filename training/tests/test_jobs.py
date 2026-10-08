from drone_model.experts.jobs import BuildPlanner, base_name, box_views

MASK_IDS = {"blocks": ["minecraft:stone", "minecraft:bricks"], "entities": ["mcdrone:drone"], "entityBase": 32768}
JOB = {"kind": "copy", "source": [0, 1, 0, 2, 3, 2], "dest": [10, 1, 0, 12, 3, 2], "size": [3, 3, 3]}


def planner() -> BuildPlanner:
    # a stand-in reader, these tests feed memory directly
    return BuildPlanner(MASK_IDS, reader=object())


def remember(p: BuildPlanner, cell, block: str) -> None:
    p.memory.apply_events([{"type": "place", "pos": list(cell), "block": block}])


def test_base_name_drops_ripeness_and_properties():
    assert base_name("minecraft:wheat ripe") == "minecraft:wheat"
    assert base_name("minecraft:oak_stairs[facing=north]") == "minecraft:oak_stairs"


def test_box_views_cover_every_side_and_the_top():
    views = box_views(JOB["source"])
    vias = [v for v, _ in views]
    assert any(v[2] > 3 for v in vias) and any(v[2] < 0 for v in vias)
    assert any(v[0] > 3 for v in vias) and any(v[0] < 0 for v in vias)
    assert any(v[1] > 4 for v in vias)


def test_read_plan_moves_the_source_onto_the_destination():
    p = planner()
    remember(p, (1, 1, 1), "minecraft:bricks")
    remember(p, (1, 2, 1), "minecraft:oak_planks")
    assert p.read_plan(JOB, {}) == {(11, 1, 1): "minecraft:bricks", (11, 2, 1): "minecraft:oak_planks"}
    assert p.read_schematic.get(1, 1, 1) == "minecraft:oak_planks"


def test_placement_prefers_the_block_below_then_sides_with_room_for_the_camera():
    p = planner()
    state = {"pos": [11.5, 3.0, 4.5]}
    remember(p, (11, 0, 1), "minecraft:stone")
    support, face, via = p.placement(state, (11, 1, 1))
    assert (support, face) == ((11, 0, 1), "up") and via[1] > 2
    remember(p, (12, 2, 1), "minecraft:bricks")
    support, face, via = p.placement(state, (11, 2, 1))
    assert (support, face) == ((12, 2, 1), "west") and via[0] < 11
    assert p.placement(state, (5, 5, 5)) is None


def test_trenches_open_a_face_of_every_block():
    p = planner()
    box = [0, 0, 0, 6, 3, 9]
    state = {"pos": [0.5, 10.0, 0.5]}
    p.next_dig(state, box)
    trench = set(p.trenches[tuple(box)])
    for x in range(7):
        for y in range(4):
            for z in range(10):
                if (x, y, z) in trench:
                    continue
                neighbors = [(x + 1, y, z), (x - 1, y, z), (x, y, z + 1), (x, y, z - 1), (x, y + 1, z)]
                outside = x in (0, 6) or z in (0, 9) or y == 3
                assert outside or any(n in trench for n in neighbors), (x, y, z)


def test_scans_fill_memory_and_dig_shafts_to_buried_blocks(tmp_path):
    from mcdrone.schematic import Schematic

    from drone_model.experts.jobs import MinePlanner

    s = Schematic.empty(3, 3, 1)
    s.blocks[:, :, :] = "minecraft:stone"
    s.blocks[0, 0, 1] = "minecraft:diamond_ore"
    (tmp_path / "scans").mkdir()
    s.save(tmp_path / "scans" / "r.schem")
    p = MinePlanner(MASK_IDS, reader=object(), schematics=tmp_path)
    job = {"kind": "mine", "region": [0, 0, 0, 2, 2, 0], "blocks": ["minecraft:diamond_ore"], "scan": {"region": "scans/r.schem"}}
    p.load_scans(job)
    assert p.scanned_in(job["region"], {"minecraft:diamond_ore"}) == [(1, 0, 0)]
    # buried under two layers: the shaft starts at the top of the ore's column
    assert p.shaft_blocker((1, 0, 0), job["region"]) == (1, 2, 0)


def test_scanned_fields_give_ripe_crops_by_age_and_bare_plots(tmp_path):
    from mcdrone.schematic import Schematic

    from drone_model.experts.jobs import HarvestPlanner, block_age, read_scans

    assert block_age("minecraft:wheat[age=7]") == 7
    assert block_age("minecraft:farmland[moisture=7]") is None
    # farmland along x, then a ripe crop, an unripe crop, and air above it
    s = Schematic.empty(3, 2, 1)
    s.blocks[0, 0, :] = "minecraft:farmland[moisture=7]"
    s.blocks[1, 0, 0] = "minecraft:wheat[age=7]"
    s.blocks[1, 0, 1] = "minecraft:wheat[age=3]"
    (tmp_path / "scans").mkdir()
    s.save(tmp_path / "scans" / "f.schem")
    p = HarvestPlanner(MASK_IDS, reader=object(), schematics=tmp_path)
    state = {"job": {"kind": "harvest", "region": [0, 0, 0, 2, 1, 0], "crop": "minecraft:wheat", "scan": {"region": "scans/f.schem"}}}
    p.field_scan = read_scans(state["job"], tmp_path)["region"]
    assert p.ripe_cells(state) == [(0, 1, 0)]
    assert p.sweep_plots(state) == [(2, 0, 0)]
    p.broken.add((0, 1, 0))
    assert p.ripe_cells(state) == []
    assert p.sweep_plots(state) == [(0, 0, 0), (2, 0, 0)]


def test_a_pocket_under_stone_gets_a_shaft_not_a_dig_through_it(tmp_path):
    from mcdrone.schematic import Schematic

    from drone_model.experts.jobs import MinePlanner

    # a column of ore, an air pocket, then stone on top, with stone beside it
    s = Schematic.empty(2, 3, 1)
    s.blocks[:, :, :] = "minecraft:stone"
    s.blocks[0, 0, 0] = "minecraft:coal_ore"
    s.blocks[1, 0, 0] = "minecraft:air"
    (tmp_path / "scans").mkdir()
    s.save(tmp_path / "scans" / "p.schem")
    p = MinePlanner(MASK_IDS, reader=object(), schematics=tmp_path)
    job = {"kind": "mine", "region": [0, 0, 0, 1, 2, 0], "blocks": ["minecraft:coal_ore"], "scan": {"region": "scans/p.schem"}}
    p.load_scans(job)
    p.dig({"pos": [0.5, 5.0, 0.5], "yaw": 0.0, "pitch": 0.0, "camera": {"eyeHeight": 0.2}}, (0, 0, 0), "minecraft:coal_ore", job["region"])
    # the drone works the stone capping the pocket first
    assert p.working_on == (0, 2, 0)


def test_aim_angle_is_small_looking_straight_down_at_any_yaw():
    from drone_model.experts.base import aim_angle

    for yaw in (0.0, 90.0, -137.0):
        state = {"pos": [0.5, 3.0, 0.5], "yaw": yaw, "pitch": 89.5, "camera": {"eyeHeight": 0.2}}
        # a point a hair off the vertical, where the yaw error alone swings wildly
        assert aim_angle(state, (0.51, 0.0, 0.5)) < 1.0
    assert aim_angle({"pos": [0, 0, 0], "yaw": 0.0, "pitch": 0.0, "camera": {"eyeHeight": 0.0}}, (5.0, 0.0, 0.0)) > 80.0


def test_body_clear_needs_room_for_the_whole_drone(tmp_path):
    from drone_model.experts.jobs import MinePlanner

    p = MinePlanner(MASK_IDS, reader=object())
    box = [0, 0, 0, 3, 2, 3]
    # air everywhere except one stone at (1, 2, 1), above the box counts as open
    for x in range(4):
        for y in range(3):
            for z in range(4):
                p.dug.add((x, y, z))
    p.dug.discard((1, 2, 1))
    assert p.body_clear((2.5, 2.5, 2.5), box)
    assert not p.body_clear((1.5, 2.5, 1.5), box)
    # straddling a cell edge touches the stone too
    assert not p.body_clear((2.1, 2.5, 1.5), box)


def test_a_shaft_through_a_block_the_tier_cant_harvest_is_given_up(tmp_path):
    from mcdrone.schematic import Schematic

    from drone_model.experts.jobs import MinePlanner

    # coal buried under diamond ore, which a copper drone would break for nothing
    s = Schematic.empty(2, 3, 1)
    s.blocks[:, :, :] = "minecraft:stone"
    s.blocks[0, 0, 0] = "minecraft:coal_ore"
    s.blocks[2, 0, 0] = "minecraft:diamond_ore"
    (tmp_path / "scans").mkdir()
    s.save(tmp_path / "scans" / "d.schem")
    p = MinePlanner(MASK_IDS, reader=object(), schematics=tmp_path)
    job = {
        "kind": "mine", "region": [0, 0, 0, 1, 2, 0], "blocks": ["minecraft:coal_ore"],
        "scan": {"region": "scans/d.schem"}, "unharvestable": ["minecraft:diamond_ore"],
    }
    p.load_scans(job)
    assert p.wasted((0, 2, 0)) and not p.wasted((1, 2, 0))
    p.dig({"pos": [0.5, 5.0, 0.5], "yaw": 0.0, "pitch": 0.0, "camera": {"eyeHeight": 0.2}}, (0, 0, 0), "minecraft:coal_ore", job["region"])
    assert (0, 0, 0) in p.unreachable


def test_trenches_go_around_blocks_the_tier_cant_harvest(tmp_path):
    from mcdrone.schematic import Schematic

    from drone_model.experts.jobs import MinePlanner

    s = Schematic.empty(3, 1, 1)
    s.blocks[:, :, :] = "minecraft:stone"
    s.blocks[0, 0, 1] = "minecraft:obsidian"
    (tmp_path / "scans").mkdir()
    s.save(tmp_path / "scans" / "t.schem")
    p = MinePlanner(MASK_IDS, reader=object(), schematics=tmp_path)
    p.load_scans({"kind": "mine", "region": [0, 0, 0, 2, 0, 0], "scan": {"region": "scans/t.schem"}, "unharvestable": ["minecraft:obsidian"]})
    state = {"pos": [1.5, 3.0, 0.5]}
    dug = []
    while (cell := p.next_dig(state, [0, 0, 0, 2, 0, 0])) is not None:
        dug.append(cell)
        p.dug.add(cell)
    assert sorted(dug) == [(0, 0, 0), (2, 0, 0)]


def test_gathering_skips_materials_the_tier_cant_harvest():
    p = planner()
    p.unharvestable = frozenset({"minecraft:obsidian"})
    p.plan = {(10, 1, 0): "minecraft:obsidian"}
    p.scanned_boxes = set()
    state = {"pos": [0.5, 5.0, 0.5], "inventory": [], "job": {**JOB, "gather": [20, 0, 0, 22, 2, 2]}}
    assert p.gather(state, list(p.plan)) is None
