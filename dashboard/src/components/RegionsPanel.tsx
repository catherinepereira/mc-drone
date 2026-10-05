import { useState } from "react";
import type { RegionPurpose } from "../protocol";
import { useBridge } from "../stores/bridge";
import { Button, Card, Field, inputClass, Pill } from "./ui";

const PURPOSES: { value: RegionPurpose; label: string; hint: string }[] = [
  {
    value: "general",
    label: "General",
    hint: "any named box, such as a copy source",
  },
  { value: "safe", label: "Safe", hint: "drones never break or build here" },
  { value: "mine", label: "Mine", hint: "where mining jobs dig" },
  { value: "farm", label: "Farm", hint: "where harvest jobs work" },
];

const TONE = {
  general: "neutral",
  safe: "green",
  mine: "amber",
  farm: "accent",
} as const;

/** The world's named regions. New ones come from the current selection's source corners */
export function RegionsPanel() {
  const status = useBridge((s) => s.status);
  const saveRegion = useBridge((s) => s.saveRegion);
  const deleteRegion = useBridge((s) => s.deleteRegion);
  const selectRegion = useBridge((s) => s.selectRegion);
  const [name, setName] = useState("");
  const [purpose, setPurpose] = useState<RegionPurpose>("general");
  const regions = status?.regions ?? [];
  const hasSelection = !!status?.selection?.size;

  return (
    <Card title="Regions" actions={<Pill>{regions.length} saved</Pill>}>
      {regions.length === 0 ? (
        <p className="text-text-dim text-sm">
          No regions yet. Select two corners with the drone remote or in Jobs,
          then save them here.
        </p>
      ) : (
        <ul className="divide-border divide-y">
          {regions.map((r) => {
            const size = [0, 1, 2].map((i) => r.box[i + 3] - r.box[i] + 1);
            return (
              <li key={r.id} className="flex items-center gap-2 py-2">
                <Pill tone={TONE[r.purpose]}>{r.purpose}</Pill>
                <span className="text-text-primary min-w-0 flex-1 truncate text-sm">
                  {r.name}
                </span>
                <span className="text-text-muted font-mono text-xs">
                  {size.join(" x ")}
                </span>
                <Button
                  onClick={() => selectRegion(r.id)}
                  title="Load into the selection"
                >
                  Select
                </Button>
                <Button variant="danger" onClick={() => deleteRegion(r.id)}>
                  Delete
                </Button>
              </li>
            );
          })}
        </ul>
      )}
      <div className="mt-3 flex items-end gap-2">
        <div className="flex-1">
          <Field label="Save the selection as">
            <input
              className={inputClass}
              placeholder="north farm"
              value={name}
              onChange={(e) => setName(e.target.value)}
            />
          </Field>
        </div>
        <Field label="Purpose">
          <select
            className={inputClass}
            value={purpose}
            onChange={(e) => setPurpose(e.target.value as RegionPurpose)}
            title={PURPOSES.find((p) => p.value === purpose)?.hint}
          >
            {PURPOSES.map((p) => (
              <option key={p.value} value={p.value}>
                {p.label}
              </option>
            ))}
          </select>
        </Field>
        <Button
          disabled={!name.trim() || !hasSelection}
          onClick={() => {
            saveRegion(name.trim(), purpose);
            setName("");
          }}
        >
          Save
        </Button>
      </div>
      <p className="text-text-muted mt-2 text-xs">
        {PURPOSES.find((p) => p.value === purpose)?.hint}. Holding the remote
        shows every region in game.
      </p>
    </Card>
  );
}
