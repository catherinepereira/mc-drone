# mcdrone

Python client for the mc-drone Minecraft mod: a bridge client, a Gymnasium env for every task, and a loader for recorded episodes.

## Install

```powershell
python -m venv .venv
.venv\Scripts\pip install -e ".[dev]"
```

## Use

The game must be running with the mod loaded and a world open.

```python
from mcdrone import DroneClient, action

with DroneClient() as drone:
    drone.set_mode("lockstep")
    obs = drone.reset(seed=7)
    obs = drone.step(action(forward=1.0, yaw=5.0))
    print(obs.state["pos"], obs.rgb.shape, obs.episode["reward"])
```

```python
from mcdrone import DroneEnv

env = DroneEnv(streams=("rgb", "depth"), record=True)
obs, info = env.reset(seed=0)
obs, reward, terminated, truncated, info = env.step(env.action_space.sample())
env.close()
```

For navigate_to the env action is `[forward, right, up, yaw, pitch]` in [-1, 1], with yaw and pitch scaled to 15 degrees per tick. The tool tasks (`DroneEnv(task="dig_block")` and the others) use a dict action: `move` (that vector), `tool` (index into `mcdrone.protocol.TOOLS`), `slot`, and `transfer` (`[kind, slot]`, kind 1 stores a drone stack in the open container, 2 takes one out). An optional `block`, such as `"minecraft:bricks"`, names the block to place, and the drone uses whichever slot holds it.

Observations hold the image streams, a 6-value state vector (velocity, sin and cos of yaw, pitch), and `bounds`, the drone's position inside the geofence from -1 to 1 per axis. Tool tasks add `inventory` and `container` as `[item index, count]` per slot (indices into `DroneClient.item_ids`) and `tool_state` (mining progress, container open, selected slot). Pass `hide_marker=False` to append the marker offset to the state vector, for debugging only.

Leaving the geofence terminates the episode as a failure, `info["episode"]["success"]` tells the two apart.

Copy and build jobs put the drone's instruction in `info["state"]["job"]`, see the mod's PROTOCOL.md.

Schematics (`.schem`, the Sponge format WorldEdit uses):

```python
from mcdrone import schematic

s = schematic.load("house.schem")
s.size, s.get(0, 0, 0)  # (width, height, length), "minecraft:oak_planks"
s.save("copy.schem")
```

Recorded episodes:

```python
from mcdrone import list_episodes, iter_transitions

episodes = list_episodes("../mc-drone-model/data", outcome="success")
for sample in iter_transitions(episodes):
    sample["rgb"], sample["depth"], sample["action"]
```

## Logs

`DroneClient(log_dir=Path("logs"))` writes `py-<session>.jsonl` in the same line shape the mod uses.

## Tests

```powershell
.venv\Scripts\python -m pytest                                     # fake bridge, no game needed
$env:MCDRONE_E2E = "1"; .venv\Scripts\python -m pytest tests\test_e2e.py   # against a running game
```

The end-to-end tests expect a world open with the mod, for example from `gradlew runClientGameTest -Pe2eHold=600` in mc-drone-mod.
