# mod

Fabric mod for Minecraft Java 26.3 that adds a pilotable drone with a 27-slot inventory, a mining tool, block placing, and container access. A local bridge on port 8318 streams the drone's camera, depth, and semantic mask, and accepts flight and tool commands, so scripts and models can fly it. Flights can be recorded as datasets.

## Requirements

- JDK 25 (Microsoft OpenJDK 25 or any other build)
- Minecraft Java 26.3 with Fabric Loader 0.19.5 and Fabric API 0.161.0+26.3 to play outside the dev client

## Build and run

```powershell
$env:JAVA_HOME = "$env:USERPROFILE\.jdks\jdk-25.0.4.1+1"   # wherever JDK 25 lives
.\gradlew.bat build          # jar in build/libs
.\gradlew.bat runClient      # dev client with the mod loaded
```

`runClient` writes recordings to `../training/data`, where training reads them, and logs to `run/logs`. A normal install writes both under `.minecraft/mcdrone/`.

To use the mod in the regular launcher, install Fabric for 26.3 with the Fabric installer, then put `build/libs/mcdrone-0.1.0.jar` and the Fabric API jar in `.minecraft/mods`.

## In game

Create a creative superflat world. The drone, the drone remote, and the marker block are in the Tools and Utilities tab.

| Key | Action |
| --- | --- |
| V | Enter or leave the drone camera |
| N | Start a new episode of the config's `task` on its `terrain` (builds the arena under you on the first use) |
| R | Open the drone inventory, and the container in front of the drone with it |
| B | Arm or stop recording, recording covers every episode that starts while armed |
| F8 | Toggle the status panel |
| J | Start a copy job from the remote's selection |
| K | Open the job screen, the same as right clicking into the air with the remote |

While piloting: WASD moves, Space and Shift climb and descend, the mouse turns the camera, holding the left button mines, the right button places from the selected slot, and the hotbar keys select slots 0 to 8. In the inventory screen a click moves a stack between the drone and the open container, and a right click selects a slot. With recording armed and no bridge controller, episodes chain automatically so you can record demos back to back.

## Copy and build jobs

Hold the drone remote to select. Left click a block for the first source corner, right click for the second, and sneak and right click a face for the paste point. The source shows as a blue outline and the destination as an orange one. Right click into the air with the remote, or press K, for the job screen: copy a region, build a schematic, or mine every block of a kind in a region, using the selection or any saved region. It also saves the selection as a named region. Press J to copy straight away, or use the dashboard's Jobs and Regions panels. The dashboard can also save the source as a `.schem` file or build a schematic from the game's `schematics` folder at the paste point.

The Regions panel on the dashboard saves the selection as a named region: `safe` boxes are never broken or built in by a drone, `mine` and `farm` boxes are where those jobs work. Holding the remote shows every region.

The drone flies a job with a learned policy over the bridge. With `perception` set to `vision` (the default) it reads blocks with its camera, never from the world data. With `scan` the server also writes each of the job's boxes to a schematic for it, read straight from the world. The brain runs from `training`, see its README. Place actions name the block, and the drone takes it from its inventory, or with `materials` set to `unlimited` in the config, places it without using items.

## Tasks

| Task | Goal |
| --- | --- |
| navigate_to | fly to the orange marker block |
| dig_block | mine the coal ore among stone decoy pedestals |
| place_block | place oak planks on the lime pad |
| chest_transfer | move everything from the stocked chest into the empty one |
| mine_and_deliver | mine every coal ore and put the coal in the chest |
| replicate_build | copy the small build on the cyan base onto the lime base |
| copy_region | copy a selected box to a paste point, in your own world |
| build_schematic | build a `.schem` file at a paste point, in your own world |
| mine_region | mine every block of a kind inside a region, in your own world |

Every task takes `terrain` (`flat`, `rough` with hills and trees, or `cave` with a roof, walls, and stalactites) and `obstacles` (stone brick pillars). The arena's footprint is a geofence: leaving it ends the episode with a penalty. Rewards and progress counters are in [protocol/PROTOCOL.md](protocol/PROTOCOL.md#tasks).

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

Depth and mask come from raycasts, so they don't depend on the graphics backend. They match block outline shapes, not textures, so leaves and glass read as solid.

## Config

`config/mcdrone.json` holds frame size, streams, stream rate, flight limits, the default task and terrain, arena settings, the collision and out-of-bounds penalties, the geofence padding, and log level. The dashboard's Config tab edits it live.

## Tests

```powershell
.\gradlew.bat runClientGameTest                  # launches a client, flies navigate_to and dig_block over the bridge, checks frames, mining, and recording
.\gradlew.bat runClientGameTest -Pe2eHold=600    # same, then keeps the world open for the end-to-end tests in training
```

Screenshots from the run land in `build/run/clientGameTest/screenshots`.

## Layout

```
src/main/       drone entity and inventory, tools, payloads, arena and terrain builder, task metrics (runs on the server)
src/client/     piloting, bridge server, capture, recorder, scoring, HUD and inventory screen (runs on the client)
src/gametest/   client gametest
```

## Known limitations

- Singleplayer only. The drone pose is client-authoritative and lockstep freezes the integrated server.
- One drone per player.
- Mining uses a fixed stone pickaxe, so gold, diamond, emerald, and redstone ore break but drop nothing.
- RGB capture reads the main framebuffer, so frames show whatever the game window renders at its current FOV and settings. Keep the window open and unminimized while streaming.
- Each lockstep step waits for one rendered frame, so step rate follows FPS. Minecraft drops to 10 FPS when the window is unfocused for a while, set Video Settings, Inactivity FPS Limit to Minimized to keep full speed in the background.
- The marker block can't be broken in survival. It's meant for the arena, not gameplay.
