"""Wire format shared with docs/PROTOCOL.md"""

from __future__ import annotations

import json
import os
import struct
from dataclasses import dataclass, field
from typing import Any

import numpy as np

# must match ClientRuntime.SCHEMA in the mod
SCHEMA = 2
DEFAULT_PORT = 8318
# containers reach the game on the host through host.docker.internal
DEFAULT_HOST = os.environ.get("MCDRONE_HOST", "127.0.0.1")

_DTYPES = {"uint8": np.uint8, "float32": np.float32, "uint16": np.uint16}


@dataclass
class Observation:
    seq: int
    tick: int
    state: dict[str, Any]
    episode: dict[str, Any] | None
    action: dict[str, Any] | None
    reply_to: int | None
    rgb: np.ndarray | None = None
    depth: np.ndarray | None = None
    mask: np.ndarray | None = None
    # the "state" stream, 1 + block state id per pixel, labels for training the block reader
    block_states: np.ndarray | None = None
    # the third-person view from behind the drone, at its own size, for videos
    chase: np.ndarray | None = None
    header: dict[str, Any] = field(default_factory=dict, repr=False)

    @property
    def done(self) -> bool:
        return bool(self.episode and self.episode.get("done"))

    @property
    def reward(self) -> float:
        return float(self.episode["reward"]) if self.episode else 0.0


def decode_obs(data: bytes) -> Observation:
    (header_len,) = struct.unpack_from("<I", data, 0)
    header = json.loads(data[4 : 4 + header_len].decode("utf-8"))
    payload = memoryview(data)[4 + header_len :]
    arrays: dict[str, np.ndarray] = {}
    for name, spec in header.get("streams", {}).items():
        dtype = np.dtype(_DTYPES[spec["dtype"]]).newbyteorder("<")
        start = spec["offset"]
        raw = payload[start : start + spec["length"]]
        arrays[name] = np.frombuffer(raw, dtype=dtype).reshape(spec["shape"]).copy()
    return Observation(
        seq=header["seq"],
        tick=header["tick"],
        state=header.get("state") or {},
        episode=header.get("episode"),
        action=header.get("action"),
        reply_to=header.get("replyTo"),
        rgb=arrays.get("rgb"),
        depth=arrays.get("depth"),
        mask=arrays.get("mask"),
        block_states=arrays.get("state"),
        chase=arrays.get("chase"),
        header=header,
    )


def encode_obs(obs: Observation) -> bytes:
    """Inverse of decode_obs, used by the fake bridge in tests"""
    parts: list[bytes] = []
    streams: dict[str, Any] = {}
    offset = 0
    for name, arr, dtype in (
        ("rgb", obs.rgb, "uint8"),
        ("depth", obs.depth, "float32"),
        ("mask", obs.mask, "uint16"),
        ("state", obs.block_states, "uint16"),
        ("chase", obs.chase, "uint8"),
    ):
        if arr is None:
            continue
        raw = np.ascontiguousarray(arr, dtype=np.dtype(_DTYPES[dtype]).newbyteorder("<")).tobytes()
        streams[name] = {"offset": offset, "length": len(raw), "shape": list(arr.shape), "dtype": dtype}
        parts.append(raw)
        offset += len(raw)
    header = {
        "type": "obs",
        "seq": obs.seq,
        "tick": obs.tick,
        "replyTo": obs.reply_to,
        "state": obs.state,
        "episode": obs.episode,
        "action": obs.action,
        "streams": streams,
    }
    header_bytes = json.dumps(header).encode("utf-8")
    return struct.pack("<I", len(header_bytes)) + header_bytes + b"".join(parts)


TOOLS = ("none", "break", "place", "open", "close")


def action(
    forward: float = 0.0,
    right: float = 0.0,
    up: float = 0.0,
    yaw: float = 0.0,
    pitch: float = 0.0,
    tool: str = "none",
    slot: int = 0,
    transfer: dict[str, Any] | None = None,
    block: str | None = None,
) -> dict[str, Any]:
    """
    transfer is a dict with from ("drone" or "container") and slot, plus optional toSlot and count.
    block names the block to place, such as "minecraft:bricks", and overrides slot: the drone uses whichever slot holds it
    """
    out = {"move": [forward, right, up], "look": [yaw, pitch], "tool": tool, "slot": slot, "transfer": transfer}
    if block:
        out["block"] = block
    return out
