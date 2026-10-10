# Results

Evaluation runs seeds starting at 100000, which collection never uses. Reports for each run are in `reports/`.

## Brain

### Hunt search and docking, 2026-10-09

The hunt planner had been chasing misreads: with every mob kind as prey, the reader's stray mob pixels on blocks became targets the beam never locked on to. In one episode it spent 989 of its first 1000 steps chasing them, with 34 remembered prey in an arena of 7. Two fixes were tried. The first dropped a target the beam refused 5 times or chased 600 steps without a kill. The second, which stays, uses the drone's own voxel memory of which block fills each cell: hits in cells the map has as solid leave a sighting before it becomes a mob, a remembered mob goes once the map shows its cells solid, and a mob whose spot now reads as a block counts as missed. With the mod's true mask kept from the planner as a check, misread prey went from about half of the views of tracked prey to 1 in 10. The planner also looks where lost prey was last seen, and looks over the region from above after each sweep. Scripted planner, vision, flat, 6 episodes:

| Prey | Steps allowed | Before | Beam rule | Voxel memory |
| --- | --- | --- | --- | --- |
| every mob (7) | 6000 | 3 of 6, 38 of 42 killed | 6 of 6, 1228 to 5207 steps | 6 of 6, 5 of them in under 600 steps |
| hostile (4) | 3000 | 6 of 6 | 6 of 6, 483 to 1978 steps | 6 of 6, 308 to 591 steps |

The skill results below were flown with the beam rule. Round 9 on the voxel-memory planner: 2 of 6 on every mob, 74% of prey killed, one episode out of bounds at the arena's edge.

The learned skill on the new planner's goals, flat, vision:

| Skill | replicate_build, size 3 (10) | Every mob, 6000 steps (6) | Hostile (6) |
| --- | --- | --- | --- |
| round 9 | 7 of 10 | 1 of 6, 57% killed, wrecked twice | |
| round 10, hunts only | 1 of 10 | 5 of 6, 86% killed | 6 of 6 |
| round 10, batches balanced by task | 4 of 10 | 2 of 6, 67% killed, out of bounds twice | 6 of 6 |

Round 9 stays live for its builds.

Goto policy round 2 added dock_station arenas (a charging station in the arena, docking checked every step) and a DAgger round. Framework evaluation, 4 obstacles, 10 episodes:

| Task | Round 1 | Round 2 |
| --- | --- | --- |
| goto_point, rough | 10 of 10 | 10 of 10 |
| dock_station, rough | 10 of 10 | 10 of 10 |
| find_block, rough | 9 of 10 | 10 of 10, 13.6 turn reversals per 100 steps against 6.7 |
| find_block, flat | 10 of 10 | 10 of 10 |

Training arenas now move off any spot whose footprint covers a drone's home station or a drone outside the arena. The earlier rough goto_point miss (out of bounds on seed 100000) doesn't come back at the arena's new spot.

### Learned policies flying jobs, 2026-10-09

The brain now flies every job with the learned policies (cell skill round 9, goto round 1, reader v5b), the scripted controller only with `--scripted`. Training arenas on the evaluation seeds:

| Task | Learned | Scripted |
| --- | --- | --- |
| goto_point, rough | 2 of 3 (one out of bounds) | 3 of 3 |
| hunt_mobs, flat, hostile | 3 of 3, every prey killed | |
| replicate_build, flat, size 3 | 2 of 2 | |

The rough goto_point miss is the policy's own: `framework.evaluate --policy goto` flies the same seed out of bounds the same way, overshooting a goal near the geofence's edge, with 18.5 turn reversals per 100 steps.

### Hunts by prey, scripted planner, reader v5b, flat, 2026-10-09

| Prey | Perception | Steps allowed | Success |
| --- | --- | --- | --- |
| hostile (4 mobs) | vision | 3000 | 6 of 6 |
| cow | vision | 3000 | 6 of 6, no hostile mob hit |
| all (7 mobs) | vision | 3000 | 1 of 6 |
| all | vision | 6000 | 3 of 6, 38 of 42 prey killed |
| all | scan | 3000 | 6 of 6, 42 of 42 |

With the learned skill flying after round 9 (hunt arenas picking their own prey): hostile 6 of 6, cow 6 of 6, all with 6000 steps 0 of 6 with 19 of 42 prey killed. Neither the planner nor the skill is told how many mobs there are, they sweep until the episode ends, so an evaluation reports the share of prey killed beside the success rate.

Hunting every mob through the camera fails on finding the last one or two of seven, the animals run once hit. A hunt arena's region first ended a block short of its wall, so the beam refused prey pressed against the wall and the scan left it out, and those hunts could never end. The region now covers everything inside the wall.

### Turning smoothness, 2026-10-08

Turn reversals per 100 steps, counted where the heading's or the pitch's turn of at least 1 degree a step flips direction from one step to the next (`evaluate.smoothness`, 2 or 3 episodes each).

| Controller | Task | Before | Turn easing | Turn easing and the training penalty |
| --- | --- | --- | --- | --- |
| goto policy | find_block, rough | 19.9 yaw, 3.4 pitch | 2.2, 0.2 | 1.3, 1.0 |
| skill | hunt_mobs, flat | 4.4, 2.6 | 1.2, 0.2 | 0.5, 0.1 |
| goto policy | follow_mob, flat | 2.1, 1.5 | 0, 0 | |
| goto planner | find_block, rough | 0.9, 1.3 | 1.0, 1.2 | |

The learned policies flipped their turn far more than the planners they learned from. Three changes went in together: the drone's turn rate eases toward the command each tick (`turnSmoothing` 0.35), each reversal costs `jitterPenalty` 0.1 reward, and goto and skill train with a penalty on the predicted turn flipping sign between consecutive steps. The mob tracker also replaced each mob with a new object every frame it was seen, so the hunt planner, which keeps its target by identity, re-picked the nearest mob every step and swung between two at similar distances. It updates the mob in place now. Success held: goto 100% on rough find_block, every other run ended as before.

### goto policy, round 1, 10 episodes each, 2026-10-08

| Task | Terrain | goto policy | Planner |
| --- | --- | --- | --- |
| goto_point | cave | 100% | |
| find_block | flat | 70%, then 100% | |
| find_block | rough | 40%, then 100% | 30%, then 100% |
| follow_mob | flat | 100% | |

The second figure is after the fix below. The planner reached every rough find_block target reading blocks from the mod's mask, and 3 in 10 through reader v3. Every failure ran the full 1500 steps, every success took under 230. Traces showed why: the world map counted only the reads that matched the target kind, so a block misread as the target once, from afar, stayed a candidate, and the planner hovered over the nearest one. The map now also counts the other kinds read on each block for the goal it tracks, and drops a block once those outnumber the target's reads.

| Reader | Without the fix | With it |
| --- | --- | --- |
| v3 | 30% | 90% |
| v4, with find_block frames | 40% | 100% |

### Hunts with the learned skill, flat, 10 episodes, 2026-10-08

The patrol planner picks the target mob through reader v3, and the skill flies, aims, and holds the beam. Hostile mobs go after the drone once it has hit them.

| Controller | Success | Left the arena | Timed out | Wrecked |
| --- | --- | --- | --- | --- |
| Scripted planner, climbing | 100% of 6 | 0 | 0 | 0 |
| Skill, round 5 | 20% | 4 | 3 | 1 |
| Skill, round 6 | 30% | 2 | 3 | 2 |
| Skill, round 7 | 50% | 0 | 4 | 1 |
| Skill, round 7, backing checked against the geofence | 40% | 1 | 4 | 1 |
| Skill, retrained with the turn penalty, tracker fixed, turn easing | 90% | 0 | 1 | 0 |

The scripted run used the first 6 of the same seeds. In round 5 the planner backed away from a mob closer than its 4-block standoff. A provoked mob follows the drone, so the skill backed out through the wall behind it, which the camera faces away from. From round 6 the planner climbs 2 blocks over a close mob instead, and the round 5 hunt episodes were set aside in `data/archive/skill-hunt-backoff`. In 3 of round 7's 4 timeouts one mob was left. A later exit traced to the planner's unstick move, which reversed blind for 8 steps while the drone hovered a block from a wall. Unsticking and backing off a close goal now reverse only while the spot 1.5 blocks behind is inside the geofence. Mobs move differently on every run, so 10 hunts swing by a success or two.

### Scan against vision perception, scripted flight, flat, 2026-10-05

| Task | Size | Scan | Vision |
| --- | --- | --- | --- |
| copy_build | 8 | 3 of 3, about 900 steps | 2 of 5, 1100 to 1300 steps |
| mine_deposit | 6 | 3 of 3, about 600 steps | 2 of 3, about 2600 steps |
| gather_build | 4 | 3 of 3, 500 to 1750 steps | 3 of 5, 1250 to 1850 steps |
| harvest_crops | 5 | 3 of 3, 160 to 350 steps | 3 of 3, 350 to 580 steps |

Scan reads every block exactly, so copies have no misreads. Mining digs a shaft to each ore it knows about where vision has to trench the whole region to find them. Harvesting skips the survey and the look at each plot.

### Learned skill, DAgger round 4, flat, 2026-10-05

The planner picks the goals, the skill flies, aims, and fires. Round 4 trained on job planner episodes from every task below on flat and rough terrain, then on episodes where the round's first skill flew half the goal steps (about 250000 steps, fire recall 0.90 and precision 0.91 on held-out episodes).

| Task | Size | Episodes | Success | Scripted |
| --- | --- | --- | --- | --- |
| replicate_build | 3 | 5 | 100% | 100% |
| copy_build | 5 | 3 | 100% | 100% |
| harvest_crops | 5 | 3 | 100% | 100% |
| mine_deposit | 6 | 2 | 50% | 67% |
| gather_build | 4 | 3 | 100% | 60% |

### Larger arenas, scripted flight, reader v2, 2026-10-05

| Task | Terrain | Size | Episodes | Success | Notes |
| --- | --- | --- | --- | --- | --- |
| copy_build | flat | 5 | 3 | 100% | 18 to 20 blocks, 700 to 950 steps |
| copy_build | rough | 7 | 5 | 100% | 32 to 51 blocks, 770 to 1240 steps |
| copy_build | flat | 8 | 5 | 40% | 61 to 82 blocks, the failures 1 to 5 blocks short from misreads |
| schematic_build | flat | 8 | 3 | 100% | 65 to 82 blocks, about 900 steps |
| mine_deposit | flat | 6 | 3 | 67% | 9 ore, mostly buried, about 2600 steps, the failure left one |
| mine_deposit | rough | 6 | 2 | 100% | about 2500 steps |
| gather_build | flat | 4 | 5 | 60% | mine the materials from the deposit, then copy, 1250 to 1850 steps, the failures one block short |
| harvest_crops | flat | 5 | 3 | 100% | |

### harvest_crops, flat, 6 episodes, 2026-10-04

| Controller | Success | Notes |
| --- | --- | --- |
| Scripted | 83% | every ripe crop harvested, the failure left one plot unplanted |
| Skill, DAgger round 3 | 0% | every ripe crop harvested, 4 to 16 plots left unplanted, the sweep's goals are new to it |

### replicate_build, flat, 10 episodes, 2026-10-04

| Controller | Success | Notes |
| --- | --- | --- |
| Scripted | 100% | the arena build expert scores 90% |
| Skill, behavior cloning only | 20% | places about half the blocks in 900 steps |
| Skill, DAgger round 2 | 70% | no wrong blocks, each failure one block short when time ran out, about 1.8x the scripted step count |
| Skill, DAgger round 3 | 100% | no wrong blocks, about 1.4x the scripted step count |

Two changes took the skill from 20% to 70%. The scripted controller now keeps the camera on the aim point while it strafes the last 1.5 blocks to a viewpoint. It used to look at the viewpoint until it arrived, which gave the skill contradictory labels. The skill also no longer gets its previous action as an input, which let it copy its own last move.

## Block reader

### Reader v5 and v5b, 2026-10-09

v5 replaced the `<hostile>` and `<passive>` classes with one class per mob kind, labeled from the mask's entity ids: zombie, husk, skeleton, creeper, cow, pig, sheep, chicken, and villager. 10000 steps from v4 on 84,800 frames (30 follow_mob episodes added for villagers), then v5b 10000 more steps from v5 with 90 more hunt episodes from arenas that pick their own prey. v6 added 65 hunts after chickens, sheep, pigs, and cows and hunted no better, so v5b stays.

| Class | v4 | v5 | v5b | v6 |
| --- | --- | --- | --- | --- |
| zombie | | 0.65 | 0.74 | 0.76 |
| husk | | 0.67 | 0.74 | 0.77 |
| skeleton | | 0.64 | 0.65 | 0.68 |
| creeper | | 0.70 | 0.76 | 0.77 |
| cow | | 0.58 | 0.63 | 0.59 |
| pig | | 0.65 | 0.58 | 0.60 |
| sheep | | 0.47 | 0.58 | 0.61 |
| chicken | | 0.31 | 0.30 | 0.35 |
| villager | | 0.91 | 0.92 | 0.88 |
| sandstone | 0.85 | 0.68 | 0.93 | |
| dirt | 0.42 | 0.26 | 0.44 | 0.16 |
| glowstone | 0.58 | 0.89 | 0.71 | |
| mean IoU | 0.890 | 0.836 | 0.853 | 0.852 |

Each version is scored on its own validation episodes. Mobs are small and thin in the frame, which keeps their IoU low, chickens most of all.

### Reader v4, 2026-10-08

8000 steps from v3 with 110 find_block episodes added (about 1800 frames), validated on 60 held-out episodes: mean IoU 0.890. Dirt rose from 0.32 to 0.42 and glowstone fell from 0.73 to 0.58, the mob classes held (hostile 0.70, passive 0.59).

### Reader v3, 2026-10-07

Trained 10000 steps from v2 on 81,600 frames from 447 episodes, adding the hunt and patrol arenas and the two mob classes. Validated on 49 held-out episodes: pixel accuracy 98.0%, mean IoU 0.884.

| Class | v2 IoU | v3 IoU |
| --- | --- | --- |
| `<hostile>` | - | 0.70 |
| `<passive>` | - | 0.57 |
| cobblestone | 0.70 | 0.92 |
| sandstone | 0.65 | 0.90 |
| bricks | 0.66 | 0.83 |
| coal ore | 0.59 | 0.77 |
| glowstone | 0.50 | 0.73 |
| dirt | 0.37 | 0.32 |
| terracotta | 0.95 | 0.91 |

Each version is scored on its own validation set, so the per-class numbers aren't a strict comparison. Mobs are small in the frame and their outlines are thin, which keeps their IoU below the block classes. The mob tracker groups a mob's labeled pixels into one mob, so it doesn't need the whole outline. Whether the drone spares animals when reading through v3 is checked by the hunt evaluation.

### Reader v2, 2026-10-05

Trained 16000 steps on the v1 frames plus about 50 episodes from the copy, build, mine, and gather arenas: pixel accuracy 97.6%, mean IoU 0.856. Its weakest classes are dirt, glowstone, coal ore, and sandstone.

### Reader v1, 2026-10-04

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

## Scripted experts, 8 pillars, 10 episodes per cell, 2026-10-03

| Task | Flat | Rough | Cave |
| --- | --- | --- | --- |
| navigate_to | 100% | 100% | 100% |
| dig_block | 100% | 80% | 90% |
| place_block | 100% | 90% | 90% |
| chest_transfer | 100% | 80% | 90% |
| mine_and_deliver | 100% | 90% | 80% |

## navigate_to policies, flat, 2026-10-02

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

PPO with the default settings halved collisions on the 16-pillar benchmark at the cost of about 9% more steps. The more aggressive run (`ppo2`, now in `checkpoints/archive/`) collided more and dropped to 92%, so `ppo.pt` is the checkpoint to use.
