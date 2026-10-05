import { useEffect, useMemo, useRef, useState } from "react";
import { api } from "../api";
import type { LogLine } from "../protocol";
import { seedLogs, useBridge } from "../stores/bridge";
import { time } from "../utils/format";
import { Button, Card, Field, Pill, inputClass } from "./ui";

const LEVELS = ["debug", "info", "warn", "error"] as const;
const LEVEL_TONE = {
  debug: "neutral",
  info: "accent",
  warn: "amber",
  error: "red",
} as const;
const BASE_FIELDS = new Set([
  "ts",
  "source",
  "level",
  "event",
  "session",
  "episode",
  "tick",
]);

function extraFields(line: LogLine): string {
  const rest = Object.entries(line).filter(([k]) => !BASE_FIELDS.has(k));
  return rest
    .map(([k, v]) => `${k}=${typeof v === "string" ? v : JSON.stringify(v)}`)
    .join("  ");
}

export function LogsView() {
  const logs = useBridge((s) => s.logs);
  const [minLevel, setMinLevel] = useState<(typeof LEVELS)[number]>("info");
  const [event, setEvent] = useState("");
  const [episode, setEpisode] = useState("");
  const [paused, setPaused] = useState(false);
  const [frozen, setFrozen] = useState<LogLine[]>([]);
  const [expanded, setExpanded] = useState<string | null>(null);
  const bottom = useRef<HTMLDivElement>(null);

  useEffect(() => {
    api
      .logs(1000)
      .then(seedLogs)
      .catch(() => {});
  }, []);

  const source = paused ? frozen : logs;
  const shown = useMemo(
    () =>
      source.filter(
        (l) =>
          LEVELS.indexOf(l.level) >= LEVELS.indexOf(minLevel) &&
          (!event || l.event.includes(event)) &&
          (!episode || (l.episode ?? "").includes(episode)),
      ),
    [source, minLevel, event, episode],
  );

  useEffect(() => {
    if (!paused) bottom.current?.scrollIntoView({ block: "nearest" });
  }, [shown.length, paused]);

  return (
    <Card
      title="Logs"
      actions={
        <div className="flex items-center gap-2">
          <span className="text-text-muted text-xs">{shown.length} lines</span>
          <Button
            onClick={() => {
              setFrozen(logs);
              setPaused(!paused);
            }}
          >
            {paused ? "Resume" : "Pause"}
          </Button>
        </div>
      }
    >
      <div className="mb-3 grid grid-cols-1 gap-2 sm:grid-cols-3">
        <Field label="Minimum level">
          <select
            className={inputClass}
            value={minLevel}
            onChange={(e) => setMinLevel(e.target.value as typeof minLevel)}
          >
            {LEVELS.map((l) => (
              <option key={l}>{l}</option>
            ))}
          </select>
        </Field>
        <Field label="Event contains">
          <input
            className={inputClass}
            value={event}
            placeholder="task., bridge., capture."
            onChange={(e) => setEvent(e.target.value)}
          />
        </Field>
        <Field label="Episode contains">
          <input
            className={inputClass}
            value={episode}
            onChange={(e) => setEpisode(e.target.value)}
          />
        </Field>
      </div>
      <div className="border-border bg-sunken h-[60vh] overflow-auto rounded-md border font-mono text-xs">
        {shown.map((l, i) => {
          const key = `${l.ts}-${l.event}-${i}`;
          const fields = extraFields(l);
          return (
            <div
              key={key}
              onClick={() => setExpanded(expanded === key ? null : key)}
              className="border-border/60 hover:bg-card cursor-pointer border-b px-3 py-1.5"
            >
              <div className="flex items-center gap-3">
                <span className="text-text-dim">{time(l.ts)}</span>
                <Pill tone={LEVEL_TONE[l.level]}>{l.level}</Pill>
                <span className="text-text-muted">{l.source}</span>
                <span className="text-text-primary font-medium">{l.event}</span>
                <span className="text-text-muted truncate">{fields}</span>
              </div>
              {expanded === key && (
                <pre className="bg-card text-text-primary mt-2 overflow-auto rounded p-2 whitespace-pre-wrap">
                  {JSON.stringify(l, null, 2)}
                </pre>
              )}
            </div>
          );
        })}
        <div ref={bottom} />
      </div>
    </Card>
  );
}
