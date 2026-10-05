import numpy as np

from mcdrone import schematic


def test_round_trip(tmp_path):
    s = schematic.Schematic.empty(3, 2, 4, data_version=5023)
    s.blocks[0, 1, 2] = "minecraft:bricks"
    s.blocks[1, 3, 0] = "minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]"
    path = tmp_path / "t.schem"
    s.save(path)
    back = schematic.load(path)
    assert back.size == (3, 2, 4)
    assert back.get(2, 0, 1) == "minecraft:bricks"
    assert back.get(0, 1, 3).startswith("minecraft:oak_stairs[")
    assert np.array_equal(back.blocks, s.blocks)


def test_large_palette_uses_multibyte_varints(tmp_path):
    s = schematic.Schematic.empty(16, 1, 16)
    for i in range(200):
        s.blocks[0, i // 16, i % 16] = f"minecraft:block_{i}"
    s.save(tmp_path / "big.schem")
    assert np.array_equal(schematic.load(tmp_path / "big.schem").blocks, s.blocks)
