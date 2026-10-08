"""
PPO fine-tuning of the behavior cloning policy in the live mod (Schulman et al. 2017).

The actor starts from the BC checkpoint and the critic's encoder from the BC encoder.
The first updates train only the critic, so early advantages aren't noise.
An anchor loss keeps the actor's mean near the BC policy while the critic is still poor
"""

from __future__ import annotations

import argparse
import copy
import json
import time
from datetime import datetime
from pathlib import Path

import numpy as np
import torch
from mcdrone import DroneEnv
from torch import nn

from ..paths import CHECKPOINTS, REPORTS
from ..policies.cnn import DronePolicy, to_image
from ..torch_utils import load_weights, pick_device



class Critic(nn.Module):
    def __init__(self, policy: DronePolicy) -> None:
        super().__init__()
        self.encoder = copy.deepcopy(policy.encoder)
        self.head = nn.Sequential(nn.Linear(128 * 12 + 6, 256), nn.ReLU(inplace=True), nn.Linear(256, 1))

    def forward(self, image: torch.Tensor, state: torch.Tensor) -> torch.Tensor:
        return self.head(torch.cat([self.encoder(image), state], dim=1)).squeeze(1)


def gae(rewards, values, dones, last_value, gamma: float, lam: float) -> tuple[np.ndarray, np.ndarray]:
    """Generalized advantage estimation, dones[t] marks that step t ended its episode"""
    advantages = np.zeros_like(rewards)
    running = 0.0
    for t in reversed(range(len(rewards))):
        next_value = last_value if t == len(rewards) - 1 else values[t + 1]
        not_done = 1.0 - dones[t]
        delta = rewards[t] + gamma * next_value * not_done - values[t]
        running = delta + gamma * lam * not_done * running
        advantages[t] = running
    return advantages, advantages + values


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--init", type=Path, default=CHECKPOINTS / "bc.pt")
    parser.add_argument("--out", type=Path, default=CHECKPOINTS / "ppo.pt")
    parser.add_argument("--updates", type=int, default=20)
    parser.add_argument("--rollout", type=int, default=1024)
    parser.add_argument("--epochs", type=int, default=4)
    parser.add_argument("--minibatch", type=int, default=128)
    parser.add_argument("--actor-lr", type=float, default=3e-5)
    parser.add_argument("--critic-lr", type=float, default=3e-4)
    parser.add_argument("--clip", type=float, default=0.2)
    parser.add_argument("--gamma", type=float, default=0.99)
    parser.add_argument("--lam", type=float, default=0.95)
    parser.add_argument("--init-std", type=float, default=0.25)
    parser.add_argument("--anchor", type=float, default=0.5, help="weight of the pull toward the BC mean, decays to 0 by the last update")
    parser.add_argument("--critic-warmup", type=int, default=2)
    parser.add_argument("--obstacles", type=int, default=8)
    parser.add_argument("--seed", type=int, default=1)
    args = parser.parse_args()

    torch.manual_seed(args.seed)
    device = pick_device()
    actor = DronePolicy().to(device)
    load_weights(actor, args.init, device)
    anchor_policy = copy.deepcopy(actor).eval()
    critic = Critic(actor).to(device)
    log_std = nn.Parameter(torch.full((5,), float(np.log(args.init_std)), device=device))
    # BatchNorm statistics stay at their BC values, RL batches are too correlated to re-estimate them
    actor.eval()
    critic.eval()
    actor_opt = torch.optim.Adam([*actor.parameters(), log_std], lr=args.actor_lr)
    critic_opt = torch.optim.Adam(critic.parameters(), lr=args.critic_lr)

    env = DroneEnv(hide_marker=True, streams=("rgb", "depth"), task_options={"obstacles": args.obstacles})
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    log_path = REPORTS / "ppo" / f"{stamp}.jsonl"
    log_path.parent.mkdir(parents=True, exist_ok=True)
    args.out.parent.mkdir(parents=True, exist_ok=True)

    def tensors(obs):
        image = to_image(torch.from_numpy(obs["rgb"])[None].to(device), torch.from_numpy(obs["depth"])[None].to(device))
        return image, torch.from_numpy(obs["state"])[None].to(device)

    best_success = -1.0
    obs, info = env.reset(seed=args.seed)
    episode_return, episode_returns, episode_success = 0.0, [], []
    try:
        for update in range(1, args.updates + 1):
            start = time.perf_counter()
            images, states, actions, logps, rewards, dones, values = [], [], [], [], [], [], []
            with torch.no_grad():
                for _ in range(args.rollout):
                    image, state = tensors(obs)
                    mean = actor(image, state)
                    dist = torch.distributions.Normal(mean, log_std.exp())
                    action = dist.sample()
                    images.append(image.squeeze(0).half().cpu())
                    states.append(state.squeeze(0).cpu())
                    actions.append(action.squeeze(0).cpu())
                    logps.append(dist.log_prob(action).sum().item())
                    values.append(critic(image, state).item())
                    obs, reward, terminated, truncated, info = env.step(action.squeeze(0).cpu().numpy())
                    rewards.append(reward)
                    dones.append(float(terminated or truncated))
                    episode_return += reward
                    if terminated or truncated:
                        episode_returns.append(episode_return)
                        episode_success.append(float(terminated))
                        episode_return = 0.0
                        obs, info = env.reset()
                image, state = tensors(obs)
                last_value = critic(image, state).item()
            rollout_seconds = time.perf_counter() - start

            adv, returns = gae(np.asarray(rewards, np.float32), np.asarray(values, np.float32), np.asarray(dones, np.float32), last_value, args.gamma, args.lam)
            adv_t = torch.from_numpy((adv - adv.mean()) / (adv.std() + 1e-8)).to(device)
            ret_t = torch.from_numpy(returns).to(device)
            img_t = torch.stack(images).to(device).float()
            st_t = torch.stack(states).to(device)
            act_t = torch.stack(actions).to(device)
            logp_t = torch.tensor(logps, device=device)
            anchor_w = args.anchor * max(0.0, 1.0 - (update - 1) / max(1, args.updates - 1))
            train_actor = update > args.critic_warmup

            stats = {"policy_loss": 0.0, "value_loss": 0.0, "anchor_loss": 0.0, "clip_frac": 0.0}
            batches = 0
            for _ in range(args.epochs):
                for idx in torch.randperm(len(act_t), device=device).split(args.minibatch):
                    value_loss = (critic(img_t[idx], st_t[idx]) - ret_t[idx]).pow(2).mean()
                    critic_opt.zero_grad(set_to_none=True)
                    value_loss.backward()
                    nn.utils.clip_grad_norm_(critic.parameters(), 1.0)
                    critic_opt.step()
                    stats["value_loss"] += value_loss.item()
                    if train_actor:
                        mean = actor(img_t[idx], st_t[idx])
                        dist = torch.distributions.Normal(mean, log_std.exp())
                        ratio = (dist.log_prob(act_t[idx]).sum(1) - logp_t[idx]).exp()
                        surrogate = torch.min(ratio * adv_t[idx], ratio.clamp(1 - args.clip, 1 + args.clip) * adv_t[idx])
                        with torch.no_grad():
                            anchor_mean = anchor_policy(img_t[idx], st_t[idx])
                        anchor_loss = (mean - anchor_mean).pow(2).mean()
                        policy_loss = -surrogate.mean()
                        actor_opt.zero_grad(set_to_none=True)
                        (policy_loss + anchor_w * anchor_loss).backward()
                        nn.utils.clip_grad_norm_([*actor.parameters(), log_std], 0.5)
                        actor_opt.step()
                        stats["policy_loss"] += policy_loss.item()
                        stats["anchor_loss"] += anchor_loss.item()
                        stats["clip_frac"] += ((ratio - 1).abs() > args.clip).float().mean().item()
                    batches += 1
            stats = {k: v / batches for k, v in stats.items()}

            recent = episode_success[-20:]
            success = float(np.mean(recent)) if recent else 0.0
            row = {
                "update": update,
                "steps": update * args.rollout,
                "steps_per_second": args.rollout / rollout_seconds,
                "episodes": len(episode_success),
                "success_recent": success,
                "return_recent": float(np.mean(episode_returns[-20:])) if episode_returns else None,
                "std": log_std.exp().mean().item(),
                "anchor_weight": anchor_w,
                "actor_trained": train_actor,
                **stats,
            }
            with open(log_path, "a", encoding="utf-8") as f:
                f.write(json.dumps(row) + "\n")
            print(
                f"update {update:3d} success {success:.0%} return {row['return_recent'] or 0:.2f} std {row['std']:.3f} "
                f"value {stats['value_loss']:.3f} clip {stats['clip_frac']:.2f} {row['steps_per_second']:.0f} sps",
                flush=True,
            )
            if train_actor and len(recent) >= 5 and success >= best_success:
                best_success = success
                torch.save({"model": actor.state_dict(), "update": update, "success_recent": success}, args.out)
        torch.save({"model": actor.state_dict(), "update": args.updates}, args.out.with_name(args.out.stem + "-last.pt"))
    finally:
        env.close()
    print(f"best recent success {best_success:.0%}, checkpoint {args.out}, log {log_path}")


if __name__ == "__main__":
    main()
