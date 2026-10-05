from drone_model.jobs import BuildPlanner, base_name, box_views

MASK_IDS = {"blocks": ["minecraft:stone", "minecraft:bricks"]}
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
    assert p.read_plan(JOB) == {(11, 1, 1): "minecraft:bricks", (11, 2, 1): "minecraft:oak_planks"}
    assert p.read_schematic.get(1, 1, 1) == "minecraft:oak_planks"


def test_support_prefers_the_block_below_then_sides():
    p = planner()
    remember(p, (11, 0, 1), "minecraft:stone")
    assert p.support((11, 1, 1)) == ((11, 0, 1), "up")
    remember(p, (12, 2, 1), "minecraft:bricks")
    assert p.support((11, 2, 1)) == ((12, 2, 1), "west")
    assert p.support((5, 5, 5)) is None
