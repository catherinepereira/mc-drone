"""
The drone brain: runs jobs with the block reader, a voxel memory, a job planner, and optionally the learned cell skill,
and publishes the memory to the dashboard as it changes. A battery keeper flies the drone home to charge when it must,
see energy.py. Every drone with a job gets its own worker, so the player's drones work at once

    python -m drone_model.brain                     run every job started in game or on the dashboard
    python -m drone_model.brain --task replicate_build --episodes 10    evaluate on training arenas
    python -m drone_model.brain --fleet replicate_build,harvest_crops --episodes 5    several drones, one shared arena

A copy job's read of its source box is also saved as a .schem in reports/
"""

from __future__ import annotations

import argparse
import threading
from datetime import datetime
from pathlib import Path

from mcdrone import DroneClient, DroneEnv

from .energy import BatteryKeeper, EnergyModel
from .paths import CHECKPOINTS, REPORTS, SCHEMATICS
from .experts.jobs import make_planner
from .perception.reader import Reader
from .policies.skill import with_skill
from .experts.base import tool_action
from .torch_utils import write_report

EVAL_SEED = 100_000
# ticks per step while docked, a charge from empty takes minutes of game time
CHARGE_TICKS = 50
# a drone's worker lets it go after this long without a new job
IDLE_SECONDS = 20.0
JOB_TASKS = (
    "copy_region", "build_schematic", "mine_region", "harvest_region", "return_home", "patrol_region", "guard_region", "fly_to", "seek_block",
    "follow_player", "replicate_build", "harvest_crops", "copy_build", "schematic_build", "mine_deposit", "gather_build", "patrol_area",
    "hunt_mobs", "goto_point", "find_block", "follow_mob",
)


def job_focus(job: dict) -> list[int] | None:
    """The smallest box around every box the job names, what the dashboard's memory panel shows, None for a follow"""
    boxes = [job[k] for k in ("source", "dest", "region") if k in job]
    boxes += [job[k] * 2 for k in ("station", "point") if k in job]
    if not boxes:
        return None
    return [min(b[i] for b in boxes) for i in range(3)] + [max(b[i] for b in boxes) for i in range(3, 6)]


def run_job(env: DroneEnv, obs: dict, info: dict, reader: Reader, args, energy: EnergyModel) -> dict:
    job = info["state"]["job"]
    planner = with_skill(make_planner(job, env.client.mask_ids, reader, args.schematics), args.skill)
    keeper = BatteryKeeper(planner, energy, job)
    planner.errand = keeper
    focus = job_focus(job)
    saved = False
    # an arena can be done before its first step
    terminated, truncated = bool(info["episode"].get("done")), False
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


def drone_entry(status: dict, drone: int) -> dict:
    return next((d for d in status.get("drones", []) if d["id"] == drone), {})


def fly(drone: int, name: str, reader: Reader, args, energy: EnergyModel) -> None:
    """
    Flies one drone's jobs as its controller, one after another while its queue continues them, and lets the drone go
    once no new job has started for IDLE_SECONDS
    """
    env = DroneEnv(tools=True, streams=("rgb", "depth"), drone=drone)
    last = None
    try:
        while True:
            def started(s: dict) -> bool:
                episode = drone_entry(s, drone).get("episode") or {}
                return episode.get("task") in JOB_TASKS and episode.get("id") != last and not episode.get("done")

            try:
                env.client.wait_status(started, timeout=IDLE_SECONDS)
            except TimeoutError:
                return
            episode = drone_entry(env.client.status, drone)["episode"]
            last = episode["id"]
            print(f"{name}: running {episode['task']}", flush=True)
            obs, _, _, _, info = env.step(tool_action([0, 0, 0, 0, 0]))
            result = run_job(env, obs, info, reader, args, energy)
            print(f"{name}: {episode['task']} {'done' if result.get('success') else 'stopped'} after {result.get('step')} steps", flush=True)
    finally:
        env.close()


def watch(reader: Reader, args, energy: EnergyModel) -> None:
    """Watches the player's drones and starts a worker for each one a job starts on that nobody flies yet"""
    workers: dict[int, threading.Thread] = {}

    def unflown(s: dict) -> bool:
        return any(needs_worker(d) for d in s.get("drones", []))

    def needs_worker(d: dict) -> bool:
        episode = d.get("episode") or {}
        worker = workers.get(d["id"])
        return episode.get("task") in JOB_TASKS and not episode.get("done") and d.get("controller") is None and (worker is None or not worker.is_alive())

    with DroneClient(role="observer", client_name="brain") as observer:
        print("waiting for jobs", flush=True)
        while True:
            try:
                observer.wait_status(unflown, timeout=60)
            except TimeoutError:
                continue
            for d in observer.status["drones"]:
                if needs_worker(d):
                    worker = threading.Thread(target=fly, args=(d["id"], d["name"], reader, args, energy), name=f"drone-{d['id']}", daemon=True)
                    workers[d["id"]] = worker
                    worker.start()


def evaluate(env: DroneEnv, reader: Reader, args, energy: EnergyModel, task: str, fleet: dict | None = None) -> None:
    """
    Runs args.episodes arenas of task and writes a report. With fleet ({"group", "size", "member"}) each arena is shared
    with the fleet's other drones, and starts once all of them finished the last one
    """
    member = fleet["member"] if fleet else 0
    name = f"{task}-{member}" if fleet else task
    results = []
    for i in range(args.episodes):
        # every member gets its own seed, the first member's lays out the shared arena
        seed = EVAL_SEED + i + 1000 * member
        options = {"fleet": {**fleet, "group": f"{fleet['group']}-{i}"}} if fleet else None
        obs, info = env.reset(seed=seed, options=options)
        episode = run_job(env, obs, info, reader, args, energy)
        results.append({
            "seed": seed, "success": bool(episode.get("success")), "steps": episode.get("step"), "metrics": episode.get("metrics"),
            "collisions": episode.get("collisions"), "droneCollisions": episode.get("droneCollisions"),
        })
        print(
            f"{name} seed {seed}: {'success' if episode.get('success') else 'failed'} in {episode.get('step')} steps, "
            f"{episode.get('droneCollisions')} drone collisions, metrics {episode.get('metrics')}", flush=True,
        )
    rate = sum(r["success"] for r in results) / len(results)
    bumps = sum(r["droneCollisions"] or 0 for r in results) / len(results)
    out = REPORTS / "brain" / f"{task}-s{args.size}-{args.terrain}-{args.perception}{'-fleet' if fleet else ''}-{datetime.now():%Y%m%d-%H%M%S}.json"
    write_report(out, task=task, terrain=args.terrain, skill=str(args.skill), fleet=fleet, success_rate=rate, drone_collisions=bumps, results=results)
    print(f"{name}: success {rate:.0%}, {bumps:.1f} drone collisions an episode, report {out}", flush=True)


def evaluate_fleet(reader: Reader, args, energy: EnergyModel) -> None:
    """Flies the player's first drones together, one per task in args.fleet, each with its own controller"""
    tasks = args.fleet.split(",")
    unknown = [t for t in tasks if t not in JOB_TASKS]
    if unknown:
        raise SystemExit(f"{unknown} aren't job arenas, pick from {JOB_TASKS}")
    with DroneClient(role="observer", client_name="brain-fleet") as observer:
        drones = [d["id"] for d in observer.status["drones"]][: len(tasks)]
    if len(drones) < len(tasks):
        raise SystemExit(f"a fleet of {len(tasks)} needs as many drones, the world has {len(drones)}")
    group = f"brain-{datetime.now():%Y%m%d-%H%M%S}"

    def work(member: int) -> None:
        env = DroneEnv(task=tasks[member], drone=drones[member], tools=True, streams=("rgb", "depth"), action_pause_ms=0, task_options=arena_options(args))
        try:
            evaluate(env, reader, args, energy, tasks[member], {"group": group, "size": len(tasks), "member": member})
        finally:
            env.close()

    workers = [threading.Thread(target=work, args=(m,), name=f"fleet-{m}") for m in range(len(tasks))]
    for w in workers:
        w.start()
    for w in workers:
        w.join()


def arena_options(args) -> dict:
    return {"obstacles": args.obstacles, "terrain": args.terrain, "size": args.size, "perception": args.perception}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--task", choices=JOB_TASKS, default=None, help="evaluate on this task's arenas instead of waiting for jobs")
    parser.add_argument("--fleet", default=None, help="comma-separated tasks, the player's first drones work one each in a shared arena")
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
    if args.fleet is not None:
        evaluate_fleet(reader, args, EnergyModel(path=None))
        return
    if args.task is None:
        # the player's action pause stays on for jobs they start
        watch(reader, args, EnergyModel())
        return
    env = DroneEnv(task=args.task, tools=True, streams=("rgb", "depth"), action_pause_ms=0, task_options=arena_options(args))
    try:
        evaluate(env, reader, args, EnergyModel(path=None), args.task)
    finally:
        env.close()


if __name__ == "__main__":
    main()
