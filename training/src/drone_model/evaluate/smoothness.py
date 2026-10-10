"""
Measures how smoothly a policy turns: per step, the heading and pitch change, how often the turn reverses direction,
and how sharply the turn rate changes

    python -m drone_model.evaluate.smoothness --policy skill --task hunt_mobs --episodes 3
    python -m drone_model.evaluate.smoothness --policy goto --task find_block --terrain rough --teacher
"""

from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
from mcdrone import DroneEnv

from ..framework.evaluate import EVAL_SEED, Runner, outcome
from ..paths import CHECKPOINTS
from ..perception.reader import Reader

# turns slower than this, in degrees per step, don't count as reversing
REVERSAL_RATE = 1.0


def wrap(deg: np.ndarray) -> np.ndarray:
    return (deg + 180.0) % 360.0 - 180.0


def turn_stats(yaw: list[float], pitch: list[float]) -> dict:
    """Reversals of the heading's and the pitch's turn direction, and the mean absolute change in turn rate, per step"""
    out = {}
    for name, angles in (("yaw", yaw), ("pitch", pitch)):
        rate = wrap(np.diff(np.asarray(angles, dtype=np.float64)))
        turning = np.abs(rate) >= REVERSAL_RATE
        flips = (np.sign(rate[1:]) != np.sign(rate[:-1])) & turning[1:] & turning[:-1]
        out[f"{name}_reversals_per_100"] = 100.0 * float(flips.sum()) / max(len(rate), 1)
        out[f"{name}_mean_rate_change"] = float(np.abs(np.diff(rate)).mean()) if len(rate) > 1 else 0.0
    return out


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--policy", required=True)
    parser.add_argument("--checkpoint", type=Path, default=None)
    parser.add_argument("--teacher", action="store_true", help="measure the policy's teacher alone")
    parser.add_argument("--task", default="hunt_mobs")
    parser.add_argument("--terrain", choices=["flat", "rough", "cave"], default="flat")
    parser.add_argument("--episodes", type=int, default=3)
    parser.add_argument("--obstacles", type=int, default=8)
    parser.add_argument("--max-steps", type=int, default=1000)
    parser.add_argument("--turn-smoothing", type=float, default=None, help="the mod's turnSmoothing for this run, 1 turns at the commanded rate at once")
    args = parser.parse_args()

    runner = Runner(args.policy, args.checkpoint, args.teacher, Reader(CHECKPOINTS / "reader.pt"))
    options = {"obstacles": args.obstacles, "terrain": args.terrain, "size": 5, "perception": "vision", "maxSteps": args.max_steps}
    env = DroneEnv(task=args.task, tools=True, streams=runner.spec.streams, action_pause_ms=0, task_options=options)
    rows = []
    saved = None
    try:
        if args.turn_smoothing is not None:
            # configure saves to the mod's config, put the player's value back afterward
            saved = env.client.config.get("turnSmoothing")
            env.client.configure(turnSmoothing=args.turn_smoothing)
        for i in range(args.episodes):
            obs, info = env.reset(seed=EVAL_SEED + i)
            runner.start(args.task, info, env.client.mask_ids)
            yaw, pitch = [info["state"]["yaw"]], [info["state"]["pitch"]]
            terminated, truncated = bool(info["episode"].get("done")), False
            while not (terminated or truncated):
                obs, _, terminated, truncated, info = env.step(runner.act(info["state"], obs))
                yaw.append(info["state"]["yaw"])
                pitch.append(info["state"]["pitch"])
            stats = turn_stats(yaw, pitch)
            rows.append(stats)
            print(f"seed {EVAL_SEED + i}: {outcome(info['episode'])} in {len(yaw) - 1} steps, "
                  + ", ".join(f"{k} {v:.2f}" for k, v in stats.items()), flush=True)
    finally:
        if saved is not None:
            env.client.configure(turnSmoothing=saved)
        env.close()
    who = "teacher" if args.teacher else (args.checkpoint or runner.checkpoint).stem
    mean = {k: float(np.mean([r[k] for r in rows])) for k in rows[0]}
    print(f"{args.policy} ({who}) {args.task} {args.terrain}: " + ", ".join(f"{k} {v:.2f}" for k, v in mean.items()))


if __name__ == "__main__":
    main()
