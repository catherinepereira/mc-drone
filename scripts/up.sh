#!/usr/bin/env bash
# Builds and starts the dashboard and training containers. Extra arguments go to docker compose up, such as a service name
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
docker compose -f "$root/compose.yaml" up -d --build "$@"
echo "Dashboard: http://localhost:5318"
echo "Training shell: docker compose exec train bash"
echo "Run a job runner: docker compose exec train python -m drone_model.brain"
echo "Start Minecraft with the mod on the host: scripts/minecraft.sh"
