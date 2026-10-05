# training

Two Python packages. `mcdrone` is the client for the mod's bridge: a Gymnasium env for every task, a loader for recorded episodes, and `.schem` files. `drone_model` holds the scripted experts, the block reader, the job planners, the learned cell skill, and the drone brain.

The navigate_to baseline is a small CNN that reads the drone's RGB and depth plus its velocity and heading and predicts the next action. It never sees the marker position and has to find the marker in the image.

## Setup

```powershell
python -m venv .venv
.venv\Scripts\pip install torch --index-url https://download.pytorch.org/whl/cu126
.venv\Scripts\pip install -e ".[dev]"
```

Collection, evaluation, and videos need the game running with the mod and a world open, for example `scripts\minecraft.ps1 -Arena` from the repo root.

## Client (`mcdrone`)

The game must be running with the mod loaded and a world open. Clients connect to `127.0.0.1:8318`, or to the host in `MCDRONE_HOST`.

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

Copy and build jobs put the drone's instruction in `info["state"]["job"]`, see [docs/PROTOCOL.md](../docs/PROTOCOL.md).

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

episodes = list_episodes("data", outcome="success")
for sample in iter_transitions(episodes):
    sample["rgb"], sample["depth"], sample["action"]
```

`DroneClient(log_dir=Path("logs"))` writes `py-<session>.jsonl` in the same line shape the mod uses.

## Pipeline

```powershell
.venv\Scripts\python -m drone_model.collect --task navigate_to --episodes 250 --obstacles 8   # expert demos into data/
.venv\Scripts\python -m drone_model.train --out checkpoints/bc2.pt                             # behavior cloning (navigate_to)
.venv\Scripts\python -m drone_model.ppo --init checkpoints/bc2.pt --obstacles 16               # PPO fine-tuning, checkpoints/ppo.pt
.venv\Scripts\python -m drone_model.evaluate --checkpoint checkpoints/ppo.pt --episodes 50 --obstacles 16
.venv\Scripts\python -m drone_model.evaluate --task chest_transfer --policy expert --terrain rough
.venv\Scripts\python -m drone_model.video --task dig_block --terrain rough                    # MP4 into ../../claudevids
```

## Scripted experts

The experts in `tool_experts.py` get the drone's own pose and its camera, nothing else. `perception.WorldMap` back-projects every frame's depth and semantic mask into world points. A hit below the camera raises that column's known floor, a hit above it lowers the column's known ceiling, and ore, chests, the marker, and the pad are remembered once seen. A climb that stalls marks a ceiling over the drone, which is how the experts find a cave roof they haven't looked at. Until the expert has seen what it needs, it turns on the spot and then searches a grid of points inside the geofence, nearest first, from a little higher than it works. It plans around any column taller than its feet or with a ceiling below its head with A*, and travels at cruise height above the local ground, kept under any known ceiling.

Each task builds on that. dig_block and mine_and_deliver mine every ore they have seen, mine_and_deliver drops coal in the chest whenever it has some and no known ore is left. chest_transfer opens the chests it finds and learns which one is stocked from what is inside. place_block aims at the pad's top face from above so hills don't block the view. When the crosshair shows the remembered block isn't there, or something keeps blocking the view, the expert drops it or climbs for a steeper look.

Collection executes the expert's flight plus Gaussian noise but stores the clean expert action as the label in `expert.jsonl` beside each episode (DART, Laskey et al. 2017). Tools and transfers stay noise-free.

Training splits by episode, so validation frames never come from a training episode. Evaluation runs seeds starting at 100000, which collection never uses.

PPO starts the actor from a BC checkpoint, trains only the critic for the first two updates, and adds a loss pulling the actor's mean toward the BC policy that fades to zero by the last update. BatchNorm statistics stay at their BC values.

## Block reader

`reader.BlockReader` is a small U-Net that reads the camera image and depth and predicts, per pixel, one of about 40 block classes (the build palette, the arena bases and floors, terrain, ore, chests, farmland, water, crops) and whether a crop is ripe. It learns from the mod's `mask` and `state` streams and needs neither to run. `memory.VoxelMemory` back-projects its predictions through depth into a voxel map: each cell keeps class and ripeness votes, break and place events update it at once, and every update reports which cells changed, which the dashboard's Drone memory panel shows step by step. For a copy job, the memory's read of the source box is the schematic to build.

```powershell
powershell -File scripts\collect_reader.ps1             # frames with the state stream, about 260 episodes across tasks and terrains
.venv\Scripts\python -m drone_model.train_reader                      # checkpoints/reader.pt
.venv\Scripts\python -m drone_model.eval_reader --episodes 10          # reads reference builds through the reader and memory
```

### Block reader, 2026-10-04

Trained on 34,800 frames from 234 episodes, validated on 26 held-out episodes.

| Metric | Result |
| --- | --- |
| Pixel accuracy | 95.1% |
| Mean IoU | 0.777 |
| Ripe accuracy on crop pixels | 98.8% |
| Build cells read right (flat, 10 builds) | 99.6% |
| Build blocks read right | 98.8% |
| Builds read exactly | 90% |

The weakest classes are dirt, terracotta, spruce planks, and sandstone, which are rare in the data and close in color to their neighbors.

## Jobs

`brain.py` runs the player's jobs. Each job gets a planner from `jobs.py` that sees through the block reader into a voxel memory and picks one goal at a time: a view (fly here, look there), a block to break, or a cell to place a block into.

- Copy: surveys the source box from views around its sides and over its top, takes the memory's read as the plan (and saves it as a `.schem` in `reports/`), surveys the destination, breaks what doesn't belong there top down, then places the plan bottom up, each block against a face of a block already in place.
- Build: the same from the named schematic in the game's `schematics/` folder.
- Mine: surveys the region, breaks every block of the kind it read, and looks again for blocks the digging uncovered.
- Harvest: the harvest expert, with its reads also kept in a voxel memory.

The memory goes to the dashboard as it changes. Planners name the block to place and the mod picks the slot.

The cell skill (`skill.py`) is a learned controller for those goals. It takes the camera image, depth, the drone's velocity and heading, and the goal relative to the drone, and outputs the flight command and whether to fire the tool. It learns by behavior cloning from the experts working their goals with flight noise (`collect_skill.py`, `train_skill.py`). With `--skill` the planner still picks the goals and the skill flies them, without it the scripted controller does.

```powershell
.venv\Scripts\python -m drone_model.brain                                      # run every job started in game or on the dashboard
.venv\Scripts\python -m drone_model.brain --task replicate_build --episodes 10  # evaluate on training arenas
powershell -File scripts\collect_skill.ps1                                     # skill data from harvest, build, and dig episodes
.venv\Scripts\python -m drone_model.train_skill                                # checkpoints/skill.pt
.venv\Scripts\python -m drone_model.brain --task replicate_build --skill checkpoints/skill.pt
powershell -File scripts\dagger_skill.ps1 -Round 1                             # skill flies, scripted controller labels, retrain
```

The skill also gets aim features worked out from its inputs: the yaw and pitch from the crosshair to the aim point, the distance, and how far the crosshair's depth lands short of or past it. Without them it couldn't tell when to fire.

### Brain on replicate_build, flat, 10 episodes, 2026-10-04

| Controller | Success | Notes |
| --- | --- | --- |
| Scripted | 100% | the arena build expert scores 90% |
| Skill, behavior cloning only | 20% | places about half the blocks in 900 steps |
| Skill, DAgger round 2 | 70% | no wrong blocks, each failure one block short when time ran out, about 1.8x the scripted step count |
| Skill, DAgger round 3 | 100% | no wrong blocks, about 1.4x the scripted step count |

Two changes took the skill from 20% to 70%. The scripted controller now keeps the camera on the aim point while it strafes the last 1.5 blocks to a viewpoint. It used to look at the viewpoint until it arrived, which gave the skill contradictory labels. The skill also no longer gets its previous action as an input, which let it copy its own last move.

### Brain on harvest_crops, flat, 6 episodes, 2026-10-04

| Controller | Success | Notes |
| --- | --- | --- |
| Scripted | 83% | every ripe crop harvested, the failure left one plot unplanted |
| Skill, DAgger round 3 | 0% | every ripe crop harvested, 4 to 16 plots left unplanted, the sweep's goals are new to it |

Harvesting is one cycle: break a ripe crop, then replant its plot before going for the next one. With nothing ripe left, the planner sweeps the field plot by plot, looks down at each one, and plants wherever the block reader sees bare farmland.

## Results

### Scripted experts, 8 pillars, 10 episodes per cell, 2026-10-03

| Task | Flat | Rough | Cave |
| --- | --- | --- | --- |
| navigate_to | 100% | 100% | 100% |
| dig_block | 100% | 80% | 90% |
| place_block | 100% | 90% | 90% |
| chest_transfer | 100% | 80% | 90% |
| mine_and_deliver | 100% | 90% | 80% |

### navigate_to policies, flat, 2026-10-02

Evaluated with the deterministic policy mean. These demos came from an earlier expert that read the marker position and pillar layout, before the experts switched to perception only.

| Policy | Pillars | Episodes | Success | Mean steps on success | Mean collisions |
| --- | --- | --- | --- | --- | --- |
| `bc` (150 open-arena demos) | 0 | 20 | 95% | 39.5 | - |
| `bc` | 8 | 20 | 40% | - | - |
| `bc2` (plus 250 demos with 8 pillars) | 0 | 20 | 100% | - | - |
| `bc2` | 8 | 20 | 100% | - | - |
| `bc2` | 16 | 50 | 98% | 63.4 | 13.0 |
| `ppo` (bc2 plus 30 PPO updates on 16 pillars) | 16 | 50 | 98% | 69.0 | 6.6 |
| `ppo2` (actor lr 1e-4, anchor 0.2, 40 updates) | 16 | 50 | 92% | 65.3 | 39.0 |
| Random | 0 | 10 | 0% | - | - |

PPO with the default settings halved collisions on the 16-pillar benchmark at the cost of about 9% more steps. The more aggressive run (`ppo2`) collided more and dropped to 92%, so `ppo.pt` is the checkpoint to use.

## Layout

```
src/mcdrone/                     bridge client, Gymnasium env, episode loader, schematic files
src/drone_model/perception.py    world map from depth and mask, what experts may know
src/drone_model/tool_experts.py  scripted experts for every task
src/drone_model/reader.py        block reader U-Net
src/drone_model/memory.py        voxel memory of the reader's output
src/drone_model/jobs.py          planners for copy, build, mine, and harvest jobs
src/drone_model/skill.py         learned cell skill
src/drone_model/brain.py         runs jobs and publishes the memory
src/drone_model/expert.py        A* planner, and the old privileged navigate_to expert
src/drone_model/collect.py       demo collection through DroneEnv
src/drone_model/data.py          loads demos into uint8 tensors
src/drone_model/model.py         CNN policy
src/drone_model/train.py         behavior cloning
src/drone_model/ppo.py           PPO fine-tuning
src/drone_model/evaluate.py      success rate on fixed seeds
src/drone_model/video.py         MP4s with camera, depth, mask, map, and stats
```

## Tests

```powershell
.venv\Scripts\python -m pytest                                              # fake bridge, no game needed
$env:MCDRONE_E2E = "1"; .venv\Scripts\python -m pytest tests	est_e2e.py   # against a running game
```

## Known limitations

- The cell skill is trained and evaluated on flat terrain only, and its training data comes from copy, harvest, and dig episodes.
- Harvest jobs often leave a plot or two unplanted, see the results above.
- Copy jobs read only what the survey views show. A cell hidden inside a solid build reads as air.
- Copy and mine jobs work with the blocks the reader knows (about 40 classes). A build job places any block in its schematic.
- Mine jobs break the blocks they can see and dig no tunnels to reach buried ones.
- The experts know their own pose exactly, the way a drone with good onboard positioning would.
- The experts still fail one or two rough or cave episodes in ten.
- Human demos recorded with the B key have no `expert.jsonl` and are skipped unless `load_demos` is called with `require_expert_labels=False`.
- PPO runs on a single game instance at about 45 steps per second, so long runs take a while.
