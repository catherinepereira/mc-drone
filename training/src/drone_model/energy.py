"""
The brain's battery budget. EnergyModel learns what each kind of job costs per block of its boxes, and what a block of
flight costs. BatteryKeeper flies the drone home to charge whenever the charge would not cover the work left,
the flight home, and the reserve
"""

from __future__ import annotations

import json
import math
import threading
from pathlib import Path

from .experts.base import tool_action
from .paths import DATA

SAVE = DATA / "energy.json"
# blocks a tick the drone covers cruising, the first guess at what a flight costs before any trip home is measured
CRUISE_SPEED = 0.25
# the flight home is budgeted at this many times its estimate
SAFETY = 1.5
# each finished job moves the learned rate this far toward what it measured
LEARN_RATE = 0.3
# charged this far counts as full
FULL = 0.98
# the feet hover this far over the station's top, inside the dock height
DOCK_HOVER = 0.2
# trips shorter than this say little about the cost per block
MIN_TRIP = 4.0
BOX_KEYS = ("source", "dest", "region", "gather")


def job_volume(job: dict) -> int:
    """Cells in the job's boxes, what its cost scales with"""
    total = 0
    for key in BOX_KEYS:
        b = job.get(key)
        if b and len(b) == 6:
            total += (abs(b[3] - b[0]) + 1) * (abs(b[4] - b[1]) + 1) * (abs(b[5] - b[2]) + 1)
    return total


def station_top(station) -> tuple[float, float, float]:
    return (station[0] + 0.5, station[1] + 1.0, station[2] + 0.5)


def fly_home(planner, state: dict, station) -> dict:
    """The planner's flight to a little above the station's top, where the drone docks"""
    x, y, z = station_top(station)
    move, _ = planner.fly(state, (x, y + DOCK_HOVER + state["camera"]["eyeHeight"], z), standoff=0.0)
    return tool_action(move)


class EnergyModel:
    """
    Charge per block of job box by job kind, and charge per block flown, as moving averages over finished jobs.
    Every drone the brain flies shares one, from its own thread
    """

    def __init__(self, path: Path | None = SAVE) -> None:
        self.path = path
        self.learn_rate = LEARN_RATE
        self._lock = threading.Lock()
        self.rates: dict[str, float] = {}
        self.travel: float | None = None
        if path is not None and path.exists():
            saved = json.loads(path.read_text())
            self.rates = saved.get("rates", {})
            self.travel = saved.get("travel")

    def job_cost(self, kind: str, volume: int, battery: dict) -> float:
        # an unseen kind is budgeted as one break per cell, generous for every job but a dense mine
        rate = self.rates.get(kind, battery["breakCost"])
        return rate * volume

    def travel_cost(self, dist: float, battery: dict) -> float:
        rate = self.travel if self.travel is not None else battery["flightPerTick"] / CRUISE_SPEED
        return rate * dist

    def learn_job(self, kind: str, volume: int, spent: float) -> None:
        if volume > 0 and spent > 0:
            rate = spent / volume
            with self._lock:
                old = self.rates.get(kind)
                self.rates[kind] = rate if old is None else old + self.learn_rate * (rate - old)

    def learn_travel(self, dist: float, spent: float) -> None:
        if dist >= MIN_TRIP and spent > 0:
            rate = spent / dist
            with self._lock:
                self.travel = rate if self.travel is None else self.travel + self.learn_rate * (rate - self.travel)

    def save(self) -> None:
        if self.path is not None:
            with self._lock:
                text = json.dumps({"rates": self.rates, "travel": self.travel}, indent=2)
                self.path.parent.mkdir(parents=True, exist_ok=True)
                self.path.write_text(text)


class BatteryKeeper:
    """
    A planner's errand (see HonestExpert.errand). At a job's start it charges first when the learned cost of the job
    won't fit in the charge, and mid-job it heads home once the charge barely covers the flight there and the reserve.
    Docked, it waits for a full charge, then hands the drone back to the planner
    """

    def __init__(self, planner, model: EnergyModel, job: dict) -> None:
        self.planner = planner
        self.model = model
        self.kind = job["kind"]
        self.volume = job_volume(job)
        # "work" leaves the drone to the planner, "home" flies it to the station, "charge" holds it docked
        self.mode = "work"
        self.started = False
        self.trips = 0
        self.work_spent = 0.0
        self.trip_spent = 0.0
        self.trip_dist = 0.0
        self.last_charge: float | None = None
        self.last_pos = None

    @property
    def charging(self) -> bool:
        return self.mode == "charge"

    def __call__(self, state: dict) -> dict | None:
        battery = state.get("battery") or {}
        self.account(state, battery)
        station = battery.get("home")
        if not battery.get("enabled") or station is None or self.kind == "return_home" or not inside(state.get("bounds"), station_top(station)):
            return None
        charge = battery["charge"]
        if self.mode == "work":
            first = not self.started
            self.started = True
            home_cost = SAFETY * self.model.travel_cost(math.dist(state["pos"], station_top(station)), battery)
            spare = charge - battery["reserve"] - home_cost
            left = max(0.0, self.model.job_cost(self.kind, self.volume, battery) - self.work_spent)
            if charge >= FULL or (spare >= 0 and not (first and spare < left)):
                return None
            self.mode = "home"
            self.trips += 1
        if self.mode == "home" and battery.get("docked"):
            self.mode = "charge"
        if self.mode == "home":
            return fly_home(self.planner, state, station)
        if charge >= FULL:
            self.mode = "work"
            return None
        return tool_action([0, 0, 0, 0, 0])

    def account(self, state: dict, battery: dict) -> None:
        """Splits the charge spent since the last step between the work and the flights home"""
        charge = battery.get("charge")
        pos = state.get("pos")
        if charge is None or pos is None:
            return
        trip = self.mode == "home" or self.kind == "return_home"
        if self.last_charge is not None and charge < self.last_charge:
            if trip:
                self.trip_spent += self.last_charge - charge
            elif self.mode == "work":
                self.work_spent += self.last_charge - charge
        if trip and self.last_pos is not None:
            self.trip_dist += math.dist(pos, self.last_pos)
        self.last_charge = charge
        self.last_pos = pos

    def finish(self, success: bool) -> None:
        """Teaches the model what this job and its flights home cost"""
        if success and self.kind != "return_home":
            self.model.learn_job(self.kind, self.volume, self.work_spent)
        self.model.learn_travel(self.trip_dist, self.trip_spent)
        self.model.save()


def inside(bounds, point) -> bool:
    return bool(bounds) and all(bounds[i] <= point[i] <= bounds[i + 3] for i in range(3))
