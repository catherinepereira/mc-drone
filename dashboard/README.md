# dashboard

Developer panel for the mc-drone mod. It shows the drone's camera, depth, and mask live, drives the drone, tails logs, browses recorded episodes, plots runtime metrics, and edits the mod config. It has no backend and talks straight to the mod's bridge.

## Setup

```powershell
npm install
npm run dev        # http://localhost:5318
```

Start Minecraft with the mod first. The page reconnects every two seconds until the bridge on port 8318 answers. Vite proxies `/api` and `/ws` to the bridge, and the mod only accepts browser requests from `http://localhost:5318`. `MCDRONE_BRIDGE_HOST` sets where the bridge is, `127.0.0.1` by default and `host.docker.internal` in the dashboard container.

## Tabs

| Tab | What it does |
| --- | --- |
| Live | Camera, depth, and mask with hover readouts (pixel, depth, block name), the drone inventory and open container, a top-down arena map with the geofence, drone state, episode progress, recent actions, controls, the Jobs panel for the copy selection, copy, schematic, and mining jobs, and saving schematics, the Regions panel for saving, selecting, and deleting named regions, and the Drone memory panel, which shows a running policy's voxel memory layer by layer with a feed of its changes and a step slider to replay them |
| Episodes | Recorded episodes with a frame scrubber, per-step reward, and move to trash |
| Logs | Live log tail with level, event, and episode filters, click a line for its full JSON |
| Metrics | Steps and frames per second, capture and raycast time, encode time, bridge round trip, dropped frames, recorder queue |
| Config | Frame size, streams, flight limits, arena settings, building materials, log level |

The dashboard joins as an observer. Take control to switch modes, start an episode of any task and terrain, toggle recording, move stacks by clicking inventory slots, and drive with the keyboard (WASD, Space, Shift, arrow keys, F to mine, G to place, R to open or close a container). Release hands control back.

## Layout

```
src/protocol.ts       observation decoder and message types, mirrors docs/PROTOCOL.md
src/stores/bridge.ts  WebSocket connection and live state
src/api.ts            HTTP calls
src/components/       one view per tab plus shared pieces
```
