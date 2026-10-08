"""In-process stand-in for the mod's bridge, enough of the protocol to exercise the client and env"""

from __future__ import annotations

import json
import math
import threading
from typing import Any

import numpy as np
from websockets.sync.server import Server, ServerConnection, serve

from mcdrone.protocol import SCHEMA, Observation, encode_obs


class FakeBridge:
    def __init__(self, width: int = 32, height: int = 24) -> None:
        self.width = width
        self.height = height
        self.mode = "realtime"
        self.record_armed = False
        self.config: dict[str, Any] = {"width": width, "height": height, "streams": ["rgb", "depth", "mask"]}
        self.pos = np.zeros(3)
        self.yaw = 0.0
        self.marker = np.array([0.0, 0.0, 5.0])
        self.step_count = 0
        self.seq = 0
        self.received: list[dict[str, Any]] = []
        self._server: Server | None = None
        self._thread: threading.Thread | None = None

    @property
    def port(self) -> int:
        assert self._server is not None
        return self._server.socket.getsockname()[1]

    def __enter__(self) -> FakeBridge:
        self._server = serve(self._handle, "127.0.0.1", 0)
        self._thread = threading.Thread(target=self._server.serve_forever, daemon=True)
        self._thread.start()
        return self

    def __exit__(self, *exc: object) -> None:
        assert self._server is not None
        self._server.shutdown()

    def _status(self) -> dict[str, Any]:
        return {"type": "status", "mode": self.mode, "recordArmed": self.record_armed, "controller": "test"}

    def _obs(self, reply_to: int | None) -> bytes:
        self.seq += 1
        dist = float(np.linalg.norm(self.marker - self.pos))
        done = dist < 1.5 or self.step_count >= 50
        obs = Observation(
            seq=self.seq,
            tick=self.step_count,
            state={
                "pos": self.pos.tolist(),
                "vel": [0, 0, 0],
                "yaw": self.yaw,
                "pitch": 0.0,
                "marker": self.marker.astype(int).tolist(),
                "inventory": [["minecraft:coal", 3]] + [["minecraft:air", 0]] * 26,
                "selectedSlot": 0,
                "container": None,
                "breaking": None,
            },
            episode={"id": "fake", "task": "navigate_to", "step": self.step_count, "reward": 0.1, "done": done, "success": dist < 1.5, "truncated": done and dist >= 1.5},
            action=None,
            reply_to=reply_to,
            rgb=np.full((self.height, self.width, 3), self.step_count % 255, dtype=np.uint8),
            depth=np.full((self.height, self.width), dist, dtype=np.float32) if "depth" in self.config["streams"] else None,
            mask=np.ones((self.height, self.width), dtype=np.uint16) if "mask" in self.config["streams"] else None,
        )
        return encode_obs(obs)

    def _handle(self, ws: ServerConnection) -> None:
        for raw in ws:
            msg = json.loads(raw)
            self.received.append(msg)
            kind = msg["type"]
            msg_id = msg.get("id")
            if kind == "hello":
                if msg.get("schema") != SCHEMA:
                    ws.send(json.dumps({"type": "error", "message": "schema mismatch"}))
                    continue
                ws.send(json.dumps({"type": "welcome", "role": msg["role"], "schema": SCHEMA, "config": self.config, "status": self._status(), "maskIds": {"blocks": [], "entities": ["mcdrone:drone"], "entityBase": 32768}, "itemIds": ["minecraft:air", "minecraft:coal"]}))
            elif kind == "configure":
                if "mode" in msg:
                    self.mode = msg["mode"]
                for key in ("width", "height", "streams"):
                    if key in msg:
                        self.config[key] = msg[key]
                self.width, self.height = self.config["width"], self.config["height"]
                ws.send(json.dumps(self._status()))
            elif kind == "record":
                self.record_armed = msg["on"]
                ws.send(json.dumps(self._status()))
            elif kind == "reset":
                self.pos = np.zeros(3)
                self.yaw = 0.0
                self.step_count = 0
                ws.send(self._obs(msg_id))
            elif kind == "step":
                if self.mode != "lockstep":
                    ws.send(json.dumps({"type": "error", "message": "not lockstep", "replyTo": msg_id}))
                    continue
                forward = msg["action"]["move"][0]
                self.yaw += msg["action"]["look"][0]
                self.pos += np.array([-math.sin(math.radians(self.yaw)), 0.0, math.cos(math.radians(self.yaw))]) * forward * 0.4
                self.step_count += 1
                ws.send(self._obs(msg_id))
            elif kind == "ping":
                ws.send(json.dumps({"type": "pong", "replyTo": msg_id, "tick": 0}))
