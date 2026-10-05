import json

import numpy as np
from PIL import Image

from mcdrone import action_array, iter_transitions, list_episodes


def write_episode(root, name, steps=3, width=8, height=6):
    ep = root / "navigate_to" / name
    for sub in ("rgb", "depth", "mask"):
        (ep / sub).mkdir(parents=True)
    meta = {"id": name, "task": "navigate_to", "width": width, "height": height, "outcome": "success"}
    (ep / "meta.json").write_text(json.dumps(meta))
    rows = []
    for n in range(steps):
        Image.fromarray(np.full((height, width, 3), n, np.uint8)).save(ep / "rgb" / f"{n:06d}.png")
        np.full((height, width), n, "<f4").tofile(ep / "depth" / f"{n:06d}.f32")
        Image.fromarray(np.full((height, width), 40000 + n, np.uint16)).save(ep / "mask" / f"{n:06d}.png")
        act = None if n == steps - 1 else {"move": [1, 0, 0], "look": [15, -7.5]}
        rows.append({"step": n, "tick": n, "state": {}, "action": act, "reward": 0.5, "done": n == steps - 1})
    (ep / "steps.jsonl").write_text("\n".join(json.dumps(r) for r in rows) + "\n")


def test_transitions_skip_terminal_and_load_streams(tmp_path):
    write_episode(tmp_path, "a")
    episodes = list_episodes(tmp_path)
    assert len(episodes) == 1
    samples = list(iter_transitions(episodes, streams=("rgb", "depth", "mask")))
    assert len(samples) == 2
    assert samples[1]["rgb"][0, 0, 0] == 1
    assert samples[1]["depth"][0, 0] == 1.0
    assert samples[1]["mask"][0, 0] == 40001


def test_action_array_scales_look():
    np.testing.assert_allclose(action_array({"move": [1, 0, -1], "look": [15, -7.5]}), [1, 0, -1, 1, -0.5])
