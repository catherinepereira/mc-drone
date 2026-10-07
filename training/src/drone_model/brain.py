"""
The drone brain: runs jobs with the block reader, a voxel memory, a job planner, and optionally the learned cell skill,
and publishes the memory to the dashboard as it changes. A battery keeper flies the drone home to charge when it must,
see energy.py

    python -m drone_model.brain                     run every job started in game or on the dashboard
    python -m drone_model.brain --task replicate_build --episodes 10    evaluate on training arenas

A copy job's read of its source box is also saved as a .schem in reports/
"""

from __future__ import annotations

import argparse
import json
from datetime import datetime
from pathlib import Path

from mcdrone import DroneEnv

from .energy import BatteryKeeper, EnergyModel
from .paths import CHECKPOINTS, REPORTS, SCHEMATICS
from .experts.jobs import make_planner
from .perception.reader import Reader
from .policies.skill import with_skill
from .experts.tools import tool_action

EVAL_SEED = 100_000
# ticks per step while docked, a charge from empty takes minutes of game time
CHARGE_TICKS = 50
JOB_TASKS = (
    "copy_region", "build_schematic", "mine_region", "harvest_region", "return_home",
    "replicate_build", "harvest_crops", "copy_build", "schematic_build", "mine_deposit", "gather_build",
)


def job_focus(job: dict) -> list[int]:
    """The smallest box around every box the job names, what the dashboard's memory panel shows"""
    boxes = [job[k] for k in ("source", "dest", "region") if k in job]
    if "station" in job:
        boxes.append(job["station"] * 2)
    return [min(b[i] for b in boxes) for i in range(3)] + [max(b[i] for b in boxes) for i in range(3, 6)]


def run_job(env: DroneEnv, obs: dict, info: dict, reader: Reader, args, energy: EnergyModel) -> dict:
    job = info["state"]["job"]
    planner = with_skill(make_planner(job, env.client.mask_ids, reader, args.schematics), args.skill)
    keeper = BatteryKeeper(planner, energy, job)
    planner.errand = keeper
    focus = job_focus(job)
    saved = False
    terminated = truncated = False
    while not (terminated or truncated):
        action = planner.act(info["state"], obs)
        step = planner.memory.step if planner.memory is not None else 0
        if step == 1 or planner.changes:
            # an empty snapshot at step 1 starts a new history on the dashboard
            env.client.publish_memory(step, [c.to_json() for c in planner.changes], snapshot=[] if step == 1 else None, focus=focus)
        if getattr(planner, "read_schematic", None) is not None and not saved:
            out = REPORTS / "reads" / f"{datetime.now():%Y%m%d-%H%M%S}.schem"
            planner.read_schematic.save(out)
            print(f"read the source box, saved {out}", flush=True)
            saved = True
        env.ticks_per_step = CHARGE_TICKS if keeper.charging else 1
        obs, _, terminated, truncated, info = env.step(action)
    env.ticks_per_step = 1
    keeper.finish(bool(info["episode"].get("success")))
    if keeper.trips:
        print(f"flew home to charge {keeper.trips} times", flush=True)
    return info["episode"]


def watch(env: DroneEnv, reader: Reader, args, energy: EnergyModel) -> None:
    client = env.client
    last = None
    while True:
        print("waiting for a job", flush=True)

        def started(s: dict) -> bool:
            episode = s.get("episode") or {}
            return s.get("task") in JOB_TASKS and episode.get("id") not in (None, last) and not episode.get("done")

        client.wait_status(started, timeout=10**9)
        last = client.status["episode"]["id"]
        print(f"running {client.status['task']}", flush=True)
        obs, _, _, _, info = env.step(tool_action([0, 0, 0, 0, 0]))
        episode = run_job(env, obs, info, reader, args, energy)
        print(f"{client.status['task']} {'done' if episode.get('success') else 'stopped'} after {episode.get('step')} steps", flush=True)


def evaluate(env: DroneEnv, reader: Reader, args, energy: EnergyModel) -> None:
    results = []
    for i in range(args.episodes):
        obs, info = env.reset(seed=EVAL_SEED + i)
        episode = run_job(env, obs, info, reader, args, energy)
        results.append({"seed": EVAL_SEED + i, "success": bool(episode.get("success")), "steps": episode.get("step"), "metrics": episode.get("metrics")})
        print(f"seed {EVAL_SEED + i}: {'success' if episode.get('success') else 'failed'} in {episode.get('step')} steps, metrics {episode.get('metrics')}", flush=True)
    rate = sum(r["success"] for r in results) / len(results)
    out = REPORTS / "brain" / f"{args.task}-s{args.size}-{args.terrain}-{args.perception}-{datetime.now():%Y%m%d-%H%M%S}.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps({"task": args.task, "terrain": args.terrain, "skill": str(args.skill), "success_rate": rate, "results": results}, indent=2))
    print(f"success {rate:.0%}, report {out}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--task", choices=JOB_TASKS, default=None, help="evaluate on this task's arenas instead of waiting for jobs")
    parser.add_argument("--episodes", type=int, default=10)
    parser.add_argument("--terrain", choices=["flat", "rough", "cave"], default="flat")
    parser.add_argument("--obstacles", type=int, default=4)
    parser.add_argument("--size", type=int, default=5, help="structure or deposit side for the copy, build, and mine arenas")
    parser.add_argument("--perception", choices=["vision", "scan"], default="vision", help="scan hands the drone each job box read from the world")
    parser.add_argument("--reader", type=Path, default=CHECKPOINTS / "reader.pt")
    parser.add_argument("--skill", type=Path, default=None, help="a trained cell skill flies the goals, the scripted controller does without one")
    parser.add_argument("--schematics", type=Path, default=SCHEMATICS, help="the game's schematics folder, for build jobs")
    args = parser.parse_args()

    reader = Reader(args.reader)
    # training arenas don't run the battery down, so only player jobs teach the model
    energy = EnergyModel() if args.task is None else EnergyModel(path=None)
    if args.task is None:
        # the player's action pause stays on for jobs they start
        env = DroneEnv(tools=True, streams=("rgb", "depth"))
    else:
        env = DroneEnv(task=args.task, tools=True, streams=("rgb", "depth"), action_pause_ms=0, task_options={"obstacles": args.obstacles, "terrain": args.terrain, "size": args.size, "perception": args.perception})
    try:
        if args.task is None:
            watch(env, reader, args, energy)
        else:
            evaluate(env, reader, args, energy)
    finally:
        env.close()


if __name__ == "__main__":
    main()
