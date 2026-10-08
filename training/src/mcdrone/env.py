"""Gymnasium env over the bridge's lockstep mode"""

from __future__ import annotations

import math
from pathlib import Path
from typing import Any, Literal

import gymnasium as gym
import numpy as np
from gymnasium import spaces

from .client import DroneClient
from .protocol import DEFAULT_HOST, DEFAULT_PORT, TOOLS, Observation, action as make_action

MAX_LOOK = 15.0
# seconds a fleet reset may wait for the group's other drones to finish their episodes
FLEET_WAIT = 3600.0
STATE_DIM = 6
MARKER_DIM = 3
INVENTORY_SLOTS = 27
# a double chest, the largest container the drone opens
CONTAINER_SLOTS = 54
TRANSFER_NONE, TRANSFER_TO_CONTAINER, TRANSFER_FROM_CONTAINER = 0, 1, 2


class DroneEnv(gym.Env):
    """
    One mc-drone task as a Gymnasium env.

    Without tools (the navigate_to default) the action is a float vector in [-1, 1]: forward, right, up, yaw, pitch,
    with yaw and pitch scaled by 15 degrees per tick.
    With tools the action is a dict: "move" (that vector), "tool" (index into protocol.TOOLS), "slot" (selected
    inventory slot), and "transfer" ([kind, slot] with kind 0 none, 1 drone to container, 2 container to drone).
    An optional "block" names the block to place, the drone then uses whichever slot holds it.

    Observations hold the configured image streams and a state vector: velocity (3), sin and cos of yaw, pitch / 90,
    and with hide_marker=False the marker offset (3). With tools they add "inventory" (27 x [item index, count]),
    "container" (54 x [item index, count], zeros when closed), and "tool_state" ([mining progress, container open,
    selected slot / 26]). Item indices come from the mod's item table in DroneClient.item_ids
    """

    metadata = {"render_modes": ["rgb_array"]}

    def __init__(
        self,
        host: str = DEFAULT_HOST,
        port: int = DEFAULT_PORT,
        width: int = 160,
        height: int = 120,
        streams: tuple[str, ...] = ("rgb", "depth"),
        task: str = "navigate_to",
        tools: bool | None = None,
        ticks_per_step: int = 1,
        hide_marker: bool = True,
        task_options: dict[str, Any] | None = None,
        depth_max: float = 64.0,
        record: bool | Literal["test"] = False,
        log_dir: Path | None = None,
        client: DroneClient | None = None,
        render_mode: str | None = None,
        action_pause_ms: int | None = None,
        chase_size: tuple[int, int] = (640, 360),
        drone: int | None = None,
    ) -> None:
        super().__init__()
        # the entity id of the drone to fly, None for the player's active drone
        self.drone = drone
        self.host = host
        self.port = port
        self.width = width
        self.height = height
        self.streams = tuple(streams)
        self.task = task
        self.tools = task != "navigate_to" if tools is None else tools
        self.ticks_per_step = ticks_per_step
        self.hide_marker = hide_marker
        self.task_options = task_options or {}
        self.depth_max = depth_max
        self.record = record
        self.log_dir = log_dir
        self.render_mode = render_mode
        # None keeps the mod's setting, 0 skips the pause after tool actions for fast collection and training
        self.action_pause_ms = action_pause_ms
        self.chase_size = chase_size
        self._client = client
        self._configured = False
        self._last: Observation | None = None
        self._item_index: dict[str, int] = {}
        self._saved_pause: int | None = None

        move_space = spaces.Box(-1.0, 1.0, shape=(5,), dtype=np.float32)
        if self.tools:
            self.action_space = spaces.Dict(
                {
                    "move": move_space,
                    "tool": spaces.Discrete(len(TOOLS)),
                    "slot": spaces.Discrete(INVENTORY_SLOTS),
                    "transfer": spaces.MultiDiscrete([3, CONTAINER_SLOTS]),
                }
            )
        else:
            self.action_space = move_space
        obs_spaces: dict[str, spaces.Space] = {}
        if "rgb" in self.streams:
            obs_spaces["rgb"] = spaces.Box(0, 255, shape=(height, width, 3), dtype=np.uint8)
        if "depth" in self.streams:
            obs_spaces["depth"] = spaces.Box(0.0, depth_max, shape=(height, width), dtype=np.float32)
        if "mask" in self.streams:
            obs_spaces["mask"] = spaces.Box(0, 65535, shape=(height, width), dtype=np.uint16)
        if "state" in self.streams:
            obs_spaces["block_states"] = spaces.Box(0, 65535, shape=(height, width), dtype=np.uint16)
        if "chase" in self.streams:
            obs_spaces["chase"] = spaces.Box(0, 255, shape=(chase_size[1], chase_size[0], 3), dtype=np.uint8)
        state_dim = STATE_DIM + (0 if hide_marker else MARKER_DIM)
        obs_spaces["state"] = spaces.Box(-np.inf, np.inf, shape=(state_dim,), dtype=np.float32)
        # position inside the geofence, -1 to 1 per axis, beyond that is out of bounds
        obs_spaces["bounds"] = spaces.Box(-np.inf, np.inf, shape=(3,), dtype=np.float32)
        # the range sensors ahead and below as a share of their reach, 1 when nothing is in range
        obs_spaces["range"] = spaces.Box(0.0, 1.0, shape=(2,), dtype=np.float32)
        if self.tools:
            obs_spaces["inventory"] = spaces.Box(0, 1 << 20, shape=(INVENTORY_SLOTS, 2), dtype=np.int32)
            obs_spaces["container"] = spaces.Box(0, 1 << 20, shape=(CONTAINER_SLOTS, 2), dtype=np.int32)
            obs_spaces["tool_state"] = spaces.Box(0.0, 1.0, shape=(3,), dtype=np.float32)
        self.observation_space = spaces.Dict(obs_spaces)

    @property
    def client(self) -> DroneClient:
        if self._client is None:
            self._client = DroneClient(self.host, self.port, role="controller", log_dir=self.log_dir, client_name="mcdrone-env", drone=self.drone)
            self._client.connect()
        if not self._configured:
            extra: dict[str, Any] = {}
            if "chase" in self.streams:
                extra.update(chaseWidth=self.chase_size[0], chaseHeight=self.chase_size[1])
            if self.action_pause_ms is not None:
                # configure saves to the mod's config, so remember the player's pause to put it back on close
                self._saved_pause = self._client.config.get("actionPauseMs")
                extra["actionPauseMs"] = self.action_pause_ms
            self._client.configure(mode="lockstep", width=self.width, height=self.height, streams=list(self.streams), depthMax=self.depth_max, **extra)
            self._client.subscribe(obs=False)
            self._client.record(bool(self.record), test=self.record == "test")
            self._item_index = {name: i for i, name in enumerate(self._client.item_ids)}
            self._configured = True
        return self._client

    def reset(self, *, seed: int | None = None, options: dict[str, Any] | None = None) -> tuple[dict[str, np.ndarray], dict[str, Any]]:
        super().reset(seed=seed)
        task_seed = int(self.np_random.integers(0, 2**31 - 1))
        merged = {"task": self.task, **self.task_options, **(options or {})}
        # a fleet's reset waits for its slowest drone to finish
        obs = self.client.reset(seed=task_seed, timeout=FLEET_WAIT if "fleet" in merged else None, **merged)
        self._last = obs
        return self._convert(obs), self._info(obs)

    def step(self, action: Any) -> tuple[dict[str, np.ndarray], float, bool, bool, dict[str, Any]]:
        obs = self.client.step(self.to_protocol(action), ticks=self.ticks_per_step)
        self._last = obs
        episode = obs.episode or {}
        # leaving the geofence ends the episode as a failure, so it terminates like success does
        terminated = bool(episode.get("success")) or bool(episode.get("outOfBounds"))
        return self._convert(obs), obs.reward, terminated, bool(episode.get("truncated")), self._info(obs)

    def to_protocol(self, action: Any) -> dict[str, Any]:
        move = np.clip(np.asarray(action["move"] if self.tools else action, dtype=np.float32), -1.0, 1.0)
        kwargs: dict[str, Any] = {}
        if self.tools:
            kind, slot = (int(v) for v in action["transfer"])
            transfer = None
            if kind != TRANSFER_NONE:
                transfer = {"from": "drone" if kind == TRANSFER_TO_CONTAINER else "container", "slot": slot}
            kwargs = {"tool": TOOLS[int(action["tool"])], "slot": int(action["slot"]), "transfer": transfer, "block": action.get("block")}
        return make_action(float(move[0]), float(move[1]), float(move[2]), float(move[3]) * MAX_LOOK, float(move[4]) * MAX_LOOK, **kwargs)

    def render(self) -> np.ndarray | None:
        if self._last is None or self._last.rgb is None:
            return None
        return self._last.rgb

    def close(self) -> None:
        if self._client is not None:
            try:
                if self._saved_pause is not None:
                    self._client.configure(actionPauseMs=self._saved_pause)
                    self._saved_pause = None
                self._client.release()
            finally:
                self._client.close()
                self._client = None
                self._configured = False

    def _convert(self, obs: Observation) -> dict[str, np.ndarray]:
        out: dict[str, np.ndarray] = {}
        if "rgb" in self.streams:
            out["rgb"] = obs.rgb
        if "depth" in self.streams:
            out["depth"] = np.clip(obs.depth, 0.0, self.depth_max).astype(np.float32)
        if "mask" in self.streams:
            out["mask"] = obs.mask
        if "state" in self.streams:
            out["block_states"] = obs.block_states
        if "chase" in self.streams:
            out["chase"] = obs.chase
        out["state"] = state_vector(obs.state, include_marker=not self.hide_marker)
        out["bounds"] = bounds_vector(obs.state)
        out["range"] = range_vector(obs.state)
        if self.tools:
            out["inventory"] = self._slots(obs.state.get("inventory"), INVENTORY_SLOTS)
            container = obs.state.get("container")
            out["container"] = self._slots(container["slots"] if container else None, CONTAINER_SLOTS)
            breaking = obs.state.get("breaking")
            out["tool_state"] = np.asarray(
                [breaking["progress"] if breaking else 0.0, 1.0 if container else 0.0, obs.state.get("selectedSlot", 0) / (INVENTORY_SLOTS - 1)],
                dtype=np.float32,
            )
        return out

    def _slots(self, slots: list | None, size: int) -> np.ndarray:
        out = np.zeros((size, 2), dtype=np.int32)
        for i, (name, count) in enumerate((slots or [])[:size]):
            if count > 0:
                out[i] = (self._item_index.get(name, 0), count)
        return out

    @staticmethod
    def _info(obs: Observation) -> dict[str, Any]:
        return {"episode": obs.episode, "state": obs.state, "tick": obs.tick}


def range_vector(state: dict[str, Any]) -> np.ndarray:
    sensors = state.get("range") or {}
    reach = sensors.get("max") or 1.0
    return np.asarray([1.0 if sensors.get(k) is None else min(1.0, sensors[k] / reach) for k in ("front", "down")], dtype=np.float32)


def bounds_vector(state: dict[str, Any]) -> np.ndarray:
    box = state.get("bounds")
    pos = state.get("pos")
    if not box or not pos:
        return np.zeros(3, dtype=np.float32)
    return np.asarray([(pos[i] - box[i]) / (box[i + 3] - box[i]) * 2 - 1 for i in range(3)], dtype=np.float32)


def state_vector(state: dict[str, Any], include_marker: bool) -> np.ndarray:
    vel = state.get("vel", [0.0, 0.0, 0.0])
    yaw = math.radians(state.get("yaw", 0.0))
    values = [*vel, math.sin(yaw), math.cos(yaw), state.get("pitch", 0.0) / 90.0]
    if include_marker:
        marker = state.get("marker") or [0, 0, 0]
        pos = state.get("pos", [0.0, 0.0, 0.0])
        values += [marker[i] + 0.5 - pos[i] for i in range(3)]
    return np.asarray(values, dtype=np.float32)
