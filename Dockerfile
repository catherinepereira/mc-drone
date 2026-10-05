# Two targets, built from the repo root by compose.yaml: the dashboard dev server and the training environment
# Minecraft runs on the host (scripts/minecraft.ps1), both containers reach its bridge through host.docker.internal

FROM node:24-slim AS dashboard
WORKDIR /app
COPY dashboard/package.json dashboard/package-lock.json ./
RUN npm ci
COPY dashboard/ ./
ENV MCDRONE_BRIDGE_HOST=host.docker.internal
EXPOSE 5318
CMD ["npx", "vite", "--host", "0.0.0.0"]

FROM python:3.11-slim AS train
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg && rm -rf /var/lib/apt/lists/*
RUN pip install --no-cache-dir torch==2.14.0 --index-url https://download.pytorch.org/whl/cu126
WORKDIR /repo/training
COPY training/pyproject.toml ./
COPY training/src src
RUN pip install --no-cache-dir -e ".[dev]"
ENV MCDRONE_HOST=host.docker.internal
CMD ["sleep", "infinity"]
