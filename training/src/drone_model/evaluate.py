"""Runs a trained policy in the live mod on fixed seeds and reports success rate"""

from __future__ import annotations

import argparse
import json
import time
from datetime import datetime
from pathlib import Path

import numpy as np
import torch
from mcdrone import DroneEnv

from .model import DronePolicy, to_image
from .seq_model import SeqAgent
from .reader import Reader
from .skill import with_skill
from .tool_experts import make_expert

ROOT = Path(__file__).resolve().parents[2]
# collect.py seeds start at 0, so eval seeds stay well clear of them
EVAL_SEED = 100_000


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--checkpoint", type=Path, default=ROOT / "checkpoints" / "bc.pt")
    parser.add_argument("--episodes", type=int, default=20)
    parser.add_argument(
        "--policy", choices=["bc", "seq", "expert", "random"], default="bc", help="bc runs any DronePolicy checkpoint, including PPO ones, seq a SeqToolPolicy"
    )
    parser.add_argument("--obstacles", type=int, default=8)
    parser.add_argument("--terrain", choices=["flat", "rough", "cave"], default="flat")
    parser.add_argument("--task", default="navigate_to")
    parser.add_argument("--perception", choices=["reader", "mask"], default="reader", help="what experts see with, mask is the mod's ground truth")
    parser.add_argument("--skill", type=Path, default=None, help="a cell skill checkpoint to fly, aim, and fire for the expert's planner")
    parser.add_argument("--reader", type=Path, default=Path(__file__).resolve().parents[2] / "checkpoints" / "reader.pt")
    args = parser.parse_args()
    reader = Reader(args.reader) if args.perception == "reader" else None

    if args.task != "navigate_to" and args.policy not in ("expert", "seq"):
        raise SystemExit("tool tasks run with --policy expert or seq")
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    model = None
    if args.policy == "bc":
        model = DronePolicy().to(device)
        model.load_state_dict(torch.load(args.checkpoint, map_location=device, weights_only=True)["model"])
        model.eval()

    # the expert needs the marker, the learned policy never sees it
    # the expert maps the arena from depth and the semantic mask, so it gets the mask stream too
    streams = ("rgb", "depth", "mask") if args.policy in ("expert", "seq") else ("rgb", "depth")
    env = DroneEnv(task=args.task, tools=args.policy in ("expert", "seq") or None, streams=streams, action_pause_ms=0, task_options={"obstacles": args.obstacles, "terrain": args.terrain})
    rng = np.random.default_rng(0)
    results = []
    try:
        for i in range(args.episodes):
            obs, info = env.reset(seed=EVAL_SEED + i)
            expert = with_skill(make_expert(args.task, env.client.mask_ids, reader=reader), args.skill) if args.policy == "expert" else None
            if args.policy == "seq":
                expert = SeqAgent(args.checkpoint, env.client.mask_ids, device)
            terminated = truncated = False
            steps = 0
            start = time.perf_counter()
            while not (terminated or truncated):
                if args.policy in ("expert", "seq"):
                    action = expert.act(info["state"], obs)
                elif args.policy == "random":
                    action = rng.uniform(-1, 1, size=5).astype(np.float32)
                else:
                    with torch.no_grad():
                        image = to_image(torch.from_numpy(obs["rgb"])[None].to(device), torch.from_numpy(obs["depth"])[None].to(device))
                        action = model(image, torch.from_numpy(obs["state"][:6])[None].to(device))[0].cpu().numpy()
                obs, reward, terminated, truncated, info = env.step(action)
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
            outcome = "success" if info["episode"].get("success") else "out of bounds" if info["episode"].get("outOfBounds") else "timeout"
            metrics = info["episode"].get("metrics")
            print(f"seed {EVAL_SEED + i}: {outcome} in {steps} steps" + (f", metrics {metrics}" if metrics else ""), flush=True)
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
    out = ROOT / "reports" / f"eval-{args.task}-{name}-o{args.obstacles}-{args.terrain}-{datetime.now():%Y%m%d-%H%M%S}.json"
    out.parent.mkdir(exist_ok=True)
    out.write_text(json.dumps(summary, indent=2))
    print(f"{args.policy}: success rate {summary['success_rate']:.0%} over {len(results)} episodes, report {out}")


if __name__ == "__main__":
    main()
