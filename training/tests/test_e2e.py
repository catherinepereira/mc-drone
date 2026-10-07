"""
Runs against a live game.
Start the mod with `gradlew runClientGameTest -Pe2eHold=600` (or a world open in runClient), then
MCDRONE_E2E=1 pytest tests/test_e2e.py
"""

import json
import math
import os
import time
import urllib.error
import urllib.request
from pathlib import Path

import numpy as np
import pytest

from mcdrone import BridgeError, DroneClient, DroneEnv, action, decode_obs

pytestmark = pytest.mark.skipif(os.environ.get("MCDRONE_E2E") != "1", reason="needs a running game, set MCDRONE_E2E=1")

BASE = "http://127.0.0.1:8318"


def http(method, path, body=None, origin=None):
    req = urllib.request.Request(BASE + path, method=method, data=None if body is None else json.dumps(body).encode())
    if origin:
        req.add_header("Origin", origin)
    with urllib.request.urlopen(req, timeout=10) as resp:
        return resp.read()


def steer(state_vec):
    """Scripted policy from the marker offset in the state vector, only used to check the plumbing"""
    _, _, _, sin_yaw, cos_yaw, _, dx, dy, dz = state_vec
    yaw = math.degrees(math.atan2(sin_yaw, cos_yaw))
    target = math.degrees(math.atan2(-dx, dz))
    err = (target - yaw + 180) % 360 - 180
    forward = min(1.0, math.hypot(dx, dz) / 2) if abs(err) < 30 else 0.0
    return np.array([forward, 0, np.clip(dy - 0.2, -1, 1), np.clip(err / 15, -1, 1), 0], dtype=np.float32)


@pytest.fixture(scope="module", autouse=True)
def signal_done():
    yield
    done = os.environ.get("MCDRONE_E2E_DONE")
    if done:
        Path(done).write_text("done")


def test_env_reaches_marker_and_records():
    # steer reads the marker, so the episode goes to the test folder, which the API serves until the env closes
    env = DroneEnv(hide_marker=False, streams=("rgb", "depth", "mask"), record="test")
    try:
        obs, info = env.reset(seed=11)
        assert obs["rgb"].shape == (120, 160, 3)
        assert obs["rgb"].std() > 5
        assert (obs["mask"] > 0).any()
        terminated = truncated = False
        steps = 0
        while not (terminated or truncated):
            obs, reward, terminated, truncated, info = env.step(steer(obs["state"]))
            steps += 1
        assert terminated, info
        episode_id = info["episode"]["id"]

        time.sleep(1.0)
        detail = json.loads(http("GET", f"/api/episodes/navigate_to/{episode_id}"))
        assert detail["meta"]["outcome"] == "success"
        assert detail["meta"]["client"] == "mcdrone-env"
        assert len(detail["steps"]) == steps + 1
        frame = decode_obs(http("GET", f"/api/episodes/navigate_to/{episode_id}/frame/0"))
        assert frame.rgb.shape == (120, 160, 3)
        assert frame.mask is not None and frame.depth is not None
    finally:
        env.close()
    assert not json.loads(http("GET", "/api/status"))["recordArmed"]


def test_realtime_act_moves_drone_and_observer_sees_frames():
    with DroneClient(client_name="e2e-controller") as ctl, DroneClient(role="observer", client_name="e2e-observer") as obs_client:
        ctl.set_mode("lockstep")
        ctl.reset(seed=3)
        ctl.set_mode("realtime")
        start = latest_obs(ctl).state["pos"]
        ctl.act(action(up=1.0))
        time.sleep(1.0)
        ctl.act(action())
        seen = obs_client.next_obs(timeout=10)
        later = latest_obs(ctl).state["pos"]
        assert later[1] > start[1] + 1.0
        assert seen.rgb is not None


def latest_obs(client, window=0.5):
    """Streamed frames queue up, so read for a moment and keep the newest"""
    obs = client.next_obs(timeout=10)
    deadline = time.monotonic() + window
    while time.monotonic() < deadline:
        try:
            obs = client.next_obs(timeout=max(0.05, deadline - time.monotonic()))
        except TimeoutError:
            break
    return obs


def test_second_controller_is_refused():
    with DroneClient(client_name="first"):
        with pytest.raises(BridgeError):
            DroneClient(client_name="second").connect()


def test_http_status_config_and_origin_guard():
    status = json.loads(http("GET", "/api/status"))
    assert status["inWorld"] is True
    before = json.loads(http("GET", "/api/config"))
    after = json.loads(http("PUT", "/api/config", {"streamHz": 5}))
    assert after["streamHz"] == 5
    http("PUT", "/api/config", {"streamHz": before["streamHz"]})
    logs = json.loads(http("GET", "/api/logs?limit=20"))
    assert logs and all("event" in line for line in logs)
    with pytest.raises(urllib.error.HTTPError) as err:
        http("GET", "/api/status", origin="http://evil.example")
    assert err.value.code == 403
    assert json.loads(http("GET", "/api/status", origin="http://localhost:5318"))["inWorld"]
