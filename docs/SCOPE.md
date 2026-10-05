# mc-drone scope

Minecraft Java mod that adds a pilotable drone. A program outside the game can read what the drone sees and send it commands. The point is to record human flights and train models, starting with vision models, that fly the drone on their own.

Status: v1 (M0 to M6) done. v2 adds drone tools (inventory, mining, placing, containers), the dig_block, place_block, chest_transfer, mine_and_deliver, and replicate_build tasks, rough and cave terrain, a geofence, and perception-only scripted experts. Learned policies for the tool tasks are next.

## Target platform

| Item | Choice |
| --- | --- |
| Minecraft | Java 26.3, the current release (26.2 was current when this was drafted) |
| Loader | Fabric (Loader 0.19.5, Loom 1.18, Fabric API 0.161.0+26.3, unobfuscated) |
| JDK | 25 (required for 26.x modding) |
| Build | Gradle wrapper from the Fabric template |
| Side | Singleplayer (integrated server). Dedicated servers are out of scope for v1 |

## Layout

```
mc-drone/                  one repo, Dockerfile and compose.yaml for the dashboard and training
  docs/                    this plan and the bridge protocol
  mod/                     Fabric mod (Java)
  dashboard/               TypeScript dev dashboard (React + Vite + Tailwind v4), no backend
  training/                Python packages `mcdrone` (bridge client, Gymnasium env, dataset loader) and `drone_model` (PyTorch, GPU)
  scripts/                 start the containers and Minecraft
```

The bridge message schema is defined once in `docs/PROTOCOL.md`. The Python and TypeScript sides mirror their types from it, and a schema version mismatch is rejected at connect time.

## Ports

| Port | Used by |
| --- | --- |
| 8318 | Mod bridge: WebSocket for control and streams, HTTP for logs, episodes, and config |
| 5318 | Dashboard dev server, proxies `/api` and `/ws` to 8318 |

## 1. Drone

- Small flying entity (about 0.6 block) spawned from a creative-tab drone item.
- Velocity-commanded movement. An action sets a target velocity and yaw rate, and the drone eases toward it with light smoothing. No gravity, no inertia beyond the smoothing.
- Camera pitch is separate from body yaw.
- No inventory or tools in v1. The action format reserves a slot for them so adding them later doesn't break recorded data.
- Collides with blocks and entities. Takes no damage.

## 2. Piloting

- Keybind to enter the drone. The camera moves to the drone and player input drives it. The player's body stays parked and invulnerable while piloting.
- Controls: WASD horizontal, Space/Shift vertical, mouse for yaw and pitch.
- Keyboard input is converted to the same action format the bridge uses, so human demos and model rollouts produce identical records.

## 3. Observations

| Stream | Format | Notes |
| --- | --- | --- |
| RGB | uint8 W x H x 3, default 160 x 120 | Offscreen render from the drone camera, independent of the window size |
| Depth | float32 per pixel, in blocks | One raycast per pixel through the camera frustum |
| Semantic mask | uint16 id per pixel | Block or entity hit by the same raycasts, with an id table sent on connect |
| State | JSON | position, velocity, yaw, pitch, looked-at block, tick, task info |

Depth and mask are toggled per session. Raycasts keep them independent of the graphics backend.

## 4. Bridge

- WebSocket and HTTP server inside the mod on `127.0.0.1:8318`.
- One controller connection sends actions. Any number of observers receive streams read-only. The dashboard connects as an observer and can request control.
- Two timing modes:
  - Realtime: the game runs normally and the last action stays in effect until replaced.
  - Lockstep: the game is frozen with the vanilla tick freeze and advances N ticks per `step`. Gives reproducible rollouts and lets slow models keep up.
- JSON envelopes for control and state, binary frames for images.
- Commands: `step`, `act`, `reset(task, seed)`, `configure(streams, resolution, mode)`, `record(start|stop)`, `ping`.
- HTTP: `GET /api/logs`, `GET /api/episodes`, `GET /api/episodes/:id/frames/:n`, `GET /api/config`, `PUT /api/config`.

## 5. Recording

Recording happens in the mod so a human demo needs only the game running.

```
data/<task>/<episode_id>/
  meta.json        task, seed, resolution, streams, mod version, outcome, step count
  steps.jsonl      one line per step: tick, action, state, reward, done
  rgb/000000.png
  depth/000000.f32  raw little-endian float32
  mask/000000.png  16-bit PNG
  log.jsonl        log lines scoped to this episode
```

Start and stop from a keybind, the HUD, the dashboard, or the bridge.

## 6. Tasks

v1 has one task, `navigate_to`. A marker block (a colored beacon-like block added by the mod) spawns at a random position in a bounded area of a superflat test world, and the drone spawns elsewhere. Optional stone brick pillars (1x1 or 2x2, 3 to 9 tall) stand between them, kept 2 blocks clear of both. Success when the drone is within 1.5 blocks of the marker. The episode times out after a set number of steps. Reward is the reduction in distance per step, minus a small penalty on ticks the drone collides, plus a success bonus.

The task's resets are seeded and reproducible. The marker position and pillar layout are in `state` for scripted experts and the dashboard's arena map. The Gym env leaves them out, so the policy has to find the marker and avoid pillars from pixels.

Drone tools and the `dig_block` and `chest_transfer` tasks come after v1.

## 7. Logging

- JSONL with a shared shape: `ts`, `source` (mod, bridge, py, dashboard), `level`, `event`, `session_id`, `episode_id`, `tick`, plus event fields.
- Events: connections, every action applied, mode changes, task resets, outcomes, render timings, dropped frames, and errors with stack traces.
- Mod logs go to `mod/run/logs/mod-<session>.jsonl` in dev runs and are also tailed over the bridge. The Python package writes `py-<session>.jsonl` to the folder it's given.
- Levels and per-category toggles in the mod config, editable from the dashboard.

## 8. Developer panels

In-game HUD, toggled with F8:
- Drone pose, velocity, looked-at block
- Bridge mode, controller connected, observer count, steps per second, frame latency
- Task, step, reward, recording indicator

Dashboard on `localhost:5318`, light theme in the sorty and united-stats style (off-white background, white cards, Space Grotesk, one accent color):
- Live: RGB, depth, and mask side by side with the state readout and an action log
- Control: take control, switch modes, step, reset task, start or stop recording, drive the drone from on-screen buttons or the keyboard
- Logs: live tail with filters by source, level, event, and episode
- Episodes: list with outcome and length, frame scrubber with the action timeline underneath, delete
- Metrics: steps per second, render time per stream, bridge latency over time
- Config: resolution, streams, log levels

## 9. Baseline model (`training`)

- Behavior cloning on recorded `navigate_to` demos. Small CNN over RGB plus depth, predicting the action.
- Evaluation: run the policy through `DroneEnv` in lockstep mode across fixed seeds and report success rate and mean steps.
- PPO fine-tuning from the BC weights on the same env, results in training/README.md.
- Trains on the GPU with CUDA torch.

## Milestones

| # | Milestone | Done when |
| --- | --- | --- |
| M0 | Toolchain + layout | Folders in place, `gradlew runClient` launches 26.3 with an empty mod |
| M1 | Drone + piloting | Spawn the drone, fly it from its camera, return to the player |
| M2 | Bridge + observations | A Python script receives RGB, depth, mask, and state and moves the drone in both timing modes |
| M3 | Logging + HUD + dashboard | Live view, log tail, metrics, and config working against a running game |
| M4 | Task + recording | `navigate_to` resets and scores, human demos record and show in the episode browser |
| M5 | Gym env | `DroneEnv` passes Gymnasium's env checker and runs a random policy end to end |
| M6 | Baseline model | BC policy trained on demos, success rate reported over fixed eval seeds |

## Testing

- Mod: JUnit for action application and message parsing, and Fabric game tests that run headless for drone movement and task scoring.
- Python: pytest against a fake bridge, and an integration test against a running dev client.
- Dashboard: typecheck and build, and Playwright screenshots against a running game.
- Each milestone is checked in a `runClient` session with screenshots.

## Out of scope for v1

- Multiplayer and dedicated servers
- Multiple drones at once
- Survival recipes, fuel, or drone damage
- Publishing to Modrinth or CurseForge
