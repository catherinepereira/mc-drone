# mc-drone

Pilotable drone for Minecraft Java whose camera feeds external programs, for recording flights and training models that fly it.

In your own world the drone runs jobs you set up with its remote: copy a region, build a schematic, mine a block, or harvest a field. A copy or build can mine its materials from a third region first. It reads blocks with its camera through a learned block reader, or with scan perception straight from the world. Training arenas cover the same jobs plus navigation and tool tasks, on flat, rough, or cave terrain.

| Folder | What it is |
| --- | --- |
| [mod](mod) | Fabric mod: drone, piloting, jobs, bridge server, recorder, HUD |
| [dashboard](dashboard) | Browser dev panel |
| [training](training) | `mcdrone` (bridge client, Gymnasium env, dataset loader, schematic files) and `drone_model` (block reader, job planners, learned skill, the drone brain) |
| [docs](docs) | [PROTOCOL.md](docs/PROTOCOL.md), the bridge protocol all three parts follow, and [SCOPE.md](docs/SCOPE.md), the plan and milestones |
| [scripts](scripts) | Start the containers and Minecraft |

## Generated files

| Folder | Written by |
| --- | --- |
| `training/data` | the mod's recordings in dev runs, and skill training data |
| `training/checkpoints`, `training/reports` | training and evaluation |
| `training/logs` | long training and collection runs |
| `mod/run/logs` | the mod's JSONL logs in dev runs |

All of them are gitignored. A normal install of the mod writes recordings and logs under `.minecraft/mcdrone/`.

## Quick start

Minecraft runs on the host, it needs the GPU and a window:

```powershell
scripts\minecraft.ps1          # dev client with the mod, create a creative superflat world
scripts\minecraft.ps1 -Arena   # or a test world with the drone and an arena ready for scripts
```

It needs a JDK 25 in `JAVA_HOME` or under `~/.jdks`. In game, press N to start an episode, V to fly, B to record.

The dashboard and the training environment run in Docker (Docker Desktop with the NVIDIA GPU support for training):

```powershell
scripts\up.ps1                                              # build and start both, dashboard on http://localhost:5318
docker compose exec train python -m drone_model.brain       # run jobs started in game or on the dashboard
docker compose exec train bash                              # training shell in training/
```

Both containers reach the game's bridge through `host.docker.internal`. The training container mounts `training`, so data, checkpoints, and reports land on the host. `scripts/up.sh` and `scripts/minecraft.sh` do the same on Linux and macOS.

Without Docker, see each folder's README: `npm run dev` in the dashboard, and the `.venv` setup in training.
