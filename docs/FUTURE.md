# Future work

Known gaps and things tried so far, to pick up later. Per-job limitations of the brain are in [training/README.md](../training/README.md#known-limitations).

## Drones avoiding each other

Postponed. Learning to steer around other drones is the goal, scripted traffic rules aren't.

- Drones are solid to each other, and every episode counts `droneCollisions`.
- Each charging station takes one drone.
- In mask perception the experts' world map blocks a column for 15 frames after seeing another drone in it near flying height. The brain sees through the block reader, which has no drone class, so it doesn't see other drones.
- A drone class for the reader was tried and backed out. Picking it up again needs reader frames with drones in view and a retrain.
- Episodes recorded with scripted drones wandering through the arena are still in `training/data`: 25 rough and 13 flat replicate_build, 20 cave dig_block. The scripted traffic itself was removed.

## Fleets

- A fleet (several drones, one job each, one shared arena that resets once every drone is done) works for training arenas only. A fleet reset that names a player job is refused.
- Player drones already run different jobs at once from their queues, and a job can't overlap another drone's running job. This hasn't been tested with three or more drones on different jobs in one world.
- Fleet evaluation tops out at 8 drones (`FleetResetPayload.MAX_MEMBERS`).

## Capture

- Every drone shares the client's one camera, so each drone's step rate drops with the number flying: one drone runs at about 147 steps a second, two at about 79 each. A second camera or render target per drone wasn't tried.
- Depth and mask raycasts run on the client thread, about 3.7 ms a frame at 160x120.

## Combat and patrols

- The reader tells apart zombies, husks, skeletons, creepers, cows, pigs, sheep, chickens, and villagers. Spiders, endermen, slimes, and the rest need frames and classes of their own, until then a guard can only hunt them in scan perception.
- A misread on a block the voxel memory hasn't voted on yet still becomes a target until the map catches up, about 1 in 10 views of tracked prey in an all-mob hunt. Chickens read worst (IoU 0.30).
- The learned skill gets wrecked by creepers on all-mob hunts, it flies closer than the planner's standoff.
- A skill round on hunts alone costs builds (7 of 10 down to 1 of 10). Builds last had DAgger data in round 4, the next skill round has to cover every task.
- Hunts and follows keep the world running in lockstep so their targets move, which makes those episodes differ from run to run on the same seed.
- A drone has 20 health and no armor or upgrades. Tiers change the beam's damage, not the drone's toughness.
- Creepers only come after a drone that hit them, and a provoked one explodes like it would at a player, blocks included.
- Patrol cells are a fixed 8 blocks across, a region can't set its own spacing or route.

## Getting somewhere

- find_block and seek_block only find blocks the reader has a class for, about 40 kinds.
- Reader v4 reads glowstone worse than v3 (IoU 0.58 against 0.73). Cave arenas with glowstone haven't been rechecked with it.
- A follow job keeps the drone within 48 blocks across of where it started, a player walking farther ends it as out of bounds.
- follow_mob trains on a villager. Players move faster and more sharply.
- The goto policy turns back and forth more than the skill, 9 to 14 reversals per 100 steps on rough goto_point, find_block, and dock_station.
- Docking is checked in the dock_station arena, the flight home in a player's world hasn't been flown end to end since the arenas started moving off stations.
- The scripted goto planner reaches cave points 4 times in 6. It climbs into pockets under low dips in the uneven roof and keeps trying a gap too low for it. Flat and rough points it reaches every time.

## Map

- BlueMap re-renders the changed regions every 5 seconds after saving the chunks, so arena changes show up on the map within about 5 seconds.
- Drone markers move every second from `mcdrone/drones.json` in BlueMap's web root. BlueMap's own marker push still comes every 10 seconds and carries the names and details.
