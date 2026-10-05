import numpy as np
import pytest
from gymnasium.utils.env_checker import check_env

from mcdrone import BridgeError, DroneClient, DroneEnv, action

from .fake_bridge import FakeBridge


def test_lockstep_reset_and_step():
    with FakeBridge() as bridge, DroneClient(port=bridge.port) as client:
        assert client.role == "controller"
        client.set_mode("lockstep")
        obs = client.reset(seed=1)
        assert obs.rgb.shape == (24, 32, 3)
        moved = client.step(action(forward=1.0))
        assert moved.state["pos"][2] > 0
        assert client.ping() >= 0


def test_step_error_raises():
    with FakeBridge() as bridge, DroneClient(port=bridge.port) as client:
        client.reset(seed=1)
        with pytest.raises(BridgeError):
            client.step(action())


def test_env_passes_gymnasium_checker():
    with FakeBridge() as bridge:
        env = DroneEnv(port=bridge.port, width=32, height=24, streams=("rgb", "depth"))
        try:
            check_env(env, skip_render_check=True)
        finally:
            env.close()


def test_tool_env_passes_checker_and_maps_actions():
    with FakeBridge() as bridge:
        env = DroneEnv(port=bridge.port, width=32, height=24, task="dig_block")
        try:
            check_env(env, skip_render_check=True)
            obs, _ = env.reset(seed=0)
            assert obs["inventory"][0].tolist() == [1, 3]
            env.step({"move": np.zeros(5, np.float32), "tool": 1, "slot": 4, "transfer": np.array([2, 7])})
            sent = bridge.received[-1]["action"]
            assert sent["tool"] == "break" and sent["slot"] == 4
            assert sent["transfer"] == {"from": "container", "slot": 7}
        finally:
            env.close()


def test_env_episode_ends_on_success():
    with FakeBridge() as bridge:
        env = DroneEnv(port=bridge.port, width=32, height=24, hide_marker=False)
        try:
            obs, info = env.reset(seed=0)
            assert obs["state"].shape == (9,)
            terminated = truncated = False
            for _ in range(60):
                obs, reward, terminated, truncated, info = env.step(np.array([1, 0, 0, 0, 0], dtype=np.float32))
                if terminated or truncated:
                    break
            assert terminated and not truncated
            assert bridge.received[-1]["action"]["move"][0] == 1.0
        finally:
            env.close()
