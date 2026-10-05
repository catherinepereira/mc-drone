"""Reads episodes recorded by the mod, layout in mc-drone-mod/protocol/PROTOCOL.md"""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterator

import numpy as np
from PIL import Image

MAX_LOOK = 15.0


@dataclass
class Episode:
    path: Path
    meta: dict[str, Any]
    steps: list[dict[str, Any]]

    @property
    def width(self) -> int:
        return int(self.meta["width"])

    @property
    def height(self) -> int:
        return int(self.meta["height"])

    def __len__(self) -> int:
        return len(self.steps)

    def rgb(self, n: int) -> np.ndarray:
        return np.asarray(Image.open(self.path / "rgb" / f"{n:06d}.png").convert("RGB"))

    def depth(self, n: int) -> np.ndarray:
        return np.fromfile(self.path / "depth" / f"{n:06d}.f32", dtype="<f4").reshape(self.height, self.width)

    def mask(self, n: int) -> np.ndarray:
        # 16-bit grayscale PNG, PIL opens it as mode "I;16"
        return np.asarray(Image.open(self.path / "mask" / f"{n:06d}.png"), dtype=np.uint16)

    def block_states(self, n: int) -> np.ndarray:
        """The state stream, 1 + block state id per pixel, see DroneClient.state_names"""
        return np.asarray(Image.open(self.path / "state" / f"{n:06d}.png"), dtype=np.uint16)

    def has(self, stream: str, n: int) -> bool:
        suffix = ".f32" if stream == "depth" else ".png"
        return (self.path / stream / f"{n:06d}{suffix}").exists()


def load_episode(path: Path | str) -> Episode:
    path = Path(path)
    meta = json.loads((path / "meta.json").read_text(encoding="utf-8"))
    steps_file = path / "steps.jsonl"
    steps = []
    if steps_file.exists():
        steps = [json.loads(line) for line in steps_file.read_text(encoding="utf-8").splitlines() if line.strip()]
    return Episode(path, meta, steps)


def list_episodes(root: Path | str, task: str = "navigate_to", outcome: str | None = None) -> list[Episode]:
    task_dir = Path(root) / task
    if not task_dir.is_dir():
        return []
    episodes = []
    for path in sorted(task_dir.iterdir()):
        if not (path / "meta.json").exists():
            continue
        episode = load_episode(path)
        if outcome is None or episode.meta.get("outcome") == outcome:
            episodes.append(episode)
    return episodes


def action_array(action: dict[str, Any]) -> np.ndarray:
    """Inverse of DroneEnv's action mapping, so recorded actions train the same policy head"""
    move = action["move"]
    look = action["look"]
    return np.asarray([move[0], move[1], move[2], look[0] / MAX_LOOK, look[1] / MAX_LOOK], dtype=np.float32)


def iter_transitions(episodes: list[Episode], streams: tuple[str, ...] = ("rgb", "depth")) -> Iterator[dict[str, Any]]:
    """Yields one dict per step that has an action, the terminal step is skipped"""
    for episode in episodes:
        for row in episode.steps:
            if row.get("action") is None:
                continue
            n = row["step"]
            sample: dict[str, Any] = {
                "episode": episode.meta["id"],
                "step": n,
                "state": row["state"],
                "action": action_array(row["action"]),
                "reward": float(row["reward"]),
                "done": bool(row["done"]),
            }
            for stream in streams:
                sample[stream] = getattr(episode, stream)(n)
            yield sample
