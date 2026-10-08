from types import SimpleNamespace

import numpy as np
import pytest
import torch

from drone_model.experts.base import tool_action
from drone_model.framework.data import pad_episodes
from drone_model.framework.registry import POLICIES
from drone_model.framework.spec import Step

MASK_IDS = {"blocks": ["minecraft:stone"], "entities": ["mcdrone:drone"], "categories": ["misc"], "entityBase": 32768}


def step() -> Step:
    state = {
        "pos": [0.5, 64.0, 0.5], "vel": [0.0, 0.0, 0.0], "yaw": 0.0, "pitch": 10.0, "bounds": [-10, 60, -10, 10, 80, 10],
        "camera": {"eyeHeight": 0.34, "fov": 70.0, "windowAspect": 1.33}, "inventory": [["minecraft:bricks", 3]],
    }
    obs = {"rgb": np.zeros((120, 160, 3), np.uint8), "depth": np.full((120, 160), 5.0, np.float32), "mask": np.ones((120, 160), np.uint16)}
    teacher = SimpleNamespace(intent={"mode": "place", "aim": (1.5, 64.5, 3.5), "via": None, "block": "minecraft:bricks", "standoff": 0.0, "hover": 0.0})
    return Step(state, obs, tool_action([0.5, 0, 0, 0.1, 0], "place"), None, MASK_IDS, teacher)


@pytest.mark.parametrize("name", list(POLICIES))
def test_every_policy_records_trains_and_acts(name):
    spec = POLICIES[name]
    rows = [spec.record(step()) for _ in range(4)]
    columns = {k: np.stack([r[k] for r in rows]) for k in rows[0]}
    if spec.sequence:
        batch = pad_episodes([columns], torch.device("cpu"))
    else:
        batch = {k: torch.from_numpy(v) for k, v in columns.items()}
    model = spec.model()
    out = spec.loss(model, spec.augment(batch))
    assert torch.isfinite(out["loss"])
    out["loss"].backward()
    action = spec.agent(model.eval(), MASK_IDS).act(step())
    assert np.asarray(action["move"]).shape == (5,)
