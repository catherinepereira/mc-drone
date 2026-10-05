"""JSONL logging in the same line shape the mod writes"""

from __future__ import annotations

import json
import threading
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

LEVELS = ("debug", "info", "warn", "error")


class JsonlLogger:
    def __init__(self, path: Path | None, level: str = "info", source: str = "py") -> None:
        self.source = source
        self.level = level
        self.session = datetime.now().strftime("%Y%m%d-%H%M%S")
        self.episode: str | None = None
        self._lock = threading.Lock()
        self._file = None
        if path is not None:
            path.mkdir(parents=True, exist_ok=True)
            self._file = open(path / f"{source}-{self.session}.jsonl", "a", encoding="utf-8")

    def log(self, level: str, event: str, **fields: Any) -> None:
        if LEVELS.index(level) < LEVELS.index(self.level):
            return
        line = {
            "ts": datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z"),
            "source": self.source,
            "level": level,
            "event": event,
            "session": self.session,
        }
        if self.episode:
            line["episode"] = self.episode
        line.update(fields)
        if self._file is not None:
            with self._lock:
                self._file.write(json.dumps(line, default=str) + "\n")
                self._file.flush()

    def debug(self, event: str, **fields: Any) -> None:
        self.log("debug", event, **fields)

    def info(self, event: str, **fields: Any) -> None:
        self.log("info", event, **fields)

    def warn(self, event: str, **fields: Any) -> None:
        self.log("warn", event, **fields)

    def error(self, event: str, **fields: Any) -> None:
        self.log("error", event, **fields)

    def close(self) -> None:
        if self._file is not None:
            self._file.close()
            self._file = None
