"""
DAgger for SeqToolPolicy (Ross et al. 2011). Each round the policy flies, handing control to the expert on a share of
steps that shrinks every round, while the expert labels every state the drone reaches. The new episodes join the
dataset and the policy is fine-tuned on all of it. The expert only reads the camera and its own pose, so it can
label whatever states the policy wanders into
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
import torch
from mcdrone import DroneEnv

from .seq_model import SeqAgent, prev_features
from .tool_experts import make_expert
from .train_seq import DAGGER_MARK, DATA, ROOT, fit, load_task


def collect_round(env: DroneEnv, task: str, checkpoint: Path, episodes: int, seed: int, beta: float, data: Path, rng: np.random.Generator) -> float:
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    successes = 0
    for i in range(episodes):
        obs, info = env.reset(seed=seed + i)
        expert = make_expert(task, env.client.mask_ids)
        agent = SeqAgent(checkpoint, env.client.mask_ids, device)
        labels = []
        terminated = truncated = False
        while not (terminated or truncated):
            label = expert.act(info["state"], obs)
            labels.append({"move": label["move"].tolist(), "tool": int(label["tool"]), "slot": int(label["slot"]), "transfer": label["transfer"].tolist()})
            action = agent.act(info["state"], obs)
            if rng.random() < beta:
                action = label
            # the policy's next input is what actually flew, not what it proposed
            agent.prev = prev_features(np.asarray(action["move"], dtype=np.float32), int(action["tool"]))
            obs, _, terminated, truncated, info = env.step(action)
        successes += int(bool(info["episode"].get("success")))
        episode_dir = data / task / info["episode"]["id"]
        with open(episode_dir / "expert.jsonl", "w", encoding="utf-8") as f:
            for label in labels:
                f.write(json.dumps(label) + "\n")
        (episode_dir / DAGGER_MARK).touch()
    return successes / episodes


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--task", default="replicate_build")
    parser.add_argument("--init", type=Path, default=None, help="starting checkpoint, default checkpoints/<task>-seq.pt")
    parser.add_argument("--rounds", type=int, default=4)
    parser.add_argument("--first-round", type=int, default=1, help="to resume, the round number to start at, it sets beta and checkpoint names")
    parser.add_argument("--episodes", type=int, default=80, help="episodes collected per round")
    parser.add_argument("--beta", type=float, default=0.5, help="share of steps the expert flies in the first round, halved every round")
    parser.add_argument("--epochs", type=int, default=6, help="fine-tuning epochs per round")
    parser.add_argument("--lr", type=float, default=1e-4)
    parser.add_argument("--obstacles", type=int, default=4)
    parser.add_argument("--terrain", choices=["flat", "rough", "cave"], default="flat")
    parser.add_argument("--seed", type=int, default=50_000, help="first env seed, collect uses 0 and up, evaluation 100000 and up")
    parser.add_argument("--data", type=Path, default=DATA)
    args = parser.parse_args()

    checkpoint = args.init or ROOT / "checkpoints" / f"{args.task}-seq.pt"
    rng = np.random.default_rng(args.seed)
    for r in range(args.first_round - 1, args.rounds):
        beta = args.beta / (2**r)
        env = DroneEnv(task=args.task, tools=True, streams=("rgb", "depth", "mask"), record=True, action_pause_ms=0, task_options={"obstacles": args.obstacles, "terrain": args.terrain})
        try:
            rate = collect_round(env, args.task, checkpoint, args.episodes, args.seed + r * args.episodes, beta, args.data, rng)
        finally:
            env.close()
        print(f"round {r + 1}: beta {beta:.2f}, success while collecting {rate:.0%}", flush=True)
        out = ROOT / "checkpoints" / f"{args.task}-dagger{r + 1}.pt"
        run = fit(load_task(args.data, args.task), out, args.task, args.epochs, args.lr, init=checkpoint, seed=r)
        out.with_suffix(".json").write_text(json.dumps({"round": r + 1, "beta": beta, "collect_success": rate, **run}, indent=2))
        checkpoint = out
    print(f"final checkpoint {checkpoint}")


if __name__ == "__main__":
    main()
