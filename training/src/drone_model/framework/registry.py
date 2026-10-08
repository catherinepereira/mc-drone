"""Every policy the framework collects for, trains, and runs, by name. A new policy adds its spec here"""

from __future__ import annotations

from ..policies.goto import GotoSpec
from ..policies.navigate import NavigateSpec
from ..policies.seq import SeqSpec
from ..policies.skill import SkillSpec
from .spec import PolicySpec

POLICIES: dict[str, PolicySpec] = {spec.name: spec for spec in (NavigateSpec(), SeqSpec(), SkillSpec(), GotoSpec())}


def get(name: str) -> PolicySpec:
    if name not in POLICIES:
        raise SystemExit(f"no policy named {name}, pick from {', '.join(POLICIES)}")
    return POLICIES[name]
