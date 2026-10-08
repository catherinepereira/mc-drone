"""
Expert labels saved beside a recorded episode, one JSON line per step, line n labels step n.
DART collection flies the expert's action plus flight noise and labels the clean action, DAgger flies the policy
"""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np

FILE = "expert.jsonl"


def row(action: dict) -> dict:
    """An expert's tool action as a label line"""
    return {
        "move": np.asarray(action["move"]).tolist(), "tool": int(action["tool"]), "slot": int(action["slot"]),
        "transfer": np.asarray(action["transfer"]).tolist(), "block": action.get("block"),
    }


def write(episode_dir: Path, rows: list[dict]) -> None:
    with open(episode_dir / FILE, "w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r) + "\n")


def read(episode_dir: Path) -> list | None:
    """The episode's labels, None when it has none, such as a human demo"""
    path = episode_dir / FILE
    if not path.exists():
        return None
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def with_flight_noise(action: dict, rng: np.random.Generator, scale: float) -> dict:
    """The action with Gaussian noise on its flight, tools and transfers stay the expert's own"""
    return dict(action, move=np.clip(action["move"] + rng.normal(0.0, scale, size=5).astype(np.float32), -1.0, 1.0))
