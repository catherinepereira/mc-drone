import { useState } from "react";
import type { Vec3 } from "../protocol";
import { useBridge } from "../stores/bridge";
import { Button, Card, Field, inputClass, Pill } from "./ui";

type Point = "cornerA" | "cornerB" | "dest";

const POINTS: { key: Point; label: string }[] = [
  { key: "cornerA", label: "Source corner 1" },
  { key: "cornerB", label: "Source corner 2" },
  { key: "dest", label: "Paste point" },
];

function PointInput({
  label,
  value,
  onCommit,
}: {
  label: string;
  value: Vec3 | null;
  onCommit: (v: Vec3 | null) => void;
}) {
  const [draft, setDraft] = useState(value ? value.join(" ") : "");
  const commit = () => {
    const parts = draft
      .trim()
      .split(/[\s,]+/)
      .filter(Boolean)
      .map(Number);
    if (parts.length === 0) onCommit(null);
    else if (parts.length === 3 && parts.every(Number.isInteger))
      onCommit(parts as Vec3);
    else setDraft(value ? value.join(" ") : "");
  };
  return (
    <Field label={label}>
      <input
        className={`${inputClass} font-mono`}
        placeholder="x y z"
        value={draft}
        onChange={(e) => setDraft(e.target.value)}
        onBlur={commit}
        onKeyDown={(e) => e.key === "Enter" && commit()}
      />
    </Field>
  );
}

/**
 * The copy selection and job controls. Corners come from the drone remote in game or are typed here,
 * either way they are the same selection
 */
export function JobsPanel() {
  const status = useBridge((s) => s.status);
  const role = useBridge((s) => s.role);
  const select = useBridge((s) => s.select);
  const reset = useBridge((s) => s.reset);
  const exportSchematic = useBridge((s) => s.exportSchematic);
  const [schematic, setSchematic] = useState("");
  const [exportName, setExportName] = useState("");
  const [mineRegion, setMineRegion] = useState("");
  const [mineBlock, setMineBlock] = useState("");
  const [mineKind, setMineKind] = useState<"mine" | "harvest">("mine");
  const [busy, setBusy] = useState(false);
  const selection = status?.selection;
  const regions = status?.regions ?? [];
  const controller = role === "controller";

  const start = async (options: Record<string, unknown>) => {
    setBusy(true);
    try {
      await reset(null, options);
    } catch {
      // the bridge error shows in the error banner
    } finally {
      setBusy(false);
    }
  };

  return (
    <Card
      title="Jobs"
      actions={
        selection?.size ? (
          <Pill tone="accent">source {selection.size.join(" x ")}</Pill>
        ) : null
      }
    >
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
        {POINTS.map((p) => (
          <PointInput
            // remount when the remote moves a corner so the field shows it
            key={`${p.key}:${selection?.[p.key]?.join() ?? ""}`}
            label={p.label}
            value={selection?.[p.key] ?? null}
            onCommit={(v) => select({ [p.key]: v })}
          />
        ))}
      </div>
      <p className="text-text-muted mt-2 text-xs">
        {selection?.problem ??
          "Ready. The copy keeps the source's orientation, its lowest corner lands on the paste point."}
      </p>
      <div className="mt-4 flex flex-wrap items-end gap-2">
        <Button
          variant="primary"
          disabled={!controller || busy || !!selection?.problem}
          onClick={() => start({ task: "copy_region" })}
        >
          Copy source to paste point
        </Button>
      </div>
      <div className="mt-4 flex flex-col gap-3">
        <div className="flex items-end gap-2">
          <Field label="Job">
            <select
              className={inputClass}
              value={mineKind}
              onChange={(e) =>
                setMineKind(e.target.value as "mine" | "harvest")
              }
            >
              <option value="mine">Mine</option>
              <option value="harvest">Harvest</option>
            </select>
          </Field>
          <Field label="In">
            <select
              className={inputClass}
              value={mineRegion}
              onChange={(e) => setMineRegion(e.target.value)}
            >
              <option value="">the selection</option>
              {regions.map((r) => (
                <option key={r.id} value={r.name}>
                  {r.name}
                </option>
              ))}
            </select>
          </Field>
          <div className="flex-1">
            <Field label={mineKind === "mine" ? "Block" : "Crop"}>
              <input
                className={inputClass}
                placeholder={mineKind === "mine" ? "coal_ore" : "wheat"}
                value={mineBlock}
                onChange={(e) => setMineBlock(e.target.value)}
              />
            </Field>
          </div>
          <Button
            disabled={
              !controller ||
              busy ||
              !mineBlock.trim() ||
              (!mineRegion && !selection?.size)
            }
            onClick={() =>
              start({
                task: mineKind === "mine" ? "mine_region" : "harvest_region",
                [mineKind === "mine" ? "block" : "crop"]: mineBlock.includes(
                  ":",
                )
                  ? mineBlock.trim()
                  : `minecraft:${mineBlock.trim()}`,
                ...(mineRegion ? { region: mineRegion } : {}),
              })
            }
          >
            Start
          </Button>
        </div>
        <div className="flex items-end gap-2">
          <div className="flex-1">
            <Field label="Build schematic at paste point">
              <input
                className={inputClass}
                placeholder="house.schem"
                value={schematic}
                onChange={(e) => setSchematic(e.target.value)}
              />
            </Field>
          </div>
          <Button
            disabled={!controller || busy || !schematic || !selection?.dest}
            onClick={() =>
              start({
                task: "build_schematic",
                schematic: schematic.endsWith(".schem")
                  ? schematic
                  : `${schematic}.schem`,
              })
            }
          >
            Build
          </Button>
        </div>
        <div className="flex items-end gap-2">
          <div className="flex-1">
            <Field label="Save source as schematic">
              <input
                className={inputClass}
                placeholder="my-build"
                value={exportName}
                onChange={(e) => setExportName(e.target.value)}
              />
            </Field>
          </div>
          <Button
            disabled={!exportName || !selection?.size}
            onClick={() => exportSchematic(exportName)}
          >
            Save
          </Button>
        </div>
      </div>
      {!controller && (
        <p className="text-text-dim mt-3 text-xs">
          Take control to start jobs. Editing the selection and saving
          schematics work as an observer.
        </p>
      )}
    </Card>
  );
}
