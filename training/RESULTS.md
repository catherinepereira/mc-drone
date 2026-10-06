# Results

Evaluation runs seeds starting at 100000, which collection never uses. Reports for each run are in `reports/`.

## Brain

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
