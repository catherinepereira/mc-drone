import { useEffect, useState } from "react";
import { api } from "../api";
import { Button, Card, Field, inputClass } from "./ui";

const NUMBER_FIELDS: { key: string; label: string; step?: number }[] = [
  { key: "width", label: "Frame width" },
  { key: "height", label: "Frame height" },
  { key: "streamHz", label: "Observer stream rate (Hz)" },
  { key: "depthMax", label: "Depth max (blocks)" },
  { key: "maxSpeed", label: "Max speed (blocks per tick)", step: 0.05 },
  { key: "maxVerticalSpeed", label: "Max vertical speed", step: 0.05 },
  { key: "smoothing", label: "Velocity smoothing", step: 0.05 },
  { key: "radius", label: "Arena radius" },
  { key: "obstacles", label: "Obstacles" },
  { key: "maxSteps", label: "Max steps per episode" },
  { key: "successDist", label: "Success distance", step: 0.1 },
  { key: "collisionPenalty", label: "Collision penalty per tick", step: 0.01 },
  { key: "resetSettleTicks", label: "Reset settle ticks" },
  { key: "boundsPadding", label: "Geofence padding (blocks)" },
  { key: "outOfBoundsPenalty", label: "Out of bounds penalty", step: 0.5 },
  { key: "actionPauseMs", label: "Pause after tool actions (ms)", step: 50 },
  { key: "logRetentionDays", label: "Keep session logs (days, 0 for all)" },
];
const STREAMS = ["rgb", "depth", "mask"];
const LEVELS = ["debug", "info", "warn", "error"];

export function ConfigView() {
  const [config, setConfig] = useState<Record<string, unknown> | null>(null);
  const [draft, setDraft] = useState<Record<string, unknown>>({});
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api
      .config()
      .then(setConfig)
      .catch((e) => setError(String(e.message ?? e)));
  }, []);

  if (!config)
    return (
      <Card>
        {error ? (
          <p className="text-red text-sm">{error}</p>
        ) : (
          <p className="text-text-dim text-sm">Loading config.</p>
        )}
      </Card>
    );

  const value = (key: string) => (key in draft ? draft[key] : config[key]);
  const streams = value("streams") as string[];
  const set = (key: string, v: unknown) => {
    setDraft((d) => ({ ...d, [key]: v }));
    setSaved(false);
  };

  async function save() {
    try {
      setConfig(await api.saveConfig(draft));
      setDraft({});
      setSaved(true);
      setError(null);
    } catch (e) {
      setError(String((e as Error).message ?? e));
    }
  }

  return (
    <Card
      title="Mod config"
      actions={
        <div className="flex items-center gap-3">
          {saved && <span className="text-green text-sm">Saved</span>}
          <Button
            variant="primary"
            disabled={Object.keys(draft).length === 0}
            onClick={save}
          >
            Save
          </Button>
        </div>
      }
    >
      {error && <p className="text-red mb-3 text-sm">{error}</p>}
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        {NUMBER_FIELDS.map((f) => (
          <Field key={f.key} label={f.label}>
            <input
              className={inputClass}
              type="number"
              step={f.step ?? 1}
              value={String(value(f.key) ?? "")}
              onChange={(e) => set(f.key, Number(e.target.value))}
            />
          </Field>
        ))}
        <Field label="Streams">
          <div className="flex gap-3 py-1.5">
            {STREAMS.map((s) => (
              <label
                key={s}
                className="text-text-primary flex items-center gap-1.5 text-sm"
              >
                <input
                  type="checkbox"
                  checked={streams.includes(s)}
                  onChange={(e) =>
                    set(
                      "streams",
                      e.target.checked
                        ? [...streams, s]
                        : streams.filter((x) => x !== s),
                    )
                  }
                />
                {s}
              </label>
            ))}
          </div>
        </Field>
        <Field label="Building materials">
          <select
            className={inputClass}
            value={String(value("materials"))}
            onChange={(e) => set("materials", e.target.value)}
          >
            <option value="inventory">From the drone inventory</option>
            <option value="unlimited">Unlimited</option>
          </select>
        </Field>
        <Field label="Block perception">
          <select
            className={inputClass}
            value={String(value("perception") ?? "vision")}
            onChange={(e) => set("perception", e.target.value)}
          >
            <option value="vision">Vision, read blocks with the camera</option>
            <option value="scan">Scan, read job boxes from the world</option>
          </select>
        </Field>
        <Field label="Log level">
          <select
            className={inputClass}
            value={String(value("logLevel"))}
            onChange={(e) => set("logLevel", e.target.value)}
          >
            {LEVELS.map((l) => (
              <option key={l}>{l}</option>
            ))}
          </select>
        </Field>
        <Field label="Muted events (comma separated)">
          <input
            className={inputClass}
            value={(value("mutedEvents") as string[]).join(", ")}
            onChange={(e) =>
              set(
                "mutedEvents",
                e.target.value
                  .split(",")
                  .map((s) => s.trim())
                  .filter(Boolean),
              )
            }
          />
        </Field>
      </div>
      <div className="bg-sunken text-text-muted mt-4 flex items-center justify-between gap-4 rounded-md px-3 py-2 text-xs">
        <span>
          Arena origin:{" "}
          {config.arenaX == null
            ? "unset, the next reset builds under the player"
            : `${config.arenaX}, ${config.arenaZ}`}
        </span>
        <Button
          disabled={config.arenaX == null}
          onClick={async () =>
            setConfig(await api.saveConfig({ arenaX: null, arenaZ: null }))
          }
        >
          Clear origin
        </Button>
      </div>
    </Card>
  );
}
