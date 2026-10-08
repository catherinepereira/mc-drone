"""
Every policy's training data: one .npz per episode under data/policies/<policy>/<run>/, each array one row per step,
named by what the policy's record returns
"""

from __future__ import annotations

from pathlib import Path

import numpy as np
import torch

from ..paths import DATA

ROOT = DATA / "policies"


def run_dir(policy: str, run: str) -> Path:
    path = ROOT / policy / run
    path.mkdir(parents=True, exist_ok=True)
    return path


def save_episode(path: Path, rows: list[dict[str, np.ndarray]]) -> None:
    """An episode's rows as one file, skipped when no step made a row"""
    if rows:
        np.savez(path, **{k: np.stack([np.asarray(r[k]) for r in rows]) for k in rows[0]})


def episode_files(policy: str, runs: list[str] | None = None) -> list[Path]:
    """The policy's episodes, from every run or the named ones"""
    folders = [ROOT / policy / r for r in runs] if runs else sorted((ROOT / policy).glob("*"))
    files = sorted(f for d in folders for f in d.glob("*.npz"))
    if not files:
        raise SystemExit(f"no {policy} data in {ROOT / policy}, collect some with python -m drone_model.framework.collect --policy {policy}")
    return files


def load_steps(files: list[Path], stride: int) -> dict[str, torch.Tensor]:
    """Every stride-th step of the episodes as one table, read twice so the arrays are allocated once at full size"""
    lengths, shapes = [], {}
    for f in files:
        with np.load(f) as z:
            first = next(iter(z.files))
            lengths.append(len(range(0, len(z[first]), stride)))
            shapes = shapes or {k: (z[k].shape[1:], z[k].dtype) for k in z.files}
    total = sum(lengths)
    out = {k: np.empty((total, *shape), dtype=dtype) for k, (shape, dtype) in shapes.items()}
    at = 0
    for f, n in zip(files, lengths):
        with np.load(f) as z:
            for k in out:
                out[k][at : at + n] = z[k][::stride]
        at += n
    return {k: torch.from_numpy(v) for k, v in out.items()}


def load_episodes(files: list[Path]) -> list[dict[str, np.ndarray]]:
    """Whole episodes, for policies that train on sequences"""
    out = []
    for f in files:
        with np.load(f) as z:
            out.append({k: z[k] for k in z.files})
    return out


def pad_episodes(episodes: list[dict[str, np.ndarray]], device: torch.device) -> dict[str, torch.Tensor]:
    """A batch of episodes padded to the longest, with "valid" False on the padding"""
    t = max(len(next(iter(e.values()))) for e in episodes)
    out = {}
    for key in episodes[0]:
        first = episodes[0][key]
        padded = np.zeros((len(episodes), t, *first.shape[1:]), dtype=first.dtype)
        for i, e in enumerate(episodes):
            padded[i, : len(e[key])] = e[key]
        out[key] = torch.from_numpy(padded).to(device, non_blocking=True)
    valid = np.zeros((len(episodes), t), dtype=bool)
    for i, e in enumerate(episodes):
        valid[i, : len(next(iter(e.values())))] = True
    out["valid"] = torch.from_numpy(valid).to(device)
    return out
