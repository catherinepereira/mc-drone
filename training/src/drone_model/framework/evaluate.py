"""
Runs a trained policy, or its teacher alone, on evaluation seeds and reports the success rate

    python -m drone_model.framework.evaluate --policy navigate --checkpoint checkpoints/ppo.pt --obstacles 16
    python -m drone_model.framework.evaluate --policy skill --task hunt_mobs
    python -m drone_model.framework.evaluate --policy skill --task hunt_mobs --teacher
"""

from __future__ import annotations

import argparse
import time
from datetime import datetime
from pathlib import Path

import numpy as np
from mcdrone import DroneEnv

from ..paths import CHECKPOINTS, REPORTS
from ..perception.reader import Reader
from ..torch_utils import pick_device, write_report
from .registry import get
from .spec import Step

# collection seeds start at 0 and 300000, evaluation stays clear of them
EVAL_SEED = 100_000


def outcome(episode: dict) -> str:
    if episode.get("success"):
        return "success"
    if episode.get("wrecked"):
        return "wrecked"
    return "out of bounds" if episode.get("outOfBounds") else "timeout"


class Runner:
    """Flies episodes with a policy's agent, or its teacher alone, one step at a time"""

    def __init__(self, policy: str, checkpoint: Path | None, teacher_only: bool, reader: Reader | None) -> None:
        self.spec = get(policy)
        self.checkpoint = checkpoint or CHECKPOINTS / f"{self.spec.name}.pt"
        self.teacher_only = teacher_only
        self.reader = reader
        self.model = None if teacher_only else self.spec.load(self.checkpoint, pick_device())
        self.agent = None
        self.teacher = None
        self.prev = None

    def start(self, task: str, info: dict, mask_ids: dict) -> None:
        self.teacher = self.spec.teacher(task, info, mask_ids, self.reader)
        self.mask_ids = mask_ids
        self.prev = None
        if self.model is not None:
            self.agent = self.agent or self.spec.agent(self.model, mask_ids)
            self.agent.reset()

    def act(self, state: dict, obs: dict) -> dict:
        label = self.teacher.act(state, obs)
        action = label if self.agent is None else self.agent.act(Step(state, obs, label, self.prev, self.mask_ids, self.teacher))
        self.prev = action
        return action


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--policy", required=True)
    parser.add_argument("--checkpoint", type=Path, default=None, help="checkpoints/<policy>.pt by default")
    parser.add_argument("--teacher", action="store_true", help="fly the policy's teacher alone")
    parser.add_argument("--task", default="navigate_to")
    parser.add_argument("--episodes", type=int, default=20)
    parser.add_argument("--obstacles", type=int, default=8)
    parser.add_argument("--terrain", choices=["flat", "rough", "cave"], default="flat")
    parser.add_argument("--size", type=int, default=5)
    parser.add_argument("--perception", choices=["vision", "scan"], default="vision")
    parser.add_argument("--teacher-sees", choices=["reader", "mask"], default="reader")
    parser.add_argument("--reader", type=Path, default=CHECKPOINTS / "reader.pt")
    args = parser.parse_args()

    runner = Runner(args.policy, args.checkpoint, args.teacher, Reader(args.reader) if args.teacher_sees == "reader" else None)
    options = {"obstacles": args.obstacles, "terrain": args.terrain, "size": args.size, "perception": args.perception}
    env = DroneEnv(task=args.task, tools=True, streams=runner.spec.streams, action_pause_ms=0, task_options=options)
    results = []
    try:
        for i in range(args.episodes):
            obs, info = env.reset(seed=EVAL_SEED + i)
            runner.start(args.task, info, env.client.mask_ids)
            # an arena can be done before its first step
            terminated, truncated = bool(info["episode"].get("done")), False
            start = time.perf_counter()
            while not (terminated or truncated):
                obs, _, terminated, truncated, info = env.step(runner.act(info["state"], obs))
            ep = info["episode"]
            results.append({
                "seed": EVAL_SEED + i, "success": bool(ep.get("success")), "outcome": outcome(ep), "steps": ep.get("step"),
                "total_reward": ep.get("totalReward"), "collisions": ep.get("collisions"), "damage": ep.get("damage"),
                "metrics": ep.get("metrics"), "seconds": time.perf_counter() - start,
            })
            print(f"seed {EVAL_SEED + i}: {outcome(ep)} in {ep.get('step')} steps, metrics {ep.get('metrics')}", flush=True)
    finally:
        env.close()

    wins = [r for r in results if r["success"]]
    who = "teacher" if args.teacher else (args.checkpoint or runner.checkpoint).stem
    summary = {
        "policy": args.policy, "flown_by": who, "task": args.task, "terrain": args.terrain, "obstacles": args.obstacles,
        "perception": args.perception, "episodes": len(results), "success_rate": len(wins) / len(results),
        "mean_steps_on_success": float(np.mean([r["steps"] for r in wins])) if wins else None,
        "mean_collisions": float(np.mean([r["collisions"] or 0 for r in results])), "results": results,
    }
    out = REPORTS / "eval" / f"{args.task}-{args.policy}-{who}-{args.terrain}-{datetime.now():%Y%m%d-%H%M%S}.json"
    write_report(out, **summary)
    print(f"{args.policy} ({who}): success rate {summary['success_rate']:.0%} over {len(results)} episodes, report {out}")


if __name__ == "__main__":
    main()
