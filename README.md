# mc-drone

Pilotable drone for Minecraft Java whose camera feeds external programs, for recording flights and training models that fly it.

| Folder | What it is |
| --- | --- |
| [mc-drone-mod](mc-drone-mod) | Fabric mod: drone, piloting, jobs, bridge server, recorder, HUD |
| [mc-drone-py](mc-drone-py) | Python client, Gymnasium env, dataset loader, schematic files |
| [mc-drone-dashboard](mc-drone-dashboard) | Browser dev panel |
| [mc-drone-model](mc-drone-model) | Block reader, job planners, learned skill, the drone brain |
| [scripts](scripts) | Start the containers and Minecraft |

[SCOPE.md](SCOPE.md) has the plan and milestones. The bridge protocol is in [mc-drone-mod/protocol/PROTOCOL.md](mc-drone-mod/protocol/PROTOCOL.md).

## Generated files

| Folder | Written by |
| --- | --- |
| `mc-drone-model/data` | the mod's recordings in dev runs, and skill training data |
| `mc-drone-model/checkpoints`, `mc-drone-model/reports` | training and evaluation |
| `mc-drone-mod/run/logs` | the mod's JSONL logs in dev runs |

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
docker compose exec train bash                              # training shell in mc-drone-model
```

Both containers reach the game's bridge through `host.docker.internal`. The training container mounts `mc-drone-model`, so data, checkpoints, and reports land on the host. `scripts/up.sh` and `scripts/minecraft.sh` do the same on Linux and macOS.

Without Docker, see each folder's README: `npm run dev` in the dashboard, and the `.venv` setup in mc-drone-model.
