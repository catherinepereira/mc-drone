"""
Behavior cloning for SeqToolPolicy on expert demos of a tool task.
Trains on whole episodes so the LSTM learns to carry what it saw early (the reference build) to where it acts on it
"""

from __future__ import annotations

import argparse
import json
import time
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import torch
from mcdrone import list_episodes
from mcdrone.dataset import action_array
from torch import nn
from torch.nn import functional as F

from ..paths import CHECKPOINTS, DATA
from ..policies.seq import (
    HEIGHT, MOVE_DIM, WIDTH, SeqToolPolicy, frame_features, inventory_features, mask_lookup, prev_features, state_features,
)
from ..experts.tools import TOOLS

CACHE = f"seq-{WIDTH}x{HEIGHT}"
# DAgger episodes carry this file, they count even when the policy flying them failed
DAGGER_MARK = "dagger"
# place and break fire on a handful of steps per episode, weight them up against "none"
TOOL_WEIGHTS = torch.tensor([1.0, 20.0, 20.0, 2.0, 2.0])


@dataclass
class Sequence:
    rgb: np.ndarray
    depth: np.ndarray
    mask: np.ndarray
    state: np.ndarray
    items: np.ndarray
    counts: np.ndarray
    prev: np.ndarray
    move: np.ndarray
    tool: np.ndarray
    slot: np.ndarray

    def __len__(self) -> int:
        return len(self.move)


def load_sequence(episode, lookup: np.ndarray) -> Sequence | None:
    cache = episode.path / CACHE
    if (cache / "done").exists():
        # memory-mapped, the OS pages episodes in as batches use them instead of the dataset filling RAM
        return Sequence(**{k: np.load(cache / f"{k}.npy", mmap_mode="r") for k in Sequence.__dataclass_fields__})
    labels_file = episode.path / "expert.jsonl"
    if not labels_file.exists():
        return None
    labels = [json.loads(line) for line in labels_file.read_text(encoding="utf-8").splitlines() if line.strip()]
    rows = [r for r in episode.steps if r["action"] is not None and r["step"] < len(labels)]
    if not rows:
        return None
    parts: dict[str, list] = {k: [] for k in Sequence.__dataclass_fields__}
    prev = prev_features(np.zeros(MOVE_DIM, dtype=np.float32), 0)
    for row in rows:
        n = row["step"]
        rgb, depth, mask = frame_features(episode.rgb(n), episode.depth(n), episode.mask(n), lookup)
        items, counts = inventory_features(row["state"])
        label = labels[n]
        for key, value in (
            ("rgb", rgb), ("depth", depth), ("mask", mask), ("state", state_features(row["state"])), ("items", items), ("counts", counts),
            ("prev", prev), ("move", np.asarray(label["move"], dtype=np.float32)), ("tool", label["tool"]), ("slot", label["slot"]),
        ):
            parts[key].append(value)
        # the next step sees what was executed, noise included, the same as when the policy drives
        prev = prev_features(action_array(row["action"]), TOOLS.index(row["action"].get("tool", "none")))
    seq = Sequence(**{k: np.stack(v) if k not in ("tool", "slot") else np.asarray(v, dtype=np.int64) for k, v in parts.items()})
    save_cache(cache, seq)
    return load_sequence(episode, lookup)


def save_cache(cache: Path, seq: Sequence) -> None:
    cache.mkdir(exist_ok=True)
    for key, value in seq.__dict__.items():
        np.save(cache / f"{key}.npy", value)
    (cache / "done").touch()


def load_task(data: Path, task: str) -> list[Sequence]:
    mask_ids = json.loads((data / task / "mask_ids.json").read_text(encoding="utf-8"))
    lookup = mask_lookup(mask_ids)
    out = []
    # expert demos that failed can hold a misread build, DAgger episodes are kept since their labels are the expert's
    episodes = [ep for ep in list_episodes(data, task) if ep.meta.get("outcome") == "success" or (ep.path / DAGGER_MARK).exists()]
    for i, ep in enumerate(episodes):
        seq = load_sequence(ep, lookup)
        if seq is not None:
            out.append(seq)
        if (i + 1) % 25 == 0:
            print(f"loaded {i + 1}/{len(episodes)} episodes", flush=True)
    if not out:
        raise SystemExit(f"no labeled {task} demos in {data}, run python -m drone_model.collect.demos --task {task} first")
    print(f"{len(out)} episodes, {sum(len(s) for s in out)} steps")
    return out


def batch(seqs: list[Sequence], device: torch.device) -> dict[str, torch.Tensor]:
    t = max(len(s) for s in seqs)

    def pad(key: str) -> torch.Tensor:
        first = getattr(seqs[0], key)
        out = np.zeros((len(seqs), t, *first.shape[1:]), dtype=first.dtype)
        for i, s in enumerate(seqs):
            out[i, : len(s)] = getattr(s, key)
        return torch.from_numpy(out).to(device, non_blocking=True)

    out = {k: pad(k) for k in Sequence.__dataclass_fields__}
    valid = np.zeros((len(seqs), t), dtype=bool)
    for i, s in enumerate(seqs):
        valid[i, : len(s)] = True
    out["valid"] = torch.from_numpy(valid).to(device)
    return out


def losses(model: SeqToolPolicy, b: dict[str, torch.Tensor], train: bool) -> dict[str, torch.Tensor]:
    rgb = b["rgb"]
    if train:
        # brightness jitter, light differs between open sky and caves
        scale = torch.empty(rgb.shape[0], 1, 1, 1, 1, device=rgb.device).uniform_(0.75, 1.25)
        rgb = (rgb.float() * scale).clamp(0, 255).to(torch.uint8)
    move, tool, slot, _ = model(rgb, b["depth"], b["mask"], b["state"], b["items"], b["counts"], b["prev"])
    valid = b["valid"]
    move_loss = F.mse_loss(move[valid], b["move"][valid])
    tool_loss = F.cross_entropy(tool[valid], b["tool"][valid], weight=TOOL_WEIGHTS.to(tool.device))
    # the slot matters most on the step a block goes down, and the expert holds the next block's slot while lining up
    placing = valid & (b["tool"] == TOOLS.index("place"))
    # outside the build phase the expert's slot is a default that can point at an empty slot, skip those
    filled = valid & (b["counts"].gather(2, b["slot"].unsqueeze(-1)).squeeze(-1) > 0)
    slot_all = F.cross_entropy(slot[filled], b["slot"][filled]) if filled.any() else move_loss * 0
    slot_place = F.cross_entropy(slot[placing], b["slot"][placing]) if placing.any() else slot_all * 0
    tool_acc = (tool[valid].argmax(-1) == b["tool"][valid]).float().mean()
    place_recall = (tool[placing].argmax(-1) == b["tool"][placing]).float().mean() if placing.any() else tool_acc * 0
    slot_acc = (slot[placing].argmax(-1) == b["slot"][placing]).float().mean() if placing.any() else tool_acc * 0
    total = move_loss + tool_loss + slot_place + 0.1 * slot_all
    return {"loss": total, "move": move_loss, "tool": tool_loss, "slot": slot_place, "tool_acc": tool_acc, "place_recall": place_recall, "slot_acc": slot_acc}


def run_epoch(model, seqs, optimizer, device, batch_size, rng, train: bool) -> dict[str, float]:
    model.train(train)
    order = rng.permutation(len(seqs)) if train else np.arange(len(seqs))
    sums: dict[str, float] = {}
    count = 0
    with torch.set_grad_enabled(train):
        for start in range(0, len(order), batch_size):
            b = batch([seqs[i] for i in order[start : start + batch_size]], device)
            with torch.autocast(device.type, dtype=torch.bfloat16, enabled=device.type == "cuda"):
                out = losses(model, b, train)
            if train:
                optimizer.zero_grad(set_to_none=True)
                out["loss"].backward()
                nn.utils.clip_grad_norm_(model.parameters(), 1.0)
                optimizer.step()
            for k, v in out.items():
                sums[k] = sums.get(k, 0.0) + float(v.detach())
            count += 1
    return {k: v / max(count, 1) for k, v in sums.items()}


def fit(
    seqs: list[Sequence], out_path: Path, task: str, epochs: int, lr: float, batch_size: int = 4, val_fraction: float = 0.1,
    init: Path | None = None, seed: int = 0,
) -> dict:
    """Trains on seqs, keeping the checkpoint with the best validation loss at out_path, and returns the run's history"""
    torch.manual_seed(seed)
    rng = np.random.default_rng(seed)
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    # split by episode, validation never shares an episode with training
    idx = rng.permutation(len(seqs))
    n_val = max(1, int(len(seqs) * val_fraction))
    val = [seqs[i] for i in idx[:n_val]]
    train = [seqs[i] for i in idx[n_val:]]
    print(f"train {len(train)} episodes, val {len(val)} episodes, device {device}", flush=True)

    model = SeqToolPolicy().to(device)
    if init is not None:
        model.load_state_dict(torch.load(init, map_location=device, weights_only=True)["model"])
    optimizer = torch.optim.AdamW(model.parameters(), lr=lr, weight_decay=1e-4)
    scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(optimizer, epochs)
    history = []
    best = float("inf")
    out_path.parent.mkdir(parents=True, exist_ok=True)
    for epoch in range(1, epochs + 1):
        start = time.perf_counter()
        tr = run_epoch(model, train, optimizer, device, batch_size, rng, train=True)
        va = run_epoch(model, val, optimizer, device, batch_size, rng, train=False)
        scheduler.step()
        history.append({"epoch": epoch, "train": tr, "val": va})
        saved = ""
        if va["loss"] < best:
            best = va["loss"]
            torch.save({"model": model.state_dict(), "epoch": epoch, "val": va, "task": task}, out_path)
            saved = " saved"
        print(
            f"epoch {epoch:3d} train {tr['loss']:.3f} val {va['loss']:.3f} (move {va['move']:.3f} tool {va['tool']:.3f} slot {va['slot']:.3f}, "
            f"place recall {va['place_recall']:.2f}, slot acc {va['slot_acc']:.2f}) {time.perf_counter() - start:.0f}s{saved}",
            flush=True,
        )
    return {"best_val_loss": best, "history": history}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--task", default="replicate_build")
    parser.add_argument("--data", type=Path, default=DATA)
    parser.add_argument("--epochs", type=int, default=40)
    # 8 full episodes overflow a 12 GB GPU with the game open, and Windows then pages video memory to RAM
    parser.add_argument("--batch-size", type=int, default=4)
    parser.add_argument("--lr", type=float, default=3e-4)
    parser.add_argument("--val-fraction", type=float, default=0.1)
    parser.add_argument("--init", type=Path, default=None, help="checkpoint to fine-tune from")
    parser.add_argument("--out", type=Path, default=None)
    parser.add_argument("--seed", type=int, default=0)
    args = parser.parse_args()
    out_path = args.out or CHECKPOINTS / f"{args.task}-seq.pt"

    seqs = load_task(args.data, args.task)
    run = fit(seqs, out_path, args.task, args.epochs, args.lr, args.batch_size, args.val_fraction, args.init, args.seed)
    out_path.with_suffix(".json").write_text(json.dumps({"args": {k: str(v) for k, v in vars(args).items()}, **run}, indent=2))
    print(f"best val loss {run['best_val_loss']:.3f}, checkpoint {out_path}")


if __name__ == "__main__":
    main()
