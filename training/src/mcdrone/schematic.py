"""
Sponge Schematic (.schem) files, the format WorldEdit and the mod use. Writes version 3, reads versions 2 and 3.
Blocks are kept as block state strings such as "minecraft:oak_stairs[facing=north]", in a (height, length, width)
array so blocks[y, z, x] matches the format's x + z * width + y * width * length order
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

import nbtlib
import numpy as np

VERSION = 3
AIR = "minecraft:air"


@dataclass
class Schematic:
    blocks: np.ndarray  # (height, length, width) of block state strings
    data_version: int = 0

    @property
    def size(self) -> tuple[int, int, int]:
        """width (x), height (y), length (z)"""
        h, l, w = self.blocks.shape
        return w, h, l

    def get(self, x: int, y: int, z: int) -> str:
        return str(self.blocks[y, z, x])

    @classmethod
    def empty(cls, width: int, height: int, length: int, data_version: int = 0) -> Schematic:
        return cls(np.full((height, length, width), AIR, dtype=object), data_version)

    def save(self, path: Path | str) -> None:
        palette: dict[str, int] = {}
        ids = [palette.setdefault(str(b), len(palette)) for b in self.blocks.ravel()]
        w, h, l = self.size
        schematic = nbtlib.Compound(
            {
                "Version": nbtlib.Int(VERSION),
                "DataVersion": nbtlib.Int(self.data_version),
                "Width": nbtlib.Short(w),
                "Height": nbtlib.Short(h),
                "Length": nbtlib.Short(l),
                "Offset": nbtlib.IntArray([0, 0, 0]),
                "Blocks": nbtlib.Compound(
                    {
                        "Palette": nbtlib.Compound({name: nbtlib.Int(i) for name, i in palette.items()}),
                        "Data": nbtlib.ByteArray(np.frombuffer(_varints(ids), dtype=np.int8)),
                        "BlockEntities": nbtlib.List[nbtlib.Compound](),
                    }
                ),
            }
        )
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        nbtlib.File({"Schematic": schematic}, gzipped=True).save(path)


def load(path: Path | str) -> Schematic:
    root = nbtlib.load(path)
    tag = root["Schematic"] if "Schematic" in root else root
    version = int(tag.get("Version", 0))
    w, h, l = (int(tag[k]) & 0xFFFF for k in ("Width", "Height", "Length"))
    if version >= 3:
        palette, data = tag["Blocks"]["Palette"], tag["Blocks"]["Data"]
    elif version == 2:
        palette, data = tag["Palette"], tag["BlockData"]
    else:
        raise ValueError(f"{path} is not a Sponge schematic (version {version})")
    names = {int(i): str(name) for name, i in palette.items()}
    ids = _read_varints(bytes(np.asarray(data, dtype=np.int8).astype(np.uint8)))
    if len(ids) != w * h * l:
        raise ValueError(f"{path} has {len(ids)} blocks for a {w}x{h}x{l} box")
    blocks = np.array([names.get(i, AIR) for i in ids], dtype=object).reshape(h, l, w)
    return Schematic(blocks, int(tag.get("DataVersion", 0)))


def _varints(values: list[int]) -> bytes:
    out = bytearray()
    for v in values:
        while v & ~0x7F:
            out.append((v & 0x7F) | 0x80)
            v >>= 7
        out.append(v)
    return bytes(out)


def _read_varints(data: bytes) -> list[int]:
    out, value, shift = [], 0, 0
    for b in data:
        value |= (b & 0x7F) << shift
        if b & 0x80:
            shift += 7
        else:
            out.append(value)
            value, shift = 0, 0
    return out
