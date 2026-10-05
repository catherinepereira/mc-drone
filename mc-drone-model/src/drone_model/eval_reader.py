"""
How well the block reader and voxel memory read a build: the build expert flies its survey of the reference,
every frame goes through the reader into memory, and the remembered box is compared cell by cell with the real one.
The expert only steers the camera here, what ends up in memory comes from the reader alone.
The memory is published to the dashboard as it fills
"""

from __future__ import annotations

import argparse
import json
from datetime import datetime
from pathlib import Path

import numpy as np
from mcdrone import DroneEnv

from .memory import VoxelMemory
from .reader import Reader
from .tool_experts import make_expert

ROOT = Path(__file__).resolve().parents[2]
EVAL_SEED = 100_000


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--checkpoint", type=Path, default=ROOT / "checkpoints" / "reader.pt")
    parser.add_argument("--episodes", type=int, default=10)
    parser.add_argument("--terrain", choices=["flat", "rough", "cave"], default="flat")
    parser.add_argument("--obstacles", type=int, default=4)
    args = parser.parse_args()

    reader = Reader(args.checkpoint)
    env = DroneEnv(task="replicate_build", tools=True, streams=("rgb", "depth", "mask"), action_pause_ms=0, task_options={"obstacles": args.obstacles, "terrain": args.terrain})
    results = []
    try:
        for i in range(args.episodes):
            obs, info = env.reset(seed=EVAL_SEED + i)
            expert = make_expert("replicate_build", env.client.mask_ids)
            memory = VoxelMemory()
            arena = info["state"]["arena"]
            rx, ry, rz = arena["referenceBase"]
            lo, hi = (rx - 1, ry + 1, rz - 1), (rx + 1, ry + 3, rz + 1)
            truth = {(rx + c["offset"][0], ry + c["offset"][1], rz + c["offset"][2]): c["block"] for c in arena["blueprint"]}
            focus = [lo[0] - 1, ry, lo[2] - 1, hi[0] + 1, hi[1], hi[2] + 1]
            snapshot = []
            while expert.plan is None:
                classes, ripe = reader.read(obs["rgb"], obs["depth"])
                changes = memory.observe(info["state"], obs["depth"], classes, ripe)
                env.client.publish_memory(memory.step, [c.to_json() for c in changes], snapshot=snapshot if memory.step == 1 else None, focus=focus)
                obs, _, terminated, truncated, info = env.step(expert.act(info["state"], obs))
                if terminated or truncated:
                    break
            read = memory.box(lo, hi)
            cells = [(x, y, z) for x in range(lo[0], hi[0] + 1) for y in range(lo[1], hi[1] + 1) for z in range(lo[2], hi[2] + 1)]
            right = sum(1 for c in cells if read.get(c, "minecraft:air") == truth.get(c, "minecraft:air"))
            blocks_right = sum(1 for c, b in truth.items() if read.get(c) == b)
            exact = right == len(cells)
            results.append({"seed": EVAL_SEED + i, "cells_right": right, "cells": len(cells), "blocks_right": blocks_right, "blocks": len(truth), "exact": exact, "steps": memory.step})
            print(f"seed {EVAL_SEED + i}: {right}/{len(cells)} cells, {blocks_right}/{len(truth)} blocks{' exact' if exact else ''} after {memory.step} frames", flush=True)
    finally:
        env.close()
    summary = {
        "checkpoint": str(args.checkpoint),
        "terrain": args.terrain,
        "cell_accuracy": float(np.mean([r["cells_right"] / r["cells"] for r in results])),
        "block_recall": float(np.mean([r["blocks_right"] / r["blocks"] for r in results])),
        "exact_reads": sum(r["exact"] for r in results) / len(results),
        "results": results,
    }
    out = ROOT / "reports" / f"reader-{args.terrain}-{datetime.now():%Y%m%d-%H%M%S}.json"
    out.parent.mkdir(exist_ok=True)
    out.write_text(json.dumps(summary, indent=2))
    print(f"cell accuracy {summary['cell_accuracy']:.1%}, block recall {summary['block_recall']:.1%}, exact reads {summary['exact_reads']:.0%}, report {out}")


if __name__ == "__main__":
    main()
