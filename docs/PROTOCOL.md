# Bridge protocol

Schema version: 2

The mod listens on `127.0.0.1:8318`. The Python client (`training/src/mcdrone/protocol.py`) and the dashboard (`dashboard/src/protocol.ts`) both mirror this file. Bump the schema version on any breaking change, the mod rejects a `hello` with a different version.

## WebSocket `/ws`

Text frames are JSON objects with a `type` field. Binary frames are observations.

### Client to mod

| type | fields | who |
| --- | --- | --- |
| `hello` | `role` (`controller` or `observer`), `schema`, `client`, optional `drone` (a drone's entity id, from `status.drones`) | first message from everyone |
| `subscribe` | `obs`, `logs`, `metrics` (booleans) | anyone |
| `configure` | any of `mode`, `width`, `height`, `streams`, `streamHz`, and other config fields | controller |
| `act` | `action` | controller, realtime mode |
| `step` | `action`, `ticks`, `id` | controller, lockstep mode, answered by an `obs` with `replyTo` |
| `reset` | `seed`, `options`, `id` | controller, answered by an `obs` with `replyTo` |
| `record` | `on`, optional `test` | controller. With `test` the episodes go to the game folder's `mcdrone/test-recordings`, out of the training data. Recording a controller turned on stops when it releases or disconnects |
| `pilot` | `on` | controller, moves the game camera into or out of the drone |
| `release` | | controller, gives up control and becomes an observer |
| `memory` | `step`, `changes`, optional `snapshot` and `focus`, see Drone memory | controller, relayed to every other client with `drone` added |
| `select` | any of `cornerA`, `cornerB`, `dest` as `[x, y, z]` or null | anyone, edits the copy selection |
| `export` | `name` | anyone, saves the selected source box as `<name>.schem` in the game's `schematics` folder |
| `region_save` | `name`, `purpose` (`general`, `safe`, `mine`, or `farm`) | anyone, saves the selection's source box as a named region, replacing one with the same name |
| `region_delete` | `region` (a region id) | anyone |
| `region_use` | `region` | anyone, loads the region's box into the selection's source corners |
| `rename` | `name` (at most 32 characters, blank for the default `Drone`) | anyone, names the player's drone, shown over it as `name (owner)` |
| `queue` | `job` (the options a job's `reset` takes), optional `drone`, `id` | anyone, adds the job to that drone's queue, the active drone's without one, answered by `queued` |
| `run_queues` | `id` | anyone, starts the first queued job of every idle drone, answered by `queues_started` with `drones`, how many started |
| `ping` | `id` | anyone, answered by `pong` |

Each drone has at most one controller. A controller `hello` with `drone` controls that drone, without it the player's active drone. A second controller for a drone already controlled gets an `error` and stays an observer. Controller messages act on the session's own drone, so several clients can fly several drones at once. `mode` is the whole world's: lockstep freezes the server for every drone, and the world goes back to realtime once the last controller leaves. Lockstep steps of different drones take turns on the game camera, one frame each.

### Mod to client

| type | fields |
| --- | --- |
| `welcome` | `schema`, `role`, `drone` (a controller's drone), `session`, `config`, `status`, `maskIds`, `itemIds` |
| `status` | `mode`, `controller`, `observers`, `recording`, `recordArmed`, `piloting`, `droneId`, `inWorld`, `task`, `episode`, `selection`, `regions`, `drones` |
| `log` | `entry` (same shape as a log line) |
| `metrics` | `sps`, `fps`, `captureMs`, `raycastMs`, `encodeMs`, `queueDepth`, `droppedFrames`, `observers` |
| `pong` | `replyTo`, `tick` |
| `error` | `message`, `replyTo` (when the error answers a request) |

`itemIds` lists item names by raw id.

### Action

```json
{
  "move": [forward, right, up],
  "look": [yaw, pitch],
  "tool": "none",
  "slot": 0,
  "block": "minecraft:bricks",
  "transfer": null
}
```

- `move` components are in [-1, 1] and scale the drone's max speed. They are relative to the drone's yaw.
- `look` is degrees per tick, clamped to [-15, 15]. Positive yaw turns right, positive pitch looks down (Minecraft convention). The drone's turn rate eases toward it each tick by `turnSmoothing` (config, default 0.35), as its velocity eases toward `move` by `smoothing`, so the camera can't snap from turning one way to the other.
- `tool` is `none`, `break`, `place`, `open`, `close`, or `attack`. `break` mines the block under the crosshair within 4.5 blocks and progresses each tick it stays on, at stone-pickaxe speed, with drops going into the inventory. `place` puts a block from `slot` against the face under the crosshair. `open` opens the container under the crosshair, `close` closes it. The container closes on its own once it is more than 6 blocks from the camera. `attack` is a guardian's beam. Held on a mob under the crosshair within 10 blocks, it locks on, and stays locked while the mob is alive, within 10 blocks, and in sight, wherever the crosshair goes. It charges for 15 drone ticks, purple to yellow, then deals the damage of the tier's sword (copper 5, iron 6, diamond 7, netherite 8) and starts charging again. Each discharge answers with an `attack` event naming the `entity` and whether it was `killed`. On a hunt or guard it only locks on to the job's prey (`state.job.prey`), flown by hand on any mob, never on players or drones. A beam on anything else, or on nothing, gets an `attack_failed` event with the `reason`. The hit is a mob attack from the drone, so the mob turns on it the way mobs go after a player's wolf that bit them.
- `slot` (0 to 26) selects the inventory slot `place` uses.
- `block` (optional) names the block `place` puts down instead of `slot`. With the config's `materials` set to `inventory` the drone swaps the first stack of it into slot 0 and places from there, and fails with `out of <block>` when it has none. With `unlimited` it places the block without using items.
- `transfer` moves a stack between the drone and the open container: `{"from": "drone" | "container", "slot": i}` plus optional `toSlot` (default: first slot that fits, the way a shift-click merges) and `count` (default: the whole stack).
- An action holds for every tick of a `step` and, in realtime mode, until the next `act`. `place`, `open`, `close`, and `transfer` fire once, on the first tick, `break` and `attack` hold.

A lockstep `step` that used a tool answers after the server has applied it, and its frame shows the changed blocks. Hunts and follows keep the world running between steps, so an episode can end after a step's frame went out. The next `step` then gets the end frame instead of acting, and a `step` after that gets an error until a `reset`.

### Observation (binary frame)

```
u32 little-endian header length
header JSON (utf-8)
payload bytes
```

Header:

```json
{
  "type": "obs",
  "seq": 42,
  "tick": 1234,
  "replyTo": 7,
  "state": {
    "pos": [x, y, z],
    "vel": [x, y, z],
    "tier": "copper",
    "health": 20.0,
    "maxHealth": 20.0,
    "range": { "front": 2.4, "down": null, "max": 4.0 },
    "battery": { "charge": 0.82, "home": [x, y, z], "docked": false, "enabled": true, "flightPerTick": 0.0000556, "hoverShare": 0.5, "breakCost": 0.00222, "chargePerTick": 0.000278, "reserve": 0.1 },
    "yaw": 90.0,
    "pitch": 10.0,
    "camera": { "fov": 70.0, "windowAspect": 1.78, "eyeHeight": 0.34 },
    "lookingAt": { "block": "minecraft:stone", "pos": [x, y, z], "face": "up", "dist": 3.2 },
    "collided": false,
    "bounds": [x0, y0, z0, x1, y1, z1],
    "inventory": [["minecraft:coal", 3], ["minecraft:air", 0]],
    "selectedSlot": 0,
    "container": { "pos": [x, y, z], "block": "minecraft:chest", "slots": [["minecraft:cobblestone", 32]] },
    "breaking": { "pos": [x, y, z], "progress": 0.4 },
    "events": [{ "type": "break", "block": "minecraft:coal_ore", "pos": [x, y, z] }],
    "marker": [x, y, z],
    "job": null,
    "arena": { "task": "dig_block", "terrain": "rough", "origin": [x, y, z], "radius": 12, "obstacles": [[x, z, width, height]], "trees": [[x, z, height]], "stalactites": [[x, z, bottomY]] }
  },
  "episode": {
    "id": "dig_block-20261003-153000-0001",
    "task": "dig_block",
    "step": 12,
    "reward": 0.21,
    "totalReward": 3.4,
    "distance": 4.1,
    "done": false,
    "success": false,
    "truncated": false,
    "outOfBounds": false,
    "damage": 0.0,
    "wrecked": false,
    "collisions": 0,
    "droneCollisions": 0,
    "turnReversals": 0,
    "metrics": [1]
  },
  "action": { "move": [1, 0, 0], "look": [0, 0], "tool": "break", "slot": 0, "transfer": null },
  "streams": {
    "rgb": { "offset": 0, "length": 57600, "shape": [120, 160, 3], "dtype": "uint8" },
    "depth": { "offset": 57600, "length": 76800, "shape": [120, 160], "dtype": "float32" },
    "mask": { "offset": 134400, "length": 38400, "shape": [120, 160], "dtype": "uint16" },
    "state": { "offset": 172800, "length": 38400, "shape": [120, 160], "dtype": "uint16" }
  }
}
```

- `lookingAt`, `container`, `breaking`, `bounds`, `marker`, `arena`, and `episode` are null when not applicable.
- `job` is the drone's instruction for a copy or build job, see Jobs. Policies may read it.
- `marker` and `arena` are privileged: the true goal and layout, for debugging and the dashboard's map. Policies and the scripted experts must not read them. Everything else is fair game, including `bounds`, the task's geofence.
- `collisions` counts the ticks something stopped the drone, `droneCollisions` the ones where it was another drone. Drones are solid to each other.
- `camera` lets a script back-project depth into world points with the same frustum the mod rendered.
- `events` holds tool events since the previous observation: `break`, `break_failed`, `place`, `place_failed`, `open`, `open_failed`, `close`, `transfer`, `transfer_failed`. A tool used with a flat battery fails with reason `battery flat`.
- `tier` is the active drone's (`copper`, `iron`, `diamond`, or `netherite`), its pickaxe sets how fast blocks break. `battery` is its charge from 0 to 1, its home charging station or null, whether it's docked there, and the battery settings from `config/mcdrone-battery.json`: charge per tick of flight, the share hovering draws, the charge a broken block costs, charge per tick on the station, and the reserve to keep. With `enabled` false the charge never drops.
- `collided` is true when a block or entity stopped the drone on the last simulated tick.
- `range` is the drone's range sensors: the gap in blocks from its body to the nearest block straight ahead along its heading and straight down, null when nothing is within `max` (the config's `sensorRange`, default 4). They see blocks, not entities.
- `health` is the drone's, out of `maxHealth` (20). Only mobs hurt a drone, their hits, arrows, and explosions, and a mob only goes after a drone that attacked it. A drone on its home station repairs, empty to full in about 20 seconds. At 0 a drone in the player's world is destroyed and drops its cargo and itself. A training arena's drone is wrecked instead and its episode ends, see Reward.
- `episode.metrics` is the task's progress counters, see Tasks. Patrols add `episode.patrol`, `{ "visited", "cells", "round", "rounds" }`: the patrol cells flown over this round and the rounds flown.
- `rgb` rows go top to bottom.
- `depth` is z-distance from the camera plane in blocks, `depthMax` (config) where nothing was hit.
- `mask` is 0 for sky, `1 + block raw id` for blocks, and `entityBase + entity type raw id` for entities. `welcome.maskIds` lists names: `blocks[i]` is id `i + 1`, `entities[j]` is id `entityBase + j`, and `categories[j]` is that entity's mob category (`monster`, `creature`, `ambient`, the water categories, or `misc` for everything that isn't a mob, villagers and golems included).
- `state` (off unless `streams` includes it) is 0 for sky and entities and `1 + block state id` for blocks, so it carries properties such as a crop's age. `GET /api/states` lists the names by id, such as `minecraft:wheat[age=7]`. It exists to label training data for the block reader, policies don't read it.
- `chase` (off unless `streams` includes it) is an RGB view from vanilla's third-person camera behind the drone, `[chaseHeight, chaseWidth, 3]` (config, default 640x360), for videos. The mod renders it as an extra frame after the drone's own, so it costs a frame per observation. Policies don't read it.
- All multi-byte values are little-endian.

## HTTP

| method | path | returns |
| --- | --- | --- |
| GET | `/api/status` | same shape as the `status` message |
| GET | `/api/config` | current config |
| PUT | `/api/config` | merges the JSON body into the config and saves it |
| GET | `/api/logs?limit=N` | the last N log lines from memory |
| GET | `/api/states` | block state names by id, for the `state` stream |
| GET | `/api/episodes` | list of episode `meta.json` objects |
| GET | `/api/episodes/{task}/{id}` | `{ meta, steps }` |
| GET | `/api/episodes/{task}/{id}/frame/{n}` | one step as an observation binary frame |
| DELETE | `/api/episodes/{task}/{id}` | moves the episode folder to `data/.trash` |

## Recorded episodes

```
data/<task>/<episode_id>/
  meta.json      task, seed, options, width, height, streams, schema, modVersion, pilot, client, outcome, steps, totalReward, arena
  steps.jsonl    one line per step: step, tick, state, action, reward, done
  rgb/000000.png
  depth/000000.f32   raw little-endian float32, height x width
  mask/000000.png    16-bit grayscale PNG
  state/000000.png   16-bit grayscale PNG, when recorded
```

Recordings are training data only. An episode's log lines are in the mod's session log, each tagged with its `episode`.

Step `n` pairs the frame captured before action `n` with action `n`. The last step has `action: null` and carries the terminal frame. `pilot` is `bridge` or `keyboard`, and `client` names the bridge client that flew a `bridge` episode, such as `mcdrone-env`.

## Log line

```json
{ "ts": "2026-10-02T15:30:00.123Z", "source": "mod", "level": "info", "event": "task.reset", "session": "...", "episode": "...", "tick": 1234, "...": "event fields" }
```

## Tasks

`reset` options for every task: `task` (default the config's `task`), `terrain` (`flat`, `rough`, or `cave`), `radius` (arena half-width, default 12), `obstacles` (pillars, default 0), `targets` (dig_block 1, mine_and_deliver 3, hunt_mobs 4 hostile mobs), `prey` (hunt_mobs and guard_region: `hostile`, `all`, or mob kinds as a list or separated by commas such as `cow, minecraft:zombie`), `size` (structure or deposit side for the arenas below that build one, 3 to 16, default 5), `perception` (`vision` or `scan`, default the config's `perception`, see Perception), `maxSteps`, `fleet` (see Fleets).

### Fleets

Several drones can share one training arena, each with its own task. Each drone's controller sends its own `reset` with `fleet: { "group", "size", "member" }`: every reset of a group names the same `group` and `size`, and `member` (0 to size - 1) orders them. The mod holds the resets until the whole group has asked, then the server builds one arena with every member's sites and spawn, laid out by member 0's seed, `terrain`, `obstacles`, and origin, with the radius grown by the square root of the size. Each controller gets its own first frame. A drone that finishes early waits in its next `reset` until the others finish theirs, so every arena of a fleet starts together. A fleet takes training tasks only, and up to 8 drones. A group fails when one of its drones' controllers leaves before it fills up. Recordings carry the `fleet` in their meta.

### Arena

- An arena is built around `arenaX`, `arenaZ` (config), set by the first arena to where the player stood. It clears everything in its footprint, so when a drone's home station or a drone outside the arena is inside the footprint, widened by 4 blocks, the arena moves 112 blocks east until it doesn't, and the config keeps the new spot.
- Flat arenas are light gray concrete. Rough arenas add seeded rolling hills up to 4 blocks high, grass with stone and gravel patches, and a few oak trees.
- Cave arenas have a stone floor with hills up to 3 blocks high, stone walls on the arena edge, and an uneven stone roof 7 to 10 blocks over the base floor, always at least 6 air blocks above the ground. Glowstone in the roof lights the room. Dripstone stalactites hang to 2 or 3 blocks over the floor, and a pillar taller than the room joins the roof.
- Pillars are stone brick, 1x1 or 2x2, 3 to 9 blocks tall, placed at least 2 blocks from every goal and the spawn. Fewer than requested can appear if the arena runs out of room.
- The geofence (`state.bounds`) is the arena footprint from its floor up 16 blocks, widened by `boundsPadding` (config). Leaving it ends the episode with `outOfBoundsPenalty` (config, default 10) subtracted and `outOfBounds` set.

### Reward

Every task subtracts `collisionPenalty` (config, default 0.05) on ticks the drone collides, `damagePenalty` (config, default 0.5) for each point of health a mob takes, and `jitterPenalty` (config, default 0.1) each time the yaw or pitch turn reverses direction at 1 degree a tick or more, counted in `episode.turnReversals`. A wrecked drone ends the episode with `outOfBoundsPenalty` subtracted and `wrecked` set, `episode.damage` is the health lost. Tool tasks reward approaching their current goal down to about 3 blocks, the rest comes from progress below. Success adds 10.

| Task | Goal | Metrics | Progress reward | Default maxSteps |
| --- | --- | --- | --- | --- |
| navigate_to | get within `successDist` (1.5) of the marker | none | distance closed | 400 |
| dig_block | break every coal ore block on its pedestal (plain stone pedestals are decoys) | remaining ore | +10 per ore | 400 |
| place_block | place the oak planks from slot 0 on top of the lime concrete pad | placed (0 or 1), wrong placements | -1 per wrong placement | 400 |
| chest_transfer | move every item from the stocked chest into the empty one | items in target, items required, required items carried | +10 per full set delivered, +2 per full set picked up | 600 |
| mine_and_deliver | mine every coal ore and deposit the coal in the chest | remaining ore, coal delivered, coal required, coal carried | +3 per ore, +5 per full delivery | 900 |
| replicate_build | copy the build on the cyan base onto the empty lime base, same blocks in the same places | matching cells, blueprint size, wrong blocks at the site | +10 per full copy built, -1 per wrong block | 900 |
| copy_build | copy a `size`-wide structure on a cyan base onto a lime base | as copy jobs | as copy jobs | 20000 |
| schematic_build | build a `size`-wide structure from a schematic file onto a lime base | as build jobs | as build jobs | 20000 |
| mine_deposit | mine every coal ore in a `size`-wide stone deposit, most of it buried | as mining jobs | as mining jobs | 20000 |
| gather_build | copy a structure, mining every block it needs from a stone deposit first, starting with an empty inventory | as copy jobs | as copy jobs | 20000 |
| patrol_area | fly over every patrol cell of the arena floor | as patrol jobs | +10 per round, spread over its cells | 1500 |
| hunt_mobs | kill the arena's prey, leaving its other mobs alone | as guard jobs | +10 for all of them, spread over the kills | 3000 |
| goto_point | get within 1.5 blocks of a point given as coordinates, nothing marks it | none | distance closed | 600 |
| find_block | get within 1.5 blocks of the one block of the named kind, among 3 look-alikes of other kinds | none | distance closed to the nearest one | 1500 |
| follow_mob | stay 2 to 5 blocks from a villager wandering inside the arena's wall for 200 ticks | none | gap to that band closed, +10 spread over the 200 ticks | 1500 |
| dock_station | dock on a charging station on the ground or a pedestal up to 2 high, given as a `return_home` job | none | distance closed to the spot over it | 600 |

## Jobs

Jobs run in the player's own world: nothing is built or cleared, the drone keeps its inventory, and the player stays where they are. They are `reset` tasks:

| Task | Options | Target |
| --- | --- | --- |
| `copy_region` | `source` (`[x0, y0, z0, x1, y1, z1]`, any two opposite corners) or `region` (a saved region's name), `dest` (`[x, y, z]` or a saved region's name), `gather` (optional, a box or a saved region's name) | the source box as it was when the job started |
| `build_schematic` | `schematic` (a file name in the game's `schematics` folder), `dest`, `gather` | the schematic |
| `mine_region` | `region` (`[x0, y0, z0, x1, y1, z1]` or a saved region's name), `blocks` (a list, or names separated by commas, such as `coal_ore, iron_ore`, without a namespace they're `minecraft:`) | no block of those kinds left in the region |
| `harvest_region` | `region`, `crop` (such as `minecraft:wheat`) | every ripe crop harvested and every farmland cell planted |
| `return_home` | none | the drone docked on its home charging station |
| `patrol_region` | `region`, `rounds` (1 to 20, default 1) | every patrol cell of the region flown over, `rounds` times |
| `guard_region` | `region`, `rounds`, `prey` (default `hostile`) | the patrol's rounds flown and none of its prey left in the region |
| `fly_to` | `dest` (`[x, y, z]` or a saved region's name) | the drone within 1.5 blocks of the point |
| `seek_block` | `region`, `block` (such as `bricks`) | the drone within 1.5 blocks of a block of that kind in the region, found with its camera |
| `follow_player` | none | none, the drone keeps 2 to 5 blocks from the player until the job is stopped, within 48 blocks across of where it started |

Boxes and points fall back to the player's selection, set with the tablet or `select`. `dest` is where the target's lowest corner lands, a saved region's lowest corner when it names one, and the copy keeps the source's orientation. With `gather` the drone mines the blocks it needs from that box before building. It can't overlap the copy's source or the destination, and is at most 32 blocks per side. Copy and build boxes are at most 16 blocks per side mining regions 32, and patrol regions 64, every box is within 96 blocks of the player, and only the singleplayer host can start jobs. During a job the server refuses drone breaks and places outside the job's box with a `break_failed` or `place_failed` event, so a copy only touches its destination and gather box, and a mining job never digs out of its region.

`state.job` is `{ "kind": "copy" | "build", "source": [x0, y0, z0, x1, y1, z1], "schematic": "house.schem", "dest": [x0, y0, z0, x1, y1, z1], "gather": [x0, y0, z0, x1, y1, z1], "size": [w, h, l] }` with inclusive corners, `source` only for copies, `schematic` only for builds, and `gather` only when the drone mines its materials there. A mining job's is `{ "kind": "mine", "region": [x0, y0, z0, x1, y1, z1], "blocks": ["minecraft:coal_ore", "minecraft:iron_ore"] }`, a return home's `{ "kind": "return_home", "station": [x, y, z] }`, a fly-to's `{ "kind": "goto", "point": [x, y, z], "arrive": 1.5 }`, a seek's `{ "kind": "find", "block": "minecraft:bricks", "region": [x0, y0, z0, x1, y1, z1], "arrive": 1.5 }`, a follow's `{ "kind": "follow", "target": entityId, "near": 2.0, "far": 5.0, "ticks": 0 }` with the target's position in `state.followTarget` every step, the way a player's phone would share it, and a patrol's or guard's `{ "kind": "patrol", "region": [x0, y0, z0, x1, y1, z1], "rounds": 3, "hunt": true, "prey": "hostile", "cells": [[x, z], ...], "visitRadius": 3.0 }`. The region splits into patrol cells about 8 blocks across, and a cell counts as flown over once the drone passes within `visitRadius` of its center, at any height. `hunt` is true for guards, which also kill their `prey`: `"hostile"`, `"all"`, or a list of mob kinds such as `["minecraft:cow"]`. The drone finds them with its camera. Every job also carries `tier`, the drone's, and `unharvestable`, the ids of every block that tier breaks without a drop, read from the game's tool tags. A mining job leaves out kinds the drone's tier can't harvest and fails when that leaves none. The drone gets the boxes, never the copy's contents: in vision perception it reads those with its camera, see Perception. The geofence covers the job's boxes, the drone's starting point, and its home station when that's within 96 blocks of the player, plus 6 blocks around them.

Copy and build metrics are matching destination cells, non-air target blocks, and destination blocks that don't belong. Each match is worth 10 divided by the target's block count, each wrong block costs 1, and success needs every target block in place with nothing extra. Mining metrics are the blocks of the kind left and how many there were, each one mined is worth 10 divided by the starting count, and success is none left. Block properties such as stair facing don't count yet. A job's default `maxSteps` is 100000. A return home has no metrics, the client ends it as a success once the drone docks. Patrol and guard metrics are hostile mobs left in the region, mobs the drone killed, and hostile mobs in the region at the start, all 0 for a patrol. The client counts the cells and rounds from the drone's pose. A round is worth 10, spread over its cells, and each kill is worth 10 divided by the hostile mobs at the start (at least 1).

### Queues

Each drone keeps a queue of jobs, saved with it. `status.drones` lists the player's loaded drones as `{ "id", "name", "tier", "active", "pos", "charge", "battery", "home", "docked", "queue", "controller", "episode" }`, where `battery` is whether the battery is enabled, `queue` holds each job's `reset` options plus a `label` such as `mine coal_ore in quarry`, `controller` is the client flying the drone or null, and `episode` is `{ "id", "task", "done", "success" }` for the drone's latest episode or null. The status goes out again whenever a drone's entry changes, checked once a second. While a controller flies a drone, the mod starts the drone's next queued job when one succeeds, and sends it home after the last one or after a job that fails. `run_queues`, and Run every drone's queue on the tablet, start every idle drone's first queued job at once.

Drones work at once. A job that breaks or places blocks can't share a block with another drone's running job of that sort (patrols and guards change no blocks, so they can overlap anything), its `reset` fails naming the drone that works there, and a drone's boxes are free again once its job ends. `state.droneId` says which drone a frame is from. Drones are solid to each other, the brain's map keeps it out of columns where its camera saw another drone lately, see the training README.

### Perception

The config's `perception` picks how the drone learns what blocks a job involves.

- `vision` (default): the drone gets the job's boxes and nothing else. It reads blocks with its camera, through a policy's own perception.
- `scan` for a guard job or hunt arena: every observation lists every mob in the region as `state.mobs`, the planner picks out its prey, `[{ "entity": "minecraft:zombie", "pos": [x, y, z] }]`, the middle of each one's box. A patrol or guard writes no block scans.
- `scan`: when the job starts, the server reads each of the job's boxes straight from the world, the way WorldEdit copies, and writes them to `schematics/scans/<task>-<seed>-<box>.schem`. `state.job.scan` maps each box name (`source`, `dest`, `region`, `gather`) to its file, relative to the game's `schematics` folder. Every block is there, buried or not, as its full block state, such as `minecraft:wheat[age=7]`, including blocks a vision model doesn't know. The files are a snapshot of the job's start, the drone's own breaks and places are its to keep track of.

### Regions

Regions are named boxes saved with the world in `mcdrone-regions.json`, at most 128 blocks across and any height. `status.regions` lists them as `{ "id", "name", "purpose", "box": [x0, y0, z0, x1, y1, z1] }`. Holding the tablet outlines each one in its purpose's color.

| Purpose | Effect |
| --- | --- |
| `safe` | the server refuses every drone break and place inside it, for any job or policy, with a `break_failed` or `place_failed` event naming the region |
| `mine` | where mining jobs dig |
| `farm` | where harvest jobs work |
| `general` | no effect, a named box to reuse, such as a copy source |

Only the singleplayer host can edit regions.

### Drone memory

A policy that keeps a voxel memory can show it on the dashboard by sending `memory` messages: `step`, `changes` as `[{ "cell": [x, y, z], "before", "after", "cause" }]` with labels such as `minecraft:bricks` or `minecraft:wheat ripe` and causes such as `seen`, `broke`, `placed`, an optional `snapshot` of every cell as `[x, y, z, label]` so a dashboard that joins late can catch up, and an optional `focus` box `[x0, y0, z0, x1, y1, z1]`. A snapshot at an earlier step than the last one starts a new history.

### Schematics

Schematics are Sponge Schematic files (`.schem`), the format WorldEdit uses. The mod writes version 3 and reads versions 2 and 3. Block entity contents, such as chest items, are not kept, and blocks the game doesn't know read as air. File names are letters, digits, spaces, dots, dashes, and underscores, ending in `.schem`.

### replicate_build

- The reference build stands on a 3x3 cyan concrete base, the build site is a 3x3 lime concrete base at least 9 blocks away. Both sit level at the highest ground under them.
- A build is 3 to 10 blocks over the 3x3 footprint and up to 3 tall. Every block rests on the base or another block, and the center column always has an open side so every block can be seen.
- Blocks come from a 10-block palette: oak, spruce, and birch planks, cobblestone, bricks, sandstone, white, red, and blue wool, and terracotta. Each build uses 2 to 4 of them.
- The drone starts with exactly the blocks the build needs plus 2 spares of each, in shuffled slots.
- The copy keeps the reference's orientation. Success needs every cell to match and nothing else above the site.
- `arena` adds `referenceBase` and `buildBase` (center blocks) and `blueprint` (`[{ "offset": [dx, dy, dz], "block": "minecraft:bricks" }]`), all privileged.
- `state.job` is a copy job from the 3x3x3 box above the cyan base to the one above the lime base, the same instruction a player's copy job gives.

### patrol_area, hunt_mobs

- The job's region is the arena floor inside its edge, from the floor up 16 blocks. patrol_area flies one round.
- hunt_mobs walls the arena in with two blocks of gray concrete on its border, raised to clear the ground beside it, and spawns `targets` hostile mobs (zombies, husks, skeletons with bows, and creepers) and 3 animals (cows, pigs, sheep, or chickens) on free cells. Without a `prey` option the arena picks one: half its hunts go after the hostile mobs, a fifth after every mob, and the rest after one kind it spawned. A named kind it didn't spawn gets one. The zombies and skeletons wear an unbreakable leather cap, the arena's sun would burn them. The mobs keep their AI and wander, and fight back once hit. Lockstep leaves the world running during a hunt or a follow, the target has to move. The next arena on the spot removes any left. Its job has `hunt` and no rounds to fly, success is all of its prey dead. Its metrics count the prey. It needs a difficulty above peaceful.

### copy_build, schematic_build, mine_deposit, gather_build

- Structures are column heights over a `size` x `size` footprint, up to two thirds of `size` tall (at least 3), from 2 to 4 palette blocks. Every block has a face in the open: a column two or more tall always has an empty neighbor or the footprint edge beside it.
- Each sits on a concrete base levelled to the highest ground under it, cyan for a copy source and lime for the site. The arena radius grows to fit, at least `2 * size + 8`.
- copy_build and schematic_build stock the drone like replicate_build. schematic_build writes the structure to `schematics/arena-<seed>.schem` and gives a build job for it.
- mine_deposit is a `size` x `size` stone box, `size / 2 + 1` deep (at least 3), on a stone base, with coal ore in about one cell in fifteen. About a third of the ore is on the surface, the rest buried.
- gather_build adds a stone deposit holding every block the copy needs plus one spare of each, about a third of them on its surface. The copy job carries it as `gather`, the box the drone may also break in. The source structure stays out of the job's boxes, so the server refuses any break there. The drone starts with nothing.
