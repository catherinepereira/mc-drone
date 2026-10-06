"""Folders the training code reads and writes, all gitignored"""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CHECKPOINTS = ROOT / "checkpoints"
DATA = ROOT / "data"
REPORTS = ROOT / "reports"
# the schematics folder of the dev client the mod's gametest launches
SCHEMATICS = ROOT.parent / "mod" / "build" / "run" / "clientGameTest" / "schematics"
VIDEOS = ROOT.parents[1] / "claudevids"
