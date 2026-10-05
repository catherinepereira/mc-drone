# Builds and starts the dashboard and training containers. Extra arguments go to docker compose up, such as a service name
$root = Split-Path $PSScriptRoot -Parent
docker compose -f "$root\compose.yaml" up -d --build @args
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
"Dashboard: http://localhost:5318"
"Training shell: docker compose exec train bash"
"Run a job runner: docker compose exec train python -m drone_model.brain"
"Start Minecraft with the mod on the host: scripts\minecraft.ps1"
