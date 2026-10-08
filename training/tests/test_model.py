import json

import numpy as np
import torch
from PIL import Image

from drone_model.experts.navigate import expert_action
from drone_model.framework.collect import from_recordings
from drone_model.framework.data import load_steps
from drone_model.policies.navigate import ACTION_DIM, DronePolicy, NavigateSpec, to_image


def test_policy_output_shape_and_range():
    model = DronePolicy().eval()
    image = to_image(torch.zeros(2, 120, 160, 3, dtype=torch.uint8), torch.full((2, 120, 160), 10.0))
    out = model(image, torch.zeros(2, 6))
    assert out.shape == (2, ACTION_DIM)
    assert out.abs().max() <= 1.0


def test_expert_turns_toward_marker_before_moving():
    state = {"pos": [0.0, 0.0, 0.0], "marker": [10, 0, 0], "yaw": 0.0, "pitch": 0.0}
    action = expert_action(state)
    # marker is at +x, which is yaw -90 in Minecraft, so the expert turns left without driving forward
    assert action[3] < 0 and action[0] == 0.0
    facing = expert_action({**state, "yaw": -90.0})
    assert facing[0] > 0.9 and abs(facing[3]) < 0.25


def test_recordings_become_rows_labeled_by_the_expert(tmp_path):
    ep = tmp_path / "navigate_to" / "e1"
    for sub in ("rgb", "depth"):
        (ep / sub).mkdir(parents=True)
    (ep / "meta.json").write_text(json.dumps({"id": "e1", "width": 8, "height": 6}))
    rows = []
    for n in range(3):
        Image.fromarray(np.zeros((6, 8, 3), np.uint8)).save(ep / "rgb" / f"{n:06d}.png")
        np.full((6, 8), 32.0, "<f4").tofile(ep / "depth" / f"{n:06d}.f32")
        rows.append({"step": n, "state": {"vel": [0, 0, 0], "yaw": 0, "pitch": 0}, "action": None if n == 2 else {"move": [0, 0, 0], "look": [0, 0]}, "reward": 0, "done": n == 2})
    (ep / "steps.jsonl").write_text("\n".join(json.dumps(r) for r in rows))
    (ep / "expert.jsonl").write_text("[1, 0, 0, 0.5, 0]\n[0, 0, 1, 0, 0]\n")
    (tmp_path / "navigate_to" / "mask_ids.json").write_text(json.dumps({"blocks": [], "entities": [], "entityBase": 32768}))
    out = tmp_path / "rows"
    out.mkdir()
    from_recordings(NavigateSpec(), "navigate_to", out, every_outcome=True, data=tmp_path)
    rows = load_steps(sorted(out.glob("*.npz")), stride=1)
    assert len(rows["move"]) == 2
    assert rows["move"][0].tolist() == [1, 0, 0, 0.5, 0]
    assert rows["depth"][0, 0, 0] == 127


def test_expert_plans_around_a_pillar():
    from drone_model.experts.navigate import Pillars, waypoint

    # pillar sits squarely between the drone and the marker
    arena = {"origin": [0, -61, 0], "radius": 12, "obstacles": [[-1, 3, 2, 9]]}
    state = {"pos": [0.0, -58.0, 0.0], "marker": [0, -58, 8], "yaw": 0.0, "pitch": 0.0, "arena": arena}
    pillars = Pillars(arena)
    assert not pillars.clear_line(0.0, 0.0, 0.5, 8.5)
    target = waypoint(state)
    assert target != (0.5, 8.5)
    assert pillars.clear_line(0.0, 0.0, *target)


def test_gae_stops_at_episode_boundaries():
    from drone_model.framework.ppo import gae

    rewards = np.array([1.0, 1.0, 1.0], np.float32)
    values = np.zeros(3, np.float32)
    dones = np.array([0.0, 1.0, 0.0], np.float32)
    adv, ret = gae(rewards, values, dones, last_value=10.0, gamma=0.5, lam=1.0)
    assert adv[1] == 1.0
    assert adv[0] == 1.5
    assert adv[2] == 1.0 + 0.5 * 10.0


def test_planner_escapes_when_starting_inside_clearance():
    from drone_model.experts.navigate import Pillars, waypoint

    arena = {"origin": [0, -61, 0], "radius": 12, "obstacles": [[4, -1, 2, 3]]}
    # drone hugging the east face of the pillar, chest far to the west
    state = {"pos": [6.3, -59.0, -0.7], "yaw": 109.0, "pitch": 0.0, "arena": arena}
    pillars = Pillars(arena)
    assert not pillars.free(6.3, -0.7)
    target = waypoint(state, (-10.5, -6.5), pillars)
    assert pillars.free(*target)
    assert target != (-10.5, -6.5)
