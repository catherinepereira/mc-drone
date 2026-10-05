import numpy as np

from mcdrone.protocol import Observation, decode_obs, encode_obs


def test_round_trip_keeps_arrays_and_header():
    obs = Observation(
        seq=3,
        tick=40,
        state={"pos": [1.0, 2.0, 3.0]},
        episode={"done": True, "reward": 1.5},
        action={"move": [1, 0, 0], "look": [0, 0]},
        reply_to=9,
        rgb=np.arange(4 * 5 * 3, dtype=np.uint8).reshape(4, 5, 3),
        depth=np.linspace(0, 1, 20, dtype=np.float32).reshape(4, 5),
        mask=np.arange(20, dtype=np.uint16).reshape(4, 5) * 1000,
    )
    out = decode_obs(encode_obs(obs))
    assert out.seq == 3 and out.tick == 40 and out.reply_to == 9
    assert out.done and out.reward == 1.5
    np.testing.assert_array_equal(out.rgb, obs.rgb)
    np.testing.assert_array_equal(out.depth, obs.depth)
    np.testing.assert_array_equal(out.mask, obs.mask)


def test_missing_streams_decode_as_none():
    obs = Observation(seq=1, tick=1, state={}, episode=None, action=None, reply_to=None, rgb=np.zeros((2, 2, 3), np.uint8))
    out = decode_obs(encode_obs(obs))
    assert out.depth is None and out.mask is None
    assert not out.done
