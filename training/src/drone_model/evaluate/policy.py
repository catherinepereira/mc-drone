"""Runs a trained policy in the live mod on fixed seeds and reports success rate"""

from __future__ import annotations

import argparse
import time
from datetime import datetime
from pathlib import Path

import numpy as np
from mcdrone import DroneEnv

from ..agents import POLICIES, Pilot, outcome
from ..paths import CHECKPOINTS, REPORTS
from ..perception.reader import Reader
from ..torch_utils import write_report

# collect.demos seeds start at 0, so eval seeds stay well clear of them
EVAL_SEED = 100_000


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--checkpoint", type=Path, default=CHECKPOINTS / "bc.pt")
    parser.add_argument("--episodes", type=int, default=20)
    parser.add_argument("--policy", choices=POLICIES, default="bc", help="bc runs any DronePolicy checkpoint, including PPO ones, seq a SeqToolPolicy")
    parser.add_argument("--obstacles", type=int, default=8)
    parser.add_argument("--terrain", choices=["flat", "rough", "cave"], default="flat")
    parser.add_argument("--task", default="navigate_to")
    parser.add_argument("--perception", choices=["reader", "mask"], default="reader", help="what experts see with, mask is the mod's ground truth")
    parser.add_argument("--skill", type=Path, default=None, help="a cell skill checkpoint to fly, aim, and fire for the expert's planner")
    parser.add_argument("--reader", type=Path, default=CHECKPOINTS / "reader.pt")
    args = parser.parse_args()
    try:
        pilot = Pilot(args.policy, args.task, args.checkpoint, Reader(args.reader) if args.perception == "reader" else None, args.skill)
    except ValueError as e:
        raise SystemExit(str(e))

    # the expert maps the arena from depth and the semantic mask, so it gets the mask stream too
    streams = ("rgb", "depth", "mask") if args.policy in ("expert", "seq") else ("rgb", "depth")
    env = DroneEnv(task=args.task, tools=args.policy in ("expert", "seq") or None, streams=streams, action_pause_ms=0, task_options={"obstacles": args.obstacles, "terrain": args.terrain})
    results = []
    try:
        for i in range(args.episodes):
            obs, info = env.reset(seed=EVAL_SEED + i)
            agent = pilot.start(env.client.mask_ids, info)
            # an arena can be done before its first step
            terminated, truncated = bool(info["episode"].get("done")), False
            steps = 0
            start = time.perf_counter()
            while not (terminated or truncated):
                obs, reward, terminated, truncated, info = env.step(agent.act(info["state"], obs))
                steps += 1
            results.append(
                {
                    "seed": EVAL_SEED + i,
                    "success": bool(info["episode"].get("success")),
                    "steps": steps,
                    "final_distance": info["episode"].get("distance"),
                    "total_reward": info["episode"].get("totalReward"),
                    "collisions": info["episode"].get("collisions"),
                    "out_of_bounds": bool(info["episode"].get("outOfBounds")),
                    "metrics": info["episode"].get("metrics"),
                    "seconds": time.perf_counter() - start,
                }
            )
            metrics = info["episode"].get("metrics")
            print(f"seed {EVAL_SEED + i}: {outcome(info['episode'])} in {steps} steps" + (f", metrics {metrics}" if metrics else ""), flush=True)
    finally:
        env.close()

    successes = [r for r in results if r["success"]]
    summary = {
        "policy": args.policy,
        "checkpoint": str(args.checkpoint) if args.policy in ("bc", "seq") else None,
        "task": args.task,
        "obstacles": args.obstacles,
        "terrain": args.terrain,
        "episodes": len(results),
        "success_rate": len(successes) / len(results),
        "mean_steps_on_success": float(np.mean([r["steps"] for r in successes])) if successes else None,
        "mean_final_distance": float(np.mean([r["final_distance"] for r in results])),
        "mean_collisions": float(np.mean([r["collisions"] or 0 for r in results])),
        "out_of_bounds": sum(1 for r in results if r["out_of_bounds"]),
        "results": results,
    }
    name = args.checkpoint.stem if args.policy in ("bc", "seq") else args.policy
    out = REPORTS / "eval" / f"{args.task}-{name}-o{args.obstacles}-{args.terrain}-{datetime.now():%Y%m%d-%H%M%S}.json"
    write_report(out, **summary)
    print(f"{args.policy}: success rate {summary['success_rate']:.0%} over {len(results)} episodes, report {out}")


if __name__ == "__main__":
    main()
