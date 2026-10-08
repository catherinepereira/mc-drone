"""
The planner for getting somewhere: a point given as coordinates, a block of a named kind it has to find with its
camera, or a player or mob to follow at a distance. Each step it names one goal, which the scripted controller or the
learned goto policy flies, around whatever stands in the way
"""

from __future__ import annotations

from pathlib import Path

from .base import SEARCH_HEIGHT, HonestExpert, center, tool_action

# a found block is reached hovering this far over its top face, inside the job's arrive radius from its center
BLOCK_HOVER = 0.7
# a follower holds its camera this far over the target's middle, where a player's or villager's arms can't reach
FOLLOW_HOVER = 1.5


class GotoPlanner(HonestExpert):
    def __init__(self, mask_ids: dict, depth_max: float = 64.0, reader=None, schematics: Path | None = None) -> None:
        super().__init__(mask_ids, depth_max, reader)
        self.tracking = False

    def decide(self, state: dict) -> dict:
        job = state["job"]
        if job["kind"] == "goto":
            return self.go(state, center(job["point"]), "goto")
        if job["kind"] == "follow":
            target = state.get("followTarget")
            if target is None:
                return tool_action([0, 0, 0, 0, 0])
            return self.go(state, tuple(target), "follow", standoff=(job["near"] + job["far"]) / 2, hover=FOLLOW_HOVER)
        if not self.tracking:
            self.map.track("goal", job["block"])
            self.tracking = True
        found = self.nearest(state, self.known("goal"))
        if found is not None:
            top = center(found)
            return self.go(state, (top[0], top[1] + 0.5 + BLOCK_HOVER, top[2]), "goto")
        action = self.explore(state)
        if self.search_goal is not None and self.spin_left == 0:
            goal = self.search_goal
            self.intent = {"mode": "explore", "aim": (goal[0], self.cruise_y(state, SEARCH_HEIGHT), goal[1]), "standoff": 0.0, "hover": 0.0}
        return action

    def go(self, state: dict, point, mode: str, standoff: float = 0.0, hover: float = 0.0) -> dict:
        """Fly to point, or to standoff blocks from it across the ground with the camera hover over it, facing it"""
        self.intent = {"mode": mode, "aim": tuple(point), "standoff": standoff, "hover": hover}
        if self.skill is not None:
            return self.skill.act(state, self.obs, self.intent)
        move, _ = self.fly(state, point, standoff=standoff, hover=hover)
        return tool_action(move)
