"""Synchronous bridge client"""

from __future__ import annotations

import itertools
import json
import time
import urllib.request
from pathlib import Path
from typing import Any

from websockets.sync.client import ClientConnection, connect

from .logs import JsonlLogger
from .protocol import DEFAULT_HOST, DEFAULT_PORT, SCHEMA, Observation, decode_obs


class BridgeError(RuntimeError):
    pass


class DroneClient:
    """
    Talks to the mod over ws://127.0.0.1:8318/ws.
    One controller at a time, connect as an observer to only watch
    """

    def __init__(
        self,
        host: str = DEFAULT_HOST,
        port: int = DEFAULT_PORT,
        role: str = "controller",
        timeout: float = 30.0,
        log_dir: Path | None = None,
        client_name: str = "mcdrone-py",
    ) -> None:
        self.url = f"ws://{host}:{port}/ws"
        self.http = f"http://{host}:{port}"
        self.requested_role = role
        self.timeout = timeout
        self.client_name = client_name
        self.log = JsonlLogger(log_dir)
        self.role: str | None = None
        self.config: dict[str, Any] = {}
        self.status: dict[str, Any] = {}
        self.mask_ids: dict[str, Any] = {}
        self.item_ids: list[str] = []
        self.metrics: dict[str, Any] = {}
        self.logs: list[dict[str, Any]] = []
        self._ids = itertools.count(1)
        self._ws: ClientConnection | None = None
        self._obs_backlog: list[Observation] = []
        self._state_names: list[str] | None = None

    @property
    def state_names(self) -> list[str]:
        """Block state names by id, such as "minecraft:wheat[age=7]", the state stream holds 1 + id. Fetched once"""
        if self._state_names is None:
            with urllib.request.urlopen(f"{self.http}/api/states", timeout=self.timeout) as r:
                self._state_names = json.loads(r.read().decode("utf-8"))
        return self._state_names

    def publish_memory(self, step: int, changes: list[dict], snapshot: list | None = None, focus: list[int] | None = None) -> None:
        """
        Sends the drone's voxel memory to the dashboards: changes are {"cell", "before", "after", "cause"} for this
        step, snapshot (every cell as [x, y, z, label]) lets a dashboard that joined late catch up, focus is the box
        [x0, y0, z0, x1, y1, z1] worth showing
        """
        msg: dict[str, Any] = {"type": "memory", "step": step, "changes": changes}
        if snapshot is not None:
            msg["snapshot"] = snapshot
        if focus is not None:
            msg["focus"] = focus
        self._send(msg)

    def __enter__(self) -> DroneClient:
        self.connect()
        return self

    def __exit__(self, *exc: object) -> None:
        self.close()

    def connect(self) -> None:
        # the mod rejects browser origins, a script sends none
        self._ws = connect(self.url, open_timeout=self.timeout, max_size=None, origin=None, proxy=None, legacy=True)
        self._send({"type": "hello", "role": self.requested_role, "schema": SCHEMA, "client": self.client_name})
        welcome = self._wait_text("welcome")
        self.role = welcome["role"]
        self.config = welcome["config"]
        self.status = welcome["status"]
        self.mask_ids = welcome["maskIds"]
        self.item_ids = welcome.get("itemIds", [])
        self.log.info("bridge.connected", url=self.url, role=self.role)
        if self.requested_role == "controller" and self.role != "controller":
            raise BridgeError("another client holds the controller role")

    def close(self) -> None:
        if self._ws is not None:
            self._ws.close()
            self._ws = None
        self.log.info("bridge.closed")
        self.log.close()

    # ---- commands ----

    def subscribe(self, obs: bool | None = None, logs: bool | None = None, metrics: bool | None = None) -> None:
        msg: dict[str, Any] = {"type": "subscribe"}
        for key, value in (("obs", obs), ("logs", logs), ("metrics", metrics)):
            if value is not None:
                msg[key] = value
        self._send(msg)

    def configure(self, **fields: Any) -> None:
        """Mode and config fields: mode, width, height, streams, streamHz, .."""
        self._send({"type": "configure", **fields})
        if "mode" in fields:
            self.wait_status(lambda s: s.get("mode") == fields["mode"])

    def set_mode(self, mode: str) -> None:
        self.configure(mode=mode)

    def reset(self, seed: int | None = None, **options: Any) -> Observation:
        """options: task (navigate_to, dig_block, place_block, chest_transfer, mine_and_deliver, replicate_build), radius, obstacles, targets, maxSteps"""
        msg_id = next(self._ids)
        self._send({"type": "reset", "id": msg_id, "seed": seed, "options": options})
        obs = self._wait_obs(msg_id)
        self.log.episode = (obs.episode or {}).get("id")
        self.log.info("task.reset", seed=seed, options=options)
        return obs

    def step(self, action: dict[str, Any], ticks: int = 1) -> Observation:
        msg_id = next(self._ids)
        self._send({"type": "step", "id": msg_id, "action": action, "ticks": ticks})
        obs = self._wait_obs(msg_id)
        self.log.debug("step", action=action, reward=obs.reward, done=obs.done)
        return obs

    def act(self, action: dict[str, Any]) -> None:
        self._send({"type": "act", "action": action})

    def record(self, on: bool) -> None:
        self._send({"type": "record", "on": on})
        self.wait_status(lambda s: s.get("recordArmed") == on)

    def pilot(self, on: bool) -> None:
        self._send({"type": "pilot", "on": on})

    def release(self) -> None:
        self._send({"type": "release"})
        self.role = "observer"

    def ping(self) -> float:
        msg_id = next(self._ids)
        start = time.perf_counter()
        self._send({"type": "ping", "id": msg_id})
        self._wait_text("pong", reply_to=msg_id)
        return time.perf_counter() - start

    def next_obs(self, timeout: float | None = None) -> Observation:
        """Next streamed observation, for observers and realtime controllers"""
        if self._obs_backlog:
            return self._obs_backlog.pop(0)
        deadline = time.monotonic() + (timeout or self.timeout)
        while True:
            message = self._recv(deadline)
            if isinstance(message, Observation):
                return message

    # ---- receive loop ----

    def _send(self, msg: dict[str, Any]) -> None:
        if self._ws is None:
            raise BridgeError("not connected")
        self._ws.send(json.dumps(msg))

    def _recv(self, deadline: float) -> dict[str, Any] | Observation:
        if self._ws is None:
            raise BridgeError("not connected")
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError("no reply from the mod in time")
        raw = self._ws.recv(timeout=remaining)
        if isinstance(raw, bytes):
            return decode_obs(raw)
        msg = json.loads(raw)
        kind = msg.get("type")
        if kind == "status":
            self.status = msg
        elif kind == "metrics":
            self.metrics = msg
        elif kind == "log":
            self.logs.append(msg["entry"])
            del self.logs[:-1000]
        return msg

    def _wait_text(self, kind: str, reply_to: int | None = None) -> dict[str, Any]:
        deadline = time.monotonic() + self.timeout
        while True:
            msg = self._recv(deadline)
            if isinstance(msg, Observation):
                self._obs_backlog.append(msg)
                continue
            if msg.get("type") == "error" and (reply_to is None or msg.get("replyTo") in (None, reply_to)):
                raise BridgeError(msg.get("message", "unknown error"))
            if msg.get("type") == kind and (reply_to is None or msg.get("replyTo") == reply_to):
                return msg

    def wait_status(self, predicate: Any, timeout: float | None = None) -> None:
        """Reads messages until the status satisfies predicate, observations that arrive meanwhile are kept"""
        deadline = time.monotonic() + (timeout or self.timeout)
        while not predicate(self.status):
            msg = self._recv(deadline)
            if isinstance(msg, Observation):
                self._obs_backlog.append(msg)
            elif msg.get("type") == "error":
                raise BridgeError(msg.get("message", "unknown error"))

    def _wait_obs(self, msg_id: int) -> Observation:
        deadline = time.monotonic() + self.timeout
        while True:
            msg = self._recv(deadline)
            if isinstance(msg, Observation):
                if msg.reply_to == msg_id:
                    return msg
                continue
            if msg.get("type") == "error" and msg.get("replyTo") == msg_id:
                self.log.warn("bridge.error", message=msg.get("message"), reply_to=msg_id)
                raise BridgeError(msg.get("message", "unknown error"))
