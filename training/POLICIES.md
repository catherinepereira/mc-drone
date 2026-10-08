# Policies

How a learned drone policy is written, trained, and run, and how each existing one was trained. Every policy goes through the same framework in `src/drone_model/framework/`: one set of commands collects its data, trains it, evaluates it, and films it.

## The framework

A policy is a `PolicySpec` subclass (`framework/spec.py`) registered in `framework/registry.py`. The spec supplies five pieces:

| Piece | What it does |
| --- | --- |
| `model()` | the `torch.nn.Module` to train |
| `teacher(task, info, mask_ids, reader)` | the expert that labels every step. The default is the task's scripted expert, or the job planner when the task hands the drone a job |
| `record(step)` | one training row from a `Step` (state, observation, the teacher's action, the action that flew before, the teacher itself), as a dict of named arrays, or `None` to skip the step |
| `loss(model, batch)` | the training loss under `"loss"`, plus anything worth printing |
| `agent(model, mask_ids)` | an `Agent` that flies the trained model: `reset()` per episode, `act(step)` per step, `executed(action)` with what actually flew |

Optional settings on the spec: `streams` (what the env sends, default rgb, depth, and mask), `sequence` (train on whole episodes, for a recurrent policy), `batch_size`, `stride` (keep every n-th step), and `augment(batch)` (brightness jitter on `rgb` by default).

Data lives in `data/policies/<policy>/<run>/<seed>.npz`, one file per episode with one row per step. Checkpoints default to `checkpoints/<policy>.pt`, reports go next to them as `.json`.

Policies only read what a real drone would have: the camera, depth, the drone's own pose and motion, its inventory, its range sensors, and the job it was given. Never `state["arena"]` or `state["marker"]`. The teacher follows the same rule, see the training README.

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
3. Give it a teacher. Override `teacher()` when no existing expert does the job, and write the expert in `src/drone_model/experts/` on top of `HonestExpert`, which maps the arena from the drone's own frames.
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

The block reader (`perception/reader.py`) is trained separately because it learns from the mod's label streams, not from a teacher's actions. `scripts\collect_reader.ps1` records frames with the `state` and `mask` streams through `collect.demos`, and `train.reader` learns per-pixel block classes from the state stream and mob classes from the mask stream. `--init` starts from an older reader, classes it didn't have start fresh.

## How each policy was trained

### navigate (`checkpoints/navigate.pt`)

The CNN in `policies/navigate.py` flies navigate_to from RGB, depth, velocity, and heading.

1. Behavior cloning on 150 expert demos in an open arena (`bc.pt`), 30 epochs, batch 128, learning rate 1e-3, validation loss 0.021. 95% success with no pillars, 40% with 8.
2. Behavior cloning again on those plus 250 demos with 8 pillars, 40 epochs (`bc2.pt`). 100% with 8 pillars, 98% with 16.
3. PPO from `bc2.pt` on 16 pillars, 30 updates of 1024 steps, critic only for the first 2, an anchor to the BC policy fading to 0 (`ppo.pt`, now `navigate.pt`). 98% with 16 pillars and half the collisions.

These demos came from an earlier expert that read the marker position, before the experts switched to perception only. `framework.collect --recordings navigate_to` turns them into framework rows.

### seq (`checkpoints/seq.pt`)

The recurrent policy in `policies/seq.py` (a CNN over RGB, depth, and the mask, an LSTM, and move, tool, and slot heads) was trained for replicate_build.

1. Behavior cloning on whole expert episodes (`replicate_build-seq.pt`), batch 4 episodes.
2. Four DAgger rounds of 80 episodes each, the expert flying half the steps in round 1, halved each round, 6 fine-tuning epochs per round at learning rate 1e-4 (`replicate_build-dagger4.pt`, now `seq.pt`).

It never succeeded: 0 of 20 evaluation arenas, no block placed. Carrying the reference build in LSTM memory didn't work, which led to the cell skill. It stays as an example of a sequence policy.

### skill (`checkpoints/skill.pt`)

The cell skill in `policies/skill.py` flies the job planners' goals: get to a viewpoint and look at a point, break or place at a point, or hold the beam on a mob. The planner, reading the world through the block reader and voxel memory, picks the goals, and the skill flies, aims, and fires from the camera image, depth, its motion, and the goal.

1. Behavior cloning on planner episodes from replicate_build, harvest_crops, and dig_block, flown with flight noise 0.2, every step with a goal labeled by the scripted controller's clean action. 8000 steps, batch 128, learning rate 5e-4, every 2nd step. 20% on replicate_build.
2. DAgger rounds: each round collects planner episodes, retrains, collects episodes where that skill flies half the goal steps while the planner labels them, and retrains on everything. Round 2 reached 70% on replicate_build and round 3 100%. Two fixes did most of that: the scripted controller keeps the camera on the aim point while strafing into place, and the skill stopped getting its own previous action as an input.
3. Round 4 (`scripts\dagger_skill.ps1`) added copy_build, mine_deposit, gather_build, and harvest_crops on flat and rough terrain, about 250000 steps, fire recall 0.90 and precision 0.91 on held-out episodes.

Round 4's checkpoint is `archive/skill-r4.pt`. The skill since gained the attack mode, so it retrains with the hunt and patrol arenas added.

### Block reader (`checkpoints/reader.pt`)

1. v1: 34800 frames from 234 episodes across tasks and terrains, pixel accuracy 95.1%, mean IoU 0.777.
2. v2: 16000 steps on the v1 frames plus about 50 episodes from the copy, build, mine, and gather arenas, pixel accuracy 97.6%, mean IoU 0.856.

Results for every run are in [RESULTS.md](RESULTS.md).
