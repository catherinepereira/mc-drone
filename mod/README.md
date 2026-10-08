# mod

Fabric mod for Minecraft Java 26.3 that adds a pilotable quadcopter drone with a 27-slot cargo chest, a mining tool, block placing, and container access. It runs copy, build, mine, and harvest jobs in your own world. A local bridge on port 8318 streams the drone's camera, depth, semantic mask, and a third-person view of the quadcopter, and accepts flight and tool commands, so scripts and models can fly it. Flights can be recorded as datasets.

## Requirements

- JDK 25 (Microsoft OpenJDK 25 or any other build)
- Minecraft Java 26.3 with Fabric Loader 0.19.5 and Fabric API 0.161.0+26.3 to play outside the dev client

## Build and run

```powershell
$env:JAVA_HOME = "$env:USERPROFILE\.jdks\jdk-25.0.4.1+1"   # wherever JDK 25 lives
.\gradlew.bat build          # jar in build/libs
.\gradlew.bat runClient      # dev client with the mod loaded
```

`runClient` writes recordings to `../training/data`, where training reads them, and logs to `run/logs`. A normal install writes both under `.minecraft/mcdrone/`. Test recordings, the client gametest's own and any a bridge client asks for with `test`, go to `mcdrone/test-recordings` in the game folder.

To use the mod in the regular launcher, install Fabric for 26.3 with the Fabric installer, then put `build/libs/mcdrone-0.1.0.jar` and the Fabric API jar in `.minecraft/mods`.

## In game

Create a creative superflat world. The copper, iron, diamond, and netherite drones, the tablet, the charging station, and the marker block are in the Tools and Utilities tab, and all but the marker can be crafted, see Crafting.

| Key | Action |
| --- | --- |
| V | Enter or leave the drone camera |
| N | Start a new episode of the config's `task` on its `terrain` (builds the arena under you on the first use) |
| R | Open the drone inventory, and the container in front of the drone with it |
| Left click while piloting | Hold the beam on the hostile mob under the crosshair, or mine the block |
| B | Arm or stop recording, recording covers every episode that starts while armed |
| F8 | Toggle the status panel |
| J | Start a copy job from the tablet's selection |
| K | Open the job screen for the active drone |

While piloting: WASD moves, Space and Shift climb and descend, the mouse turns the camera, holding the left button mines, the right button places from the selected slot, and the hotbar keys select slots 0 to 8. In the inventory screen a click moves a stack between the drone and the open container, and a right click selects a slot. With recording armed and no bridge controller, episodes chain automatically so you can record demos back to back, until one passes with no input.

## Jobs

Hold the tablet to select. Left click a block for the first corner, right click for the second, and sneak and right click a face for the paste point. The selection shows as a blue outline and the paste point's box as an orange one. Right click into the air with the tablet for the drone list, pick a drone, and press Set up jobs, or press K, for the job screen.

| Job | Regions on the job screen |
| --- | --- |
| Copy a region | Copy from, Paste at, Materials |
| Build a schematic | Paste at, Materials, and a file from the game's `schematics` folder |
| Mine blocks in a region | Mine in, and the blocks, such as `coal_ore, iron_ore` |
| Harvest and replant crops | Farm, and the crop |
| Return home | none, the drone flies back to its charging station |

Each region is the tablet selection or a saved region. Paste at is the tablet's paste point or a saved region, whose lowest corner the build's lowest corner lands on. Materials is what the drone carries, or a saved region it mines the blocks it needs from before building. That makes a three-region job: mine region A for materials, copy region B, build in region C. The drone never breaks blocks in the copy's source, and the gather region can't overlap the source or the destination.

Start job runs a job now. Add to queue puts it on the active drone's queue, with the tablet selection copied in, so moving the selection later doesn't move queued work. Each drone keeps its own queue of up to 16 jobs, saved with the drone. The tablet shows the active drone's queue, where Run queue starts the first job, x removes one, and Clear queue empties it. With the brain connected, the next queued job starts when one finishes. After the last one, or after a job that stops short, the drone flies back to its home station. Send home does the same right away. Run every drone's queue starts the first job of every idle drone, and the drones work at once, each flown by its own brain worker. A job can't share blocks with another drone's running job. The dashboard's Drones panel shows every drone's queue and the job it's on.

To set one up, select a box with the tablet, name it on the job screen's bottom row, pick a purpose, and save it. Repeat for each region, then pick them on the job screen and start. Saved regions show while you hold the tablet, colored by purpose. `safe` boxes are never broken or built in by a drone. `mine` and `farm` are labels for the boxes those jobs work in. Press J to copy the selection straight away. The dashboard's Jobs and Regions panels do the same, and can save the selection as a `.schem` file.

The drone brain in `training` flies the jobs over the bridge, see its README. With `perception` set to `vision` (the default) it reads blocks with its camera, never from the world data. With `scan` the server also writes each of the job's boxes to a schematic for it, read straight from the world, crop ages included. Place actions name the block, and the drone takes it from its inventory, or with `materials` set to `unlimited` in the config, places it without using items.

## The drone

A quadcopter a little under a block across, with spinning rotors, a camera pod that tilts with the camera, and a cargo chest under it. It leans into its motion. A nametag over it shows its name and its owner's, such as `Harvester (Steve)`. The owner renames it by using a named name tag on it, or from the dashboard's Control panel. Any player can use the drone (right click) to open the chest and put items in or take them out, within reach like a chest boat. The owner picks it back up by sneaking and using it with an empty hand, its cargo drops.

| Tier | Breaks blocks like | Beam damage | Colors |
| --- | --- | --- | --- |
| Copper | a copper pickaxe | 5 | copper arms, orange accents |
| Iron | an iron pickaxe, faster, and diamond ore drops | 6 | gray arms, white accents |
| Diamond | a diamond pickaxe, faster, and obsidian drops | 7 | teal arms, cyan accents |
| Netherite | a netherite pickaxe, fastest, the same drops as diamond. The item survives fire and lava | 8 | dark arms, mauve accents |

The drone fights with a guardian's beam: held on a hostile mob up to 10 blocks away it locks on, charges from purple to yellow, and hits for the tier's sword damage. It never hits animals, villagers, or players. A mob it hits fights back, and only mobs can hurt a drone. A drone has 20 health and repairs on its home station. Shot down in your world, it drops its cargo and itself. Range sensors report the gap to the nearest block straight ahead and straight below, up to 4 blocks (`sensorRange` in the config).

A player can own any number of drones. The tablet lists the loaded ones with their tier, charge, home station, and the job each is on, and the one you pick is the active drone that the job screen, piloting, and the HUD use. Placing a drone makes it the active one. Several drones fly at once, each with its own bridge controller, see docs/PROTOCOL.md.

## Battery and charging

A drone runs on a battery, about 15 minutes of flight on a full charge. Flying draws full power, hovering in place half, and each block broken about 2 seconds of flight. A drone that hasn't moved or broken anything for 5 seconds powers down and draws nothing. Out of charge it can't fly or use tools and sinks to the ground where it is.

Right click a charging station with the tablet to make it the active drone's home. Drones are solid to each other, so a station is home to one drone. A drone sitting on top of its home station charges, empty to full in about 3 minutes. A job's geofence takes in the home station when it's within reach, so the drone can fly back mid-job.

The battery counts drone ticks: each pose the flying client sends is one tick, and an idle drone runs on server ticks. That keeps it running in lockstep, where server ticks are frozen. A training arena recharges the drone and keeps it from running down, so long training runs don't depend on the charge.

The settings are in `config/mcdrone-battery.json`, written with the defaults on the first start: `enabled` (false turns the battery off, drones never run down), `flightMinutes`, `hoverShare`, `breakSeconds`, `chargeMinutes`, and `reserve`, the share of a charge the brain keeps for the flight home. The drone's state carries its charge, home, and these settings under `battery`.

## Web map

With [BlueMap](https://modrinth.com/mod/bluemap) installed next to the mod, its web map at `http://localhost:8100` shows the saved regions as colored boxes, each drone with its tier's icon, charge, and queue, and the charging stations. Drone markers move every second, as often as BlueMap moves players: the mod writes the drones' positions to `mcdrone/drones.json` in BlueMap's web root, and a small script it adds to the web app moves the markers from it. The same script glides the camera after the drone the dashboard follows. Blocks that drones and arenas change are saved and rendered again every 5 seconds, BlueMap renders from the region files. Toggle the markers from the map's menu. The dashboard's Map tab embeds it. The dev client loads BlueMap 5.28. BlueMap waits for `accept-download: true` in `config/bluemap/core.conf`, which accepts Mojang's EULA and lets it download the Minecraft client jar it renders with. The client gametest's run folder is cleared on every launch, so its BlueMap settings come from `devconfig/bluemap/`: `core.conf` with the download on, and `webserver.conf`, which binds the map to `127.0.0.1`. For `runClient`, set it in `run/config/bluemap/core.conf` after the first start, then `/bluemap reload`. Without BlueMap the mod runs the same, minus the map.

## Crafting

Rows top to bottom, `_` is an empty slot.

| Item | Recipe |
| --- | --- |
| Copper, iron, or diamond drone | `M _ M` / `_ R _` / `M C M`, M a copper ingot, iron ingot, or diamond, R a block of redstone, C a chest |
| Tablet | `I R I` / `I D I` / `I R I`, I an iron ingot, R redstone, D a diamond |
| Netherite drone | a diamond drone, a netherite ingot, and a netherite upgrade template at a smithing table |
| Charging station | `C L C` / `I R I` / `C C C`, C a copper ingot, L a lightning rod, I an iron ingot, R a block of redstone |

## Tasks

| Task | Goal |
| --- | --- |
| navigate_to | fly to the orange marker block |
| dig_block | mine the coal ore among stone decoy pedestals |
| place_block | place oak planks on the lime pad |
| chest_transfer | move everything from the stocked chest into the empty one |
| mine_and_deliver | mine every coal ore and put the coal in the chest |
| replicate_build | copy the small build on the cyan base onto the lime base |
| harvest_crops | harvest the ripe wheat in a field and replant every plot |
| patrol_area | fly over every part of the arena |
| hunt_mobs | kill the hostile mobs walled into the arena, leaving its animals alone |
| copy_build | copy a random structure `size` wide onto a lime base |
| schematic_build | build a random structure from a `.schem` file |
| mine_deposit | mine every block of one kind from a stone deposit, most of them buried |
| gather_build | mine materials from a deposit (A), copy a structure (B) without touching it, build the copy (C) |
| copy_region | copy a selected box to a paste point, in your own world |
| build_schematic | build a `.schem` file at a paste point, in your own world |
| mine_region | mine every block of the chosen kinds inside a region, in your own world |
| harvest_region | harvest and replant a crop in a region, in your own world |
| patrol_region | fly over every part of a region, round after round, in your own world |
| guard_region | patrol a region and kill the hostile mobs in it, in your own world |
| goto_point | fly to a point given as coordinates |
| find_block | find the one block of a named kind among look-alikes and fly to it |
| follow_mob | stay 2 to 5 blocks from a wandering villager |
| fly_to | fly to the paste point, in your own world |
| seek_block | find a block of a named kind in a region and fly to it, in your own world |
| follow_player | follow you at 2 to 5 blocks until stopped |
| return_home | fly back to the drone's charging station and dock on it |

Every arena task takes `terrain` (`flat`, `rough` with hills and trees, or `cave` with a roof, walls, and stalactites) and `obstacles` (stone brick pillars), and the structure arenas take `size`. The arena's footprint is a geofence: leaving it ends the episode with a penalty. Rewards and progress counters are in [docs/PROTOCOL.md](../docs/PROTOCOL.md#tasks).

## Bridge

The full message reference is [docs/PROTOCOL.md](../docs/PROTOCOL.md). In short:

- `ws://127.0.0.1:8318/ws` takes JSON commands (`hello`, `configure`, `reset`, `step`, `act`, `record`, `pilot`, `release`) and returns observations as binary frames.
- One client holds the controller role. Everyone else is an observer and receives frames, logs, and metrics.
- `realtime` mode runs the game normally. `lockstep` freezes world ticks and advances only on `step`.
- `/api/status`, `/api/config`, `/api/logs`, and `/api/episodes` serve the dashboard.
- Requests carrying a browser `Origin` other than the dashboard's `http://localhost:5318` are refused.

## Observations

| Stream | Source |
| --- | --- |
| RGB | GPU readback of the main framebuffer after the world pass, before the HUD, center-cropped and box-downsampled |
| Depth | One raycast per output pixel through the same frustum, z-distance in blocks |
| Mask | Same raycasts, block or entity registry id per pixel |
| State | Same raycasts, block state id per pixel, labels for training the block reader |
| Chase | A frame from vanilla's third-person camera behind the drone, rendered after the drone's own, for videos |

Depth and mask come from raycasts, so they don't depend on the graphics backend. They match block outline shapes, not textures, so leaves and glass read as solid.

## Config

`config/mcdrone.json` holds frame size, streams and the chase view's size, stream rate, flight limits, the default task and terrain, arena settings, the collision and out-of-bounds penalties, the geofence padding, block perception (`vision` or `scan`), materials, the pause after tool actions, and log level. The dashboard's Config tab edits it live.

## Tests

```powershell
.\gradlew.bat runClientGameTest                  # launches a client and checks tasks, jobs, tiers, the attack tool, docking and the battery, the job queue, the drone's chest, frames, and recording over the bridge
.\gradlew.bat runClientGameTest -Pe2eHold=600    # same, then keeps the world open for the end-to-end tests in training
```

Screenshots from the run land in `build/run/clientGameTest/screenshots`.

## Layout

```
src/main/       drone entity and inventory, battery, tools, payloads, arena and terrain builder, task metrics, BlueMap markers (runs on the server)
src/client/     piloting, bridge server, capture, recorder, scoring, HUD, job and inventory screens, drone model (runs on the client)
src/gametest/   client gametest
```

## Known limitations

- Singleplayer only. The drone pose is client-authoritative and lockstep freezes the integrated server.
- There is one game camera, so drones flying at once take turns rendering their frames, one frame each. The window shows whichever drone rendered last. Frames are cheap, the depth and mask raycast is most of a capture, so drones flying at once share about 150 captures a second.
- Drops follow the tier's pickaxe. Gold, diamond, emerald, and redstone ore need an iron drone or better, and obsidian a diamond or netherite one. Mine jobs leave out kinds the drone gets nothing from, the job screen warns about them before you start, and the brain digs around those blocks.
- RGB capture reads the main framebuffer, so frames show whatever the game window renders at its current FOV and settings. Keep the window open and unminimized while streaming.
- Each lockstep step waits for one rendered frame, so step rate follows FPS. Minecraft drops to 10 FPS when the window is unfocused for a while, set Video Settings, Inactivity FPS Limit to Minimized to keep full speed in the background.
- The marker block can't be broken in survival. It's meant for the arena, not gameplay.
