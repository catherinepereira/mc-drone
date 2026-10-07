# training

Two Python packages. `mcdrone` is the client for the mod's bridge: a Gymnasium env for every task, a loader for recorded episodes, and `.schem` files. `drone_model` has the scripted experts, block reader, job planners, learned cell skill, and drone brain. Results are in [RESULTS.md](RESULTS.md).

## Setup

```powershell
python -m venv .venv
.venv\Scripts\pip install torch --index-url https://download.pytorch.org/whl/cu126
.venv\Scripts\pip install -e ".[dev]"
```

Collection, evaluation, and videos need the game running with the mod and a world open, for example `scripts\minecraft.ps1 -Arena` from the repo root.

## Layout

```
src/mcdrone/                  bridge client, Gymnasium env, episode loader, schematic files
src/drone_model/brain.py      runs jobs and publishes the memory
src/drone_model/paths.py      checkpoint, data, report, schematic, and video folders
src/drone_model/perception/   world map from depth and mask, block reader U-Net, voxel memory
src/drone_model/experts/      A* planner, scripted experts for every task, job planners
src/drone_model/policies/     CNN policy, sequence policy, learned cell skill
src/drone_model/train/        behavior cloning, PPO, block reader, sequence policy and DAgger, cell skill
src/drone_model/collect/      expert demos and skill data through DroneEnv
src/drone_model/evaluate/     policy success rates, reader accuracy, MP4s
scripts/                      multi-run collections and skill DAgger rounds
tests/
```

Generated files stay in this folder and are gitignored:

```
checkpoints/          current weights, older versions in checkpoints/archive/
data/                 recorded episodes by task, cell skill data in data/skill/
reports/              JSON reports in brain/, eval/, reader/, ppo/, and copy reads as .schem in reads/
logs/                 output of long runs
```

## Client (`mcdrone`)

Clients connect to `127.0.0.1:8318`, or to the host in `MCDRONE_HOST`.

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

Jobs put the drone's instruction in `info["state"]["job"]`, see [docs/PROTOCOL.md](../docs/PROTOCOL.md).

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

## Scripted experts

The experts in `experts/tools.py` get the drone's own pose and its camera, nothing else. `perception/worldmap.py` back-projects every frame's depth and semantic mask into world points. A hit below the camera raises that column's known floor, a hit above it lowers the column's known ceiling, and ore, chests, the marker, and the pad are remembered once seen. A climb that stalls marks a ceiling over the drone, which is how the experts find a cave roof they haven't looked at. Until the expert has seen what it needs, it turns on the spot and then searches a grid of points inside the geofence, nearest first, from a little higher than it works. It plans around any column taller than its feet or with a ceiling below its head with A*, and travels at cruise height above the local ground, kept under any known ceiling.

Each task builds on that. dig_block and mine_and_deliver mine every ore they have seen, mine_and_deliver drops coal in the chest whenever it has some and no known ore is left. chest_transfer opens the chests it finds and learns which one is stocked from what is inside. place_block aims at the pad's top face from above so hills don't block the view. When the crosshair shows the remembered block isn't there, or something keeps blocking the view, the expert drops it or climbs for a steeper look.

Collection executes the expert's flight plus Gaussian noise but stores the clean expert action as the label in `expert.jsonl` beside each episode (DART, Laskey et al. 2017). Tools and transfers stay noise-free. Training splits by episode, so validation frames never come from a training episode.

## navigate_to baseline

A small CNN reads the drone's RGB and depth plus its velocity and heading and predicts the next action. It never sees the marker position and has to find the marker in the image. PPO starts the actor from a BC checkpoint, trains only the critic for the first two updates, and adds a loss pulling the actor's mean toward the BC policy that fades to zero by the last update. BatchNorm statistics stay at their BC values.

```powershell
.venv\Scripts\python -m drone_model.collect.demos --task navigate_to --episodes 250 --obstacles 8   # expert demos into data/
.venv\Scripts\python -m drone_model.train.bc --out checkpoints/bc2.pt                               # behavior cloning
.venv\Scripts\python -m drone_model.train.ppo --init checkpoints/bc2.pt --obstacles 16               # PPO fine-tuning, checkpoints/ppo.pt
.venv\Scripts\python -m drone_model.evaluate.policy --checkpoint checkpoints/ppo.pt --episodes 50 --obstacles 16
.venv\Scripts\python -m drone_model.evaluate.policy --task chest_transfer --policy expert --terrain rough
.venv\Scripts\python -m drone_model.evaluate.video --task dig_block --terrain rough                  # MP4 into ../../claudevids
.venv\Scripts\python -m drone_model.evaluate.video --task gather_build --size 4 --scan               # a job, filmed from behind the drone
```

Videos show the drone from behind with vanilla's third-person camera (the `chase` stream) side by side with what its own camera sees, with depth inset. `--view drone` shows the drone's camera full size with depth and the semantic mask instead.

## Block reader

`perception/reader.py` is a small U-Net that reads the camera image and depth and predicts, per pixel, one of about 40 block classes (the build palette, the arena bases and floors, terrain, ore, chests, farmland, water, crops) and whether a crop is ripe. It learns from the mod's `mask` and `state` streams and needs neither to run. `perception/memory.py` back-projects its predictions through depth into a voxel map: each cell keeps class and ripeness votes, break and place events update it at once, and every update reports which cells changed, which the dashboard's Drone memory panel shows step by step. For a copy job, the memory's read of the source box is the schematic to build.

```powershell
powershell -File scripts\collect_reader.ps1                                 # frames with the state stream across tasks and terrains
.venv\Scripts\python -m drone_model.train.reader                           # checkpoints/reader.pt
.venv\Scripts\python -m drone_model.evaluate.reader --episodes 10          # reads reference builds through the reader and memory
```

## Jobs

`brain.py` runs the player's jobs. Each job gets a planner from `experts/jobs.py` that sees through the block reader into a voxel memory and picks one goal at a time: a view (fly here, look there), a block to break, or a cell to place a block into.

- Copy: surveys the source box from views around its sides and over its top, then looks up close at cells under read blocks that no view reached. The memory's read is the plan (saved as a `.schem` in `reports/reads/`), a block the drone doesn't carry loses to a carried one with a fair share of the votes. It surveys the destination, breaks what doesn't belong there top down, then places the plan bottom up, each block against a face of a block already in place, from a viewpoint with room for the camera. Running out of a block sends it back to look at the source cells it read as that block.
- Build: the same from the named schematic in the game's `schematics/` folder.
- Mine: surveys the region and breaks every block of the kind it can see. With none in sight it digs trenches two wide every four blocks, top layer first, which leaves every block of the region with a face in a trench, and breaks what comes into view. Then it looks up close at wall blocks it hasn't seen well, and digs any block with a fair share of votes for the kind.
- Copy or build with gathering (a job with a `gather` box, set as Materials on the in-game job screen or the dashboard, or the gather_build arena): before placing, it mines the gather box for the blocks it needs and doesn't carry, the ones in sight first, then trenches. A player's copy over a gather_build arena's three boxes succeeds with scan (1184 steps) and vision (1859 steps).
- Harvest: harvesting and replanting a plot is one cycle, then a sweep looks down at each plot and plants the bare ones.

The memory goes to the dashboard as it changes. Planners name the block to place and the mod picks the slot.

With scan perception (`--perception scan`, or `perception` set to `scan` in the mod's config) the server hands the planner each job box read from the world. The planner loads them into memory as certain reads and skips the surveys. A copy builds straight from the scanned source with exact block names, so any block works. Mining or gathering digs a two-wide shaft down to each buried target it knows about instead of trenching the whole box. Harvesting takes ripeness from each crop's `age` and plants the plots the scan found bare without looking at them first.

The cell skill (`policies/skill.py`) is a learned controller for those goals. It takes the camera image, depth, the drone's velocity and heading, and the goal relative to the drone, and outputs the flight command and whether to fire the tool. It also gets aim features worked out from its inputs: the yaw and pitch from the crosshair to the aim point, the distance, and how far the crosshair's depth lands short of or past it. It learns by behavior cloning from the planners working their goals with flight noise, then DAgger rounds where the skill flies and the scripted controller labels. With `--skill` the planner still picks the goals and the skill flies them, without it the scripted controller does.

```powershell
.venv\Scripts\python -m drone_model.brain                                      # run every job started in game or on the dashboard
.venv\Scripts\python -m drone_model.brain --task copy_build --size 8 --perception scan --episodes 3   # evaluate on training arenas
powershell -File scripts\dagger_skill.ps1 -Round 1                             # planner data, train, skill flies and planner labels, train
.venv\Scripts\python -m drone_model.train.skill                                # checkpoints/skill.pt from data/skill
.venv\Scripts\python -m drone_model.brain --task replicate_build --skill checkpoints/skill.pt
```

## Tests

```powershell
.venv\Scripts\python -m pytest                                              # fake bridge, no game needed
$env:MCDRONE_E2E = "1"; .venv\Scripts\python -m pytest tests\test_e2e.py   # against a running game
```

## Known limitations

- The cell skill is evaluated on flat terrain only, and on copies no larger than 5 wide.
- Vision harvest jobs sometimes leave a plot unplanted.
- A harvest arena's count of ripe crops at the start varies between runs of the same seed (22, 20, and 15 for seed 100000), so harvest results aren't comparable run to run.
- Copy jobs in vision read only what the survey views show. A cell hidden inside a solid build reads as air.
- Copy and mine jobs in vision work with the blocks the reader knows (about 40 classes). Build jobs and scanned jobs place any block.
- Mine jobs and gathering dig open trenches or shafts from the top, the drone doesn't fly through enclosed tunnels.
- Vision copies of 60 or more blocks still miss a few from the reader's misreads.
- The experts know their own pose exactly, the way a drone with good onboard positioning would.
- The experts still fail one or two rough or cave episodes in ten.
- Human demos recorded with the B key have no `expert.jsonl` and are skipped unless `load_demos` is called with `require_expert_labels=False`.
- PPO runs on a single game instance at about 45 steps per second, so long runs take a while.
