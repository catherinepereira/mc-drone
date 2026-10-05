"""
Records training data for the cell skill: the experts (seeing through the block reader) work their jobs with
flight noise, and every step where the planner has a goal is saved with the expert's clean action as the label (DART).
Tasks that hand the drone a job use the brain's job planners.
With --skill a trained skill flies a share of the goal steps and the scripted controller only labels them (DAgger),
so the data covers the states the skill gets itself into.
Frames are stored at the skill's resolution, so this needs no recording in the mod
"""

from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
from mcdrone import DroneEnv

from .jobs import make_planner
from .reader import Reader
from .seq_model import state_features
from .skill import SkillAgent, frame, goal_features
from .tool_experts import TOOLS, make_expert

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "data" / "skill"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--task", default="harvest_crops")
    parser.add_argument("--terrain", choices=["flat", "rough", "cave"], default="flat")
    parser.add_argument("--episodes", type=int, default=40)
    parser.add_argument("--seed", type=int, default=300_000, help="first env seed, clear of demos, reader data, and evaluation")
    parser.add_argument("--noise", type=float, default=0.2)
    parser.add_argument("--obstacles", type=int, default=4)
    parser.add_argument("--max-steps", type=int, default=1200)
    parser.add_argument("--reader", type=Path, default=ROOT / "checkpoints" / "reader.pt")
    parser.add_argument("--skill", type=Path, default=None, help="a trained skill that flies a share of the goal steps")
    parser.add_argument("--beta", type=float, default=0.5, help="share of goal steps the skill flies")
    parser.add_argument("--name", default=None, help="data folder name, task-terrain by default")
    args = parser.parse_args()

    reader = Reader(args.reader)
    agent = SkillAgent(args.skill) if args.skill is not None else None
    rng = np.random.default_rng(args.seed)
    out = OUT / (args.name or f"{args.task}-{args.terrain}")
    out.mkdir(parents=True, exist_ok=True)
    env = DroneEnv(
        task=args.task, tools=True, streams=("rgb", "depth"), action_pause_ms=0,
        task_options={"obstacles": args.obstacles, "terrain": args.terrain, "maxSteps": args.max_steps},
    )
    try:
        for i in range(args.episodes):
            obs, info = env.reset(seed=args.seed + i)
            job = info["state"].get("job")
            expert = make_planner(job, env.client.mask_ids, reader) if job else make_expert(args.task, env.client.mask_ids, reader=reader)
            rows: dict[str, list] = {k: [] for k in ("rgb", "depth", "state", "goal", "move", "fire")}
            terminated = truncated = False
            while not (terminated or truncated):
                state = info["state"]
                label = expert.act(state, obs)
                intent = expert.intent
                fired = TOOLS[label["tool"]] in ("break", "place")
                if agent is not None and intent is not None and rng.random() < args.beta:
                    executed = agent.act(state, obs, intent)
                else:
                    executed = dict(label, move=np.clip(label["move"] + rng.normal(0.0, args.noise, size=5).astype(np.float32), -1.0, 1.0))
                if intent is not None:
                    rgb, depth = frame(obs)
                    rows["rgb"].append(rgb)
                    rows["depth"].append(depth)
                    rows["state"].append(state_features(state))
                    rows["goal"].append(goal_features(state, intent))
                    rows["move"].append(np.asarray(label["move"], dtype=np.float32))
                    rows["fire"].append(1.0 if fired else 0.0)
                obs, _, terminated, truncated, info = env.step(executed)
            if rows["move"]:
                np.savez(out / f"{args.seed + i}.npz", **{k: np.stack(v) for k, v in rows.items()})
            print(f"episode {i + 1}/{args.episodes}: {len(rows['move'])} steps with a goal, {'success' if info['episode'].get('success') else 'not finished'}", flush=True)
    finally:
        env.close()


if __name__ == "__main__":
    main()
