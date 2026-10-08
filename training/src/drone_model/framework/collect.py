"""
Collects a policy's training data. Its teacher flies the task with flight noise and labels every step with its clean
action (DART, Laskey et al. 2017). With --dagger a trained policy flies a share of the steps and the teacher still labels
every one, so the data covers the states the policy gets itself into (DAgger, Ross et al. 2011). --recordings turns
episodes the mod recorded with expert labels (collect.demos) into the same rows

    python -m drone_model.framework.collect --policy skill --task hunt_mobs --episodes 40
    python -m drone_model.framework.collect --policy skill --task hunt_mobs --dagger checkpoints/skill.pt
    python -m drone_model.framework.collect --policy navigate --recordings navigate_to
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
from mcdrone import DroneEnv, list_episodes
from mcdrone.dataset import action_array

from .. import labels as expert_labels
from ..experts.base import TOOLS, tool_action
from ..paths import CHECKPOINTS, DATA
from ..perception.reader import Reader
from ..torch_utils import pick_device
from .data import run_dir, save_episode
from .registry import get
from .spec import PolicySpec, Step


def collect(spec: PolicySpec, args, out: Path) -> None:
    reader = Reader(args.reader) if args.teacher_sees == "reader" else None
    rng = np.random.default_rng(args.seed)
    options = {"obstacles": args.obstacles, "terrain": args.terrain, "size": args.size, "perception": args.perception}
    if args.max_steps:
        options["maxSteps"] = args.max_steps
    env = DroneEnv(task=args.task, tools=True, streams=spec.streams, action_pause_ms=0, task_options=options)
    agent = None
    try:
        for i in range(args.episodes):
            seed = args.seed + i
            obs, info = env.reset(seed=seed)
            teacher = spec.teacher(args.task, info, env.client.mask_ids, reader)
            if args.dagger is not None:
                agent = agent or spec.agent(spec.load(args.dagger, pick_device()), env.client.mask_ids)
                agent.reset()
            rows, prev = [], None
            # an arena can be done before its first step
            terminated, truncated = bool(info["episode"].get("done")), False
            while not (terminated or truncated):
                state = info["state"]
                label = teacher.act(state, obs)
                step = Step(state, obs, label, prev, env.client.mask_ids, teacher)
                row = spec.record(step)
                if row is not None:
                    rows.append(row)
                if agent is not None and rng.random() < args.beta:
                    action = agent.act(step)
                else:
                    action = expert_labels.with_flight_noise(label, rng, args.noise)
                if agent is not None:
                    agent.executed(action)
                prev = action
                obs, _, terminated, truncated, info = env.step(action)
            save_episode(out / f"{seed}.npz", rows)
            print(f"episode {i + 1}/{args.episodes}: {len(rows)} rows, {'success' if info['episode'].get('success') else 'not finished'}", flush=True)
    finally:
        env.close()


def from_recordings(spec: PolicySpec, task: str, out: Path, every_outcome: bool, data: Path = DATA) -> None:
    """Rows from episodes the mod recorded, labeled by the expert.jsonl beside each one"""
    mask_ids = json.loads((data / task / "mask_ids.json").read_text(encoding="utf-8"))
    count = 0
    for ep in list_episodes(data, task):
        labels = expert_labels.read(ep.path)
        # a failed expert demo can hold a misread build, a DAgger episode's labels are the expert's whatever the outcome
        if labels is None or not (every_outcome or ep.meta.get("outcome") == "success" or (ep.path / "dagger").exists()):
            continue
        rows, prev = [], None
        for row in ep.steps:
            n = row["step"]
            if row["action"] is None or n >= len(labels):
                continue
            label = labels[n] if isinstance(labels[n], dict) else tool_action(labels[n])
            obs = {"rgb": ep.rgb(n), "depth": ep.depth(n), "mask": ep.mask(n) if ep.has("mask", n) else None}
            record = spec.record(Step(row["state"], obs, label, prev, mask_ids))
            if record is not None:
                rows.append(record)
            prev = {"move": action_array(row["action"]), "tool": TOOLS.index(row["action"].get("tool", "none"))}
        save_episode(out / f"{ep.meta['id']}.npz", rows)
        count += 1
    print(f"{count} recorded {task} episodes into {out}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--policy", required=True)
    parser.add_argument("--task", default="navigate_to")
    parser.add_argument("--episodes", type=int, default=40)
    parser.add_argument("--seed", type=int, default=300_000, help="first env seed, evaluation uses 100000 and up")
    parser.add_argument("--terrain", choices=["flat", "rough", "cave"], default="flat")
    parser.add_argument("--obstacles", type=int, default=4)
    parser.add_argument("--size", type=int, default=5, help="structure or deposit side for the copy, build, and mine arenas")
    parser.add_argument("--max-steps", type=int, default=None)
    parser.add_argument("--perception", choices=["vision", "scan"], default="vision", help="scan hands the drone the job's blocks or mobs")
    parser.add_argument("--teacher-sees", choices=["reader", "mask"], default="reader", help="mask is the mod's ground truth, for data the reader can't label yet")
    parser.add_argument("--reader", type=Path, default=CHECKPOINTS / "reader.pt")
    parser.add_argument("--noise", type=float, default=0.2, help="flight noise on the teacher's executed action")
    parser.add_argument("--dagger", type=Path, default=None, help="a trained checkpoint that flies a share of the steps")
    parser.add_argument("--beta", type=float, default=0.5, help="share of steps the --dagger policy flies")
    parser.add_argument("--recordings", default=None, help="a task folder of recorded episodes to turn into rows instead of flying")
    parser.add_argument("--every-outcome", action="store_true", help="with --recordings, keep failed expert episodes too")
    parser.add_argument("--run", default=None, help="data folder under data/policies/<policy>, task-terrain by default")
    args = parser.parse_args()

    spec = get(args.policy)
    if args.recordings:
        from_recordings(spec, args.recordings, run_dir(spec.name, args.run or f"{args.recordings}-recorded"), args.every_outcome)
        return
    default = f"{args.task}-{args.terrain}" + ("-dagger" if args.dagger else "")
    collect(spec, args, run_dir(spec.name, args.run or default))


if __name__ == "__main__":
    main()
