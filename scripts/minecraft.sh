#!/usr/bin/env bash
# Runs Minecraft with the mod built from source
# --arena opens a test world with the drone and an arena ready, so scripts can drive it without creating a world by hand
set -euo pipefail
mod="$(cd "$(dirname "$0")/../mod" && pwd)"
if [ -z "${JAVA_HOME:-}" ]; then
  jdk="$(ls -d "$HOME"/.jdks/jdk-25* 2>/dev/null | head -1 || true)"
  [ -n "$jdk" ] || { echo "set JAVA_HOME to a JDK 25" >&2; exit 1; }
  export JAVA_HOME="$jdk"
fi
if [ "${1:-}" = "--arena" ]; then
  "$mod/gradlew" -p "$mod" runClientGameTest "-Pe2eHold=${HOLD:-86400}" --console=plain
else
  "$mod/gradlew" -p "$mod" runClient --console=plain
fi
