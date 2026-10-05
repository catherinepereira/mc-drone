"""
Scripted navigate_to expert that reads the marker and the pillar layout, used only to generate demos.
Plans around pillars with A* over arena cells, then steers at the farthest waypoint in clear line of sight
"""

from __future__ import annotations

import heapq
import math

import numpy as np

YAW_GAIN = 1.0 / 15.0
FACING_TOLERANCE = 30.0
# drone half-width is 0.3, the rest is margin for the eased velocity
CLEARANCE = 0.55
LOS_STEP = 0.25


class Pillars:
    def __init__(self, arena: dict | None, extra: list[tuple[int, int, int]] | None = None) -> None:
        """extra adds (x, z, width) footprints, such as pedestals and chests the expert should fly around"""
        self.boxes = []
        footprints = [(x, z, w) for x, z, w, _h in (arena or {}).get("obstacles", [])] + (extra or [])
        for x, z, w in footprints:
            self.boxes.append((x - CLEARANCE, z - CLEARANCE, x + w + CLEARANCE, z + w + CLEARANCE))

    def free(self, x: float, z: float) -> bool:
        return not any(x0 < x < x1 and z0 < z < z1 for x0, z0, x1, z1 in self.boxes)

    def clear_line(self, ax: float, az: float, bx: float, bz: float) -> bool:
        steps = max(1, int(math.hypot(bx - ax, bz - az) / LOS_STEP))
        return all(self.free(ax + (bx - ax) * t / steps, az + (bz - az) * t / steps) for t in range(steps + 1))


def plan(start: tuple[float, float], goal: tuple[float, float], pillars: Pillars, bounds: tuple[int, int, int, int]) -> list[tuple[float, float]]:
    """A* over cell centers inside bounds (x0, z0, x1, z1), 8-connected with no corner cutting"""
    x0, z0, x1, z1 = bounds
    start_cell = (math.floor(start[0]), math.floor(start[1]))
    goal_cell = (math.floor(goal[0]), math.floor(goal[1]))

    def center(c):
        return (c[0] + 0.5, c[1] + 0.5)

    def ok(c):
        return c == goal_cell or (x0 <= c[0] <= x1 and z0 <= c[1] <= z1 and pillars.free(*center(c)))

    frontier = [(0.0, start_cell)]
    came = {start_cell: None}
    cost = {start_cell: 0.0}
    while frontier:
        _, cell = heapq.heappop(frontier)
        if cell == goal_cell:
            break
        for dx in (-1, 0, 1):
            for dz in (-1, 0, 1):
                if dx == dz == 0:
                    continue
                nxt = (cell[0] + dx, cell[1] + dz)
                # the first move may start inside a clearance box (the drone drifted close), it only has to end clear
                if not ok(nxt) or (cell != start_cell and not pillars.clear_line(*center(cell), *center(nxt))):
                    continue
                new_cost = cost[cell] + math.hypot(dx, dz)
                if new_cost < cost.get(nxt, math.inf):
                    cost[nxt] = new_cost
                    came[nxt] = cell
                    heapq.heappush(frontier, (new_cost + math.dist(nxt, goal_cell), nxt))
    if goal_cell not in came:
        return [goal]
    path = []
    cell = goal_cell
    while cell is not None:
        path.append(center(cell))
        cell = came[cell]
    path.reverse()
    path[-1] = goal
    return path


def waypoint(state: dict, goal: tuple[float, float] | None = None, pillars: Pillars | None = None) -> tuple[float, float]:
    """Next point to steer at on the way to goal (default the marker), planning around pillars when blocked"""
    pos = state["pos"]
    if goal is None:
        marker = state["marker"]
        goal = (marker[0] + 0.5, marker[2] + 0.5)
    pillars = pillars or Pillars(state.get("arena"))
    if not pillars.boxes or pillars.clear_line(pos[0], pos[2], *goal):
        return goal
    # search a window around the drone and the goal, no arena bounds needed
    margin = 8
    bounds = (
        math.floor(min(pos[0], goal[0])) - margin,
        math.floor(min(pos[2], goal[1])) - margin,
        math.floor(max(pos[0], goal[0])) + margin,
        math.floor(max(pos[2], goal[1])) + margin,
    )
    path = plan((pos[0], pos[2]), goal, pillars, bounds)
    target = path[min(1, len(path) - 1)]
    for p in path[1:]:
        if pillars.clear_line(pos[0], pos[2], *p):
            target = p
    return target


def expert_action(state: dict) -> np.ndarray:
    """Returns the env action vector: forward, right, up, yaw, pitch in [-1, 1]"""
    pos = state["pos"]
    marker = state["marker"]
    tx, tz = waypoint(state)
    dx = tx - pos[0]
    dz = tz - pos[2]
    # the drone's box center sits 0.2 above its position
    dy = marker[1] + 0.5 - (pos[1] + 0.2)
    target_yaw = math.degrees(math.atan2(-dx, dz))
    yaw_error = (target_yaw - state["yaw"] + 180.0) % 360.0 - 180.0
    horizontal = math.hypot(dx, dz)
    goal_dist = math.hypot(marker[0] + 0.5 - pos[0], marker[2] + 0.5 - pos[2])
    forward = min(1.0, goal_dist / 2.0) if abs(yaw_error) < FACING_TOLERANCE else 0.0
    # keep the camera level-ish but tilt toward the marker when it is far above or below
    target_pitch = -math.degrees(math.atan2(dy, max(horizontal, 1e-3))) * 0.5
    pitch_error = target_pitch - state.get("pitch", 0.0)
    return np.array(
        [
            forward,
            0.0,
            float(np.clip(dy, -1.0, 1.0)),
            float(np.clip(yaw_error * YAW_GAIN, -1.0, 1.0)),
            float(np.clip(pitch_error * YAW_GAIN, -1.0, 1.0)),
        ],
        dtype=np.float32,
    )
