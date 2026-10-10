# Policies

How a learned drone policy is written, trained, and run, and how each existing one was trained. Every policy goes through the same framework in `src/drone_model/framework/`: one set of commands collects its data, trains it, evaluates it, and films it.

## The framework

A policy is a `PolicySpec` subclass (`framework/spec.py`) registered in `framework/registry.py`. The spec supplies five pieces:

| Piece | What it does |
| --- | --- |
| `model()` | the `torch.nn.Module` to train |
| `teacher(task, info, mask_ids, reader)` | the planner that labels every step, built on a fresh perception core for the episode. The default is the job planner when the task hands the drone a job, else the task's arena expert |
| `record(step)` | one training row from a `Step` (state, observation, the teacher's action, the action that flew before, the teacher itself), as a dict of named arrays, or `None` to skip the step |
| `loss(model, batch)` | the training loss under `"loss"`, plus anything worth printing |
| `agent(model, mask_ids)` | an `Agent` that flies the trained model: `reset()` per episode, `act(step)` per step, `executed(action)` with what actually flew |

Optional settings on the spec: `streams` (what the env sends, default rgb, depth, and mask), `sequence` (train on whole episodes, for a recurrent policy), `batch_size`, `stride` (keep every n-th step), `augment(batch)` (brightness jitter on `rgb` by default), and `smoothness` with `look(model, batch)`. A `smoothness` above 0 adds `smoothness * relu(-turn_t * turn_t+1)` to the loss, summed over yaw and pitch for each step and the one after it in the same episode, so flipping the turn back and forth raises the loss and turning steadily doesn't. `look` returns the model's predicted yaw and pitch. goto and skill train with 0.2.

Data lives in `data/policies/<policy>/<run>/<seed>.npz`, one file per episode with one row per step. A run folder's name starts with its task, and training draws each task's rows in proportion to the square root of its row count, so 6000-step hunts don't crowd 600-step builds out of the batches. Checkpoints default to `checkpoints/<policy>.pt`, reports go next to them as `.json`.

Policies only read what a real drone would have: the camera, depth, the drone's own pose and motion, its inventory, its range sensors, and the job it was given. Never `state["arena"]` or `state["marker"]`. The teacher follows the same rule: it knows only what its perception core built from the drone's own frames, see How the drone works in the training README.

goto and the cell skill are pilots: each flies the goal its teacher names each step (the teacher's `intent`) and doesn't pick goals. In the brain the same planners name the goals and these policies fly them, so what they learn here is what flies the player's jobs. navigate and seq fly whole tasks on their own.

## Writing a policy

1. Add a module in `src/drone_model/policies/` with the model and a `PolicySpec` subclass. A minimal one:

```python
class HoverSpec(PolicySpec):
    name = "hover"

    def model(self) -> nn.Module:
        return HoverNet()

    def record(self, step: Step) -> dict[str, np.ndarray]:
        return {
            "depth": quantize_depth(step.obs["depth"]),
            "state": state_vector(step.state, include_marker=False)[:6],
            "move": np.asarray(step.label["move"], dtype=np.float32),
        }

    def loss(self, model, batch):
        return {"loss": F.mse_loss(model(batch["depth"], batch["state"]).float(), batch["move"])}

    def augment(self, batch):
        return batch  # no rgb to jitter

    def agent(self, model, mask_ids) -> Agent:
        return HoverAgent(model)
```

2. Add it to `POLICIES` in `framework/registry.py`.
3. Give it a teacher. Override `teacher()` when no existing expert does the job, and write the expert in `src/drone_model/scripted/` on top of `Planner`, which reads the drone's perception core (`perception/core.py`): the voxel memory, world map, and mob tracker built from its own frames.
4. Add a training arena when no task exercises the behavior: a `TaskKind`, a case in `TrainingArenas.placeTask`, metrics in `ArenaRecord`, and scoring in `TaskScorer`, see `docs/PROTOCOL.md`.
5. `tests/test_framework.py` runs every registered policy through record, loss, and act on a made-up step, run it with `pytest`.

## Training a policy

```powershell
# 1. data: the teacher flies with flight noise and labels each step with its clean action (DART)
.venv\Scripts\python -m drone_model.framework.collect --policy hover --task navigate_to --episodes 100
# 2. behavior cloning, the best checkpoint by validation loss lands in checkpoints/hover.pt
.venv\Scripts\python -m drone_model.framework.train --policy hover
# 3. DAgger: the policy flies half the steps, the teacher still labels every one, then train again on all of it
.venv\Scripts\python -m drone_model.framework.collect --policy hover --task navigate_to --episodes 50 --dagger checkpoints\hover.pt
.venv\Scripts\python -m drone_model.framework.train --policy hover --init checkpoints\hover.pt
# 4. success rate on evaluation seeds, and the teacher alone for comparison
.venv\Scripts\python -m drone_model.framework.evaluate --policy hover --task navigate_to
.venv\Scripts\python -m drone_model.framework.evaluate --policy hover --task navigate_to --teacher
# 5. an MP4 into ../../claudevids
.venv\Scripts\python -m drone_model.evaluate.video --policy hover --task navigate_to
```

Notes:

- Collection runs against the live game (`scripts\minecraft.ps1 -Arena` or the client gametest with `-Pe2eHold`). One collection or training run at a time.
- `--teacher-sees mask` lets the teacher use the mod's ground-truth mask, for data the block reader can't label yet, such as frames that teach the reader a new class.
- `--perception scan` hands the drone each job box read from the world, or a hunt's mobs, in place of reading them with the camera.
- Seeds: collection from 300000 by default, evaluation from 100000. Keep runs on separate seed ranges so no evaluation arena was trained on.
- Episodes split into training and validation by file, so validation never sees a frame from a training episode.
- `--recordings <task>` turns episodes the mod recorded with expert labels (`collect.demos`) into rows, for policies trained on recordings.
- `framework.ppo` fine-tunes the navigate policy with PPO in the live game. It's specific to policies with a continuous action head and one image encoder.

## Perception models

The block reader (`perception/reader.py`), the first stage of the perception core, is trained separately because it learns from the mod's label streams, not from a teacher's actions. `scripts\collect_reader.ps1` records frames with the `state` and `mask` streams through `collect.demos`, and `train.reader` learns per-pixel block classes from the state stream and mob classes from the mask stream. `--init` starts from an older reader, classes it didn't have start fresh.

## How each policy was trained

### navigate (`checkpoints/navigate.pt`)

The CNN in `policies/navigate.py` flies navigate_to from RGB, depth, velocity, and heading.

1. Behavior cloning on 150 expert demos in an open arena (`archive/bc.pt`), 30 epochs, batch 128, learning rate 1e-3, validation loss 0.021. 95% success with no pillars, 40% with 8.
2. Behavior cloning again on those plus 250 demos with 8 pillars, 40 epochs (`archive/bc2.pt`). 100% with 8 pillars, 98% with 16.
3. PPO from `archive/bc2.pt` on 16 pillars, 30 updates of 1024 steps, critic only for the first 2, an anchor to the BC policy fading to 0 (`navigate.pt`, a copy in `archive/ppo.pt`). 98% with 16 pillars and half the collisions.

These demos came from an earlier expert that read the marker position, before the experts switched to perception only. `framework.collect --recordings navigate_to` turns them into framework rows.

### seq (`checkpoints/seq.pt`)

The recurrent policy in `policies/seq.py` (a CNN over RGB, depth, and the mask, an LSTM, and move, tool, and slot heads) was trained for replicate_build.

1. Behavior cloning on whole expert episodes (`archive/replicate_build-seq.pt`), batch 4 episodes.
2. Four DAgger rounds of 80 episodes each, the expert flying half the steps in round 1, halved each round, 6 fine-tuning epochs per round at learning rate 1e-4 (`seq.pt`, a copy in `archive/replicate_build-dagger4.pt`).

It never succeeded: 0 of 20 evaluation arenas, no block placed. Carrying the reference build in LSTM memory didn't work, which led to the cell skill. It stays as an example of a sequence policy.

### skill (`checkpoints/skill.pt`)

The cell skill in `policies/skill.py` flies the job planners' goals: get to a viewpoint and look at a point, break or place at a point, or hold the beam on a mob. The planner, reading the world through the block reader and voxel memory, picks the goals, and the skill flies, aims, and fires from the camera image, depth, its motion, and the goal.

1. Behavior cloning on planner episodes from replicate_build, harvest_crops, and dig_block, flown with flight noise 0.2, every step with a goal labeled by the scripted controller's clean action. 8000 steps, batch 128, learning rate 5e-4, every 2nd step. 20% on replicate_build.
2. DAgger rounds: each round collects planner episodes, retrains, collects episodes where that skill flies half the goal steps while the planner labels them, and retrains on everything. Round 2 reached 70% on replicate_build and round 3 100%. Two fixes did most of that: the scripted controller keeps the camera on the aim point while strafing into place, and the skill stopped getting its own previous action as an input.
3. Round 4 (`scripts\dagger_skill.ps1`) added copy_build, mine_deposit, gather_build, and harvest_crops on flat and rough terrain, about 250000 steps, fire recall 0.90 and precision 0.91 on held-out episodes.

4. Round 5 added the attack mode, with planner episodes from the hunt and patrol arenas read through reader v3, on top of the earlier rounds' data with the goal widened to 11 values. 20% on flat hunts.
5. Round 6 (`dagger_skill.ps1 -Round 6 -Tasks hunt_mobs`) recollected the hunts after the planner switched from backing away from close mobs to climbing over them. 30% on flat hunts.
6. Round 7, another hunt round on the same terms, 50% on flat hunts.
7. Retrained on all of it plus round 8's planner hunts, with the turn reversal penalty (`smoothness` 0.2), after the mob tracker fix and with turn easing in the mod: 90% on flat hunts.
8. Round 9 (`dagger_skill.ps1 -Round 9 -Tasks hunt_mobs`) on hunt arenas that pick their own prey: hostile, every mob, or one kind. 6 of 6 on flat hunts after hostile mobs and after cows, 0 of 6 after every mob.
9. Round 10 (`dagger_skill.ps1 -Round 10 -Tasks hunt_mobs`, hunts of 6000 steps) after the hunt planner stopped chasing misreads. 5 of 6 on every mob, but builds fell from 7 of 10 to 1 of 10: the hunt rows crowded the build rows out of the batches. Retrained with batches balanced by task, 4 of 10 builds and 2 of 6 hunts on every mob. Round 9 stays live, both round 10 checkpoints are in the archive (`skill-r10.pt`, `skill-r10b.pt`). Builds haven't had DAgger data since round 4, a round over every task comes next.

Round 4's checkpoint is `archive/skill-r4.pt`, from before the attack mode.

### goto (`checkpoints/goto.pt`)

The goto policy in `policies/goto.py` flies the goto planner's goals: a point given as coordinates, a spot over a block of a named kind once the planner has found it, a place 2 to 5 blocks from a followed mob or player, or the next search point. It reads the camera image, depth, the range sensors, its motion, and the goal relative to its heading.

1. Round 1 (`scripts\dagger_goto.ps1`): planner episodes on goto_point (flat, rough, cave), find_block (flat, rough, cave), and follow_mob (flat, rough), flown with flight noise 0.2 and the planner reading blocks through reader v3, then a retrain, episodes where that policy flies half the steps, and a final retrain.

On its own it matches the planner: 100% on cave goto_point, flat and rough find_block, and flat follow_mob, once the world map let other reads outvote a misread goal block (see RESULTS.md).

### Block reader (`checkpoints/reader.pt`)

1. v1: 34800 frames from 234 episodes across tasks and terrains, pixel accuracy 95.1%, mean IoU 0.777.
2. v2: 16000 steps on the v1 frames plus about 50 episodes from the copy, build, mine, and gather arenas, pixel accuracy 97.6%, mean IoU 0.856.
3. v3: 10000 steps from v2 (`--init`) on 81600 frames from 447 episodes, adding hunt and patrol arenas with the `<hostile>` and `<passive>` mob classes labeled from the mask stream, pixel accuracy 98.0%, mean IoU 0.884.
4. v4: 8000 steps from v3 with frames from 110 find_block episodes (`scripts\collect_reader.ps1 -Set find`), mean IoU 0.890.
5. v5 and v5b: one class per mob kind in place of hostile and passive, 10000 steps from v4 and 10000 more with 90 hunts from arenas that pick their own prey, mean IoU 0.853.

Results for every run are in [RESULTS.md](RESULTS.md).
