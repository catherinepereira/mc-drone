"""
Records expert demos through the live mod.

Executes the expert action plus noise but labels each step with the clean expert action (DART),
so the policy sees recoveries from the states noise pushes it into.
Labels go to expert.jsonl next to the mod's steps.jsonl, line n matches step n
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
from mcdrone import DroneEnv

from .reader import Reader
from .tool_experts import make_expert

DATA = Path(__file__).resolve().parents[2] / "data"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--episodes", type=int, default=150)
    parser.add_argument("--seed", type=int, default=0, help="first env seed, eval uses 100000 and up")
    parser.add_argument("--noise", type=float, default=0.3)
    parser.add_argument("--obstacles", type=int, default=8)
    parser.add_argument("--terrain", choices=["flat", "rough", "cave"], default="flat")
    parser.add_argument("--task", default="navigate_to")
    parser.add_argument("--perception", choices=["reader", "mask"], default="reader", help="what experts see with, mask is the mod's ground truth")
    parser.add_argument("--reader", type=Path, default=Path(__file__).resolve().parents[2] / "checkpoints" / "reader.pt")
    parser.add_argument("--data", type=Path, default=DATA)
    parser.add_argument("--streams", default="rgb,depth,mask", help="add state to record block reader labels")
    parser.add_argument("--max-steps", type=int, default=None)
    args = parser.parse_args()
    reader = Reader(args.reader) if args.perception == "reader" else None

    rng = np.random.default_rng(args.seed)
    options = {"obstacles": args.obstacles, "terrain": args.terrain}
    if args.max_steps:
        options["maxSteps"] = args.max_steps
    env = DroneEnv(task=args.task, tools=True, streams=tuple(args.streams.split(",")), record=True, action_pause_ms=0, task_options=options)
    successes = 0
    try:
        for i in range(args.episodes):
            obs, info = env.reset(seed=args.seed + i)
            if i == 0:
                # the recurrent policy reads the mask through this table, see seq_model.mask_lookup
                (args.data / args.task).mkdir(parents=True, exist_ok=True)
                (args.data / args.task / "mask_ids.json").write_text(json.dumps(env.client.mask_ids), encoding="utf-8")
                if "state" in env.streams:
                    # the block reader turns state stream ids into its labels through this table
                    (args.data / args.task / "state_names.json").write_text(json.dumps(env.client.state_names), encoding="utf-8")
            expert = make_expert(args.task, env.client.mask_ids, reader=reader)
            labels = []
            terminated = truncated = False
            while not (terminated or truncated):
                label = expert.act(info["state"], obs)
                labels.append({
                    "move": label["move"].tolist(), "tool": int(label["tool"]), "slot": int(label["slot"]), "transfer": label["transfer"].tolist(), "block": label.get("block"),
                })
                # noise only on flight, tools and transfers stay the expert's own
                executed = dict(label, move=np.clip(label["move"] + rng.normal(0.0, args.noise, size=5).astype(np.float32), -1.0, 1.0))
                obs, reward, terminated, truncated, info = env.step(executed)
            successes += int(bool(info["episode"].get("success")))
            episode_dir = args.data / args.task / info["episode"]["id"]
            with open(episode_dir / "expert.jsonl", "w", encoding="utf-8") as f:
                for label in labels:
                    f.write(json.dumps(label) + "\n")
            print(f"episode {i + 1}/{args.episodes} {'success' if info['episode'].get('success') else 'failed'} after {len(labels)} steps", flush=True)
    finally:
        env.close()
    print(f"expert success rate {successes / args.episodes:.2%}")


if __name__ == "__main__":
    main()
