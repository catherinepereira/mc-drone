# Two targets, built from the repo root by compose.yaml: the dashboard dev server and the training environment.
# Minecraft runs on the host (scripts/minecraft.ps1), both containers reach its bridge through host.docker.internal

FROM node:24-slim AS dashboard
WORKDIR /app
COPY mc-drone-dashboard/package.json mc-drone-dashboard/package-lock.json ./
RUN npm ci
COPY mc-drone-dashboard/ ./
ENV MCDRONE_BRIDGE_HOST=host.docker.internal
EXPOSE 5318
CMD ["npx", "vite", "--host", "0.0.0.0"]

FROM python:3.11-slim AS train
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg && rm -rf /var/lib/apt/lists/*
RUN pip install --no-cache-dir torch==2.14.0 --index-url https://download.pytorch.org/whl/cu126
WORKDIR /repo
COPY mc-drone-py/pyproject.toml mc-drone-py/
COPY mc-drone-py/src mc-drone-py/src
COPY mc-drone-model/pyproject.toml mc-drone-model/
COPY mc-drone-model/src mc-drone-model/src
RUN pip install --no-cache-dir -e mc-drone-py -e "mc-drone-model[dev]"
WORKDIR /repo/mc-drone-model
ENV MCDRONE_HOST=host.docker.internal
CMD ["sleep", "infinity"]
