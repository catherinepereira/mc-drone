from drone_model.energy import FULL, BatteryKeeper, EnergyModel, job_volume

JOB = {"kind": "mine", "region": [0, 0, 0, 9, 3, 9], "blocks": ["minecraft:coal_ore"]}
STATION = [20, 0, 0]
BOUNDS = [-10, -5, -10, 30, 20, 30]


class FakePlanner:
    """Records where the keeper asks it to fly"""

    def __init__(self) -> None:
        self.flown_to = None
        self.home_pilot = None

    def fly(self, state, point, standoff):
        self.flown_to = point
        return [1.0, 0, 0, 0, 0], False


def battery(charge: float, docked: bool = False, enabled: bool = True) -> dict:
    flight = 1 / (15 * 60 * 20)
    return {
        "enabled": enabled, "charge": charge, "home": STATION, "docked": docked, "reserve": 0.1,
        "flightPerTick": flight, "breakCost": 40 * flight, "chargePerTick": 1 / 3600,
    }


def state(charge: float, pos=(5.0, 4.0, 5.0), docked: bool = False, enabled: bool = True) -> dict:
    return {"pos": list(pos), "bounds": BOUNDS, "camera": {"eyeHeight": 0.2}, "battery": battery(charge, docked, enabled)}


def keeper(model: EnergyModel | None = None) -> tuple[BatteryKeeper, FakePlanner]:
    planner = FakePlanner()
    return BatteryKeeper(planner, model or EnergyModel(path=None), JOB), planner


def test_job_volume_sums_every_box():
    assert job_volume(JOB) == 400
    assert job_volume({"kind": "copy", "source": [0, 0, 0, 1, 1, 1], "dest": [5, 0, 0, 6, 1, 1]}) == 16


def test_a_full_battery_leaves_the_drone_to_the_planner():
    k, _ = keeper()
    assert k(state(1.0)) is None
    assert k.mode == "work"


def test_a_job_too_big_for_the_charge_charges_first():
    model = EnergyModel(path=None)
    model.rates["mine"] = 0.5 / 400
    k, planner = keeper(model)
    assert k(state(0.4)) is not None
    assert k.mode == "home"
    assert planner.flown_to[0] == STATION[0] + 0.5


def test_a_job_that_fits_starts_right_away():
    model = EnergyModel(path=None)
    model.rates["mine"] = 0.1 / 400
    k, _ = keeper(model)
    assert k(state(0.6)) is None


def test_mid_job_it_turns_home_only_once_the_reserve_is_at_stake():
    model = EnergyModel(path=None)
    model.rates["mine"] = 0.01 / 400
    k, _ = keeper(model)
    assert k(state(0.5)) is None
    assert k(state(0.3)) is None
    assert k(state(0.1005)) is not None
    assert k.trips == 1


def test_docked_it_waits_for_a_full_charge_then_goes_back_to_work():
    k, _ = keeper()
    k.mode = "home"
    k.started = True
    hold = k(state(0.3, pos=(20.5, 1.2, 0.5), docked=True))
    assert k.charging and not hold["move"].any()
    assert k(state(FULL, pos=(20.5, 1.2, 0.5), docked=True)) is None
    assert k.mode == "work"


def test_a_disabled_battery_or_a_station_out_of_bounds_is_ignored():
    k, _ = keeper()
    assert k(state(0.05, enabled=False)) is None
    k, _ = keeper()
    far = state(0.05)
    far["bounds"] = [-10, -5, -10, 10, 20, 10]
    assert k(far) is None


def test_finish_learns_the_cost_of_the_work_and_the_trip():
    model = EnergyModel(path=None)
    model.rates["mine"] = 0.01 / 400
    model.learn_rate = 1.0
    k, _ = keeper(model)
    k(state(0.9))
    k(state(0.8))
    k.mode = "home"
    k(state(0.75, pos=(5.0, 4.0, 5.0)))
    k(state(0.7, pos=(15.0, 4.0, 5.0)))
    k.finish(success=True)
    assert abs(model.rates["mine"] - 0.1 / 400) < 1e-9
    assert abs(model.travel - 0.1 / 10) < 1e-9


def test_rates_move_toward_new_measurements():
    model = EnergyModel(path=None)
    model.learn_job("mine", 100, 0.2)
    model.learn_job("mine", 100, 0.4)
    assert 0.002 < model.rates["mine"] < 0.004
