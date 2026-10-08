import { useState } from "react";
import type { Vec3 } from "../protocol";
import { useBridge } from "../stores/bridge";
import { Button, Card, Field, inputClass, Pill } from "./ui";

type Point = "cornerA" | "cornerB" | "dest";
type JobKind = "mine" | "harvest" | "patrol" | "guard" | "seek";

// what each job in the region row takes in its text field, patrols take rounds and may leave it blank for one
const JOB_FIELD: Record<
  JobKind,
  { label: string; placeholder: string; task: string }
> = {
  mine: {
    label: "Blocks",
    placeholder: "coal_ore, iron_ore",
    task: "mine_region",
  },
  harvest: { label: "Crop", placeholder: "wheat", task: "harvest_region" },
  patrol: { label: "Rounds", placeholder: "1", task: "patrol_region" },
  guard: { label: "Rounds", placeholder: "1", task: "guard_region" },
  seek: { label: "Block", placeholder: "bricks", task: "seek_block" },
};

function jobOptions(kind: JobKind, text: string): Record<string, string> {
  const value = text.trim();
  if (kind === "mine") {
    // the mod reads a list of names, without a namespace they're minecraft's
    return { blocks: value };
  }
  if (kind === "harvest") {
    return { crop: value.includes(":") ? value : `minecraft:${value}` };
  }
  if (kind === "seek") {
    return { block: value };
  }
  return value ? { rounds: value } : {};
}

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
 * The copy selection and job controls. Corners come from the tablet in game or are typed here,
 * either way they are the same selection. Copies and builds can paste at a saved region instead of the paste point,
 * and mine their materials from another saved region first
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
  const [mineKind, setMineKind] = useState<JobKind>("mine");
  const [destRegion, setDestRegion] = useState("");
  const [gatherRegion, setGatherRegion] = useState("");
  const [busy, setBusy] = useState(false);
  const selection = status?.selection;
  const regions = status?.regions ?? [];
  const controller = role === "controller";

  const hasDest = !!destRegion || !!selection?.dest;
  // where a copy or build pastes and where it mines its materials, when not the paste point and its own blocks
  const placement = {
    ...(destRegion ? { dest: destRegion } : {}),
    ...(gatherRegion ? { gather: gatherRegion } : {}),
  };

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
            // remount when the tablet moves a corner so the field shows it
            key={`${p.key}:${selection?.[p.key]?.join() ?? ""}`}
            label={p.label}
            value={selection?.[p.key] ?? null}
            onCommit={(v) => select({ [p.key]: v })}
          />
        ))}
      </div>
      <p className="text-text-muted mt-2 text-xs">
        {!selection?.size
          ? (selection?.problem ?? "Select the source corners.")
          : !hasDest
            ? "Set the paste point or pick a saved region to paste at."
            : "Ready. The copy keeps the source's orientation, its lowest corner lands on the paste point."}
      </p>
      <div className="mt-3 grid grid-cols-1 gap-3 sm:grid-cols-2">
        <Field label="Paste at">
          <select
            className={inputClass}
            value={destRegion}
            onChange={(e) => setDestRegion(e.target.value)}
          >
            <option value="">the paste point</option>
            {regions.map((r) => (
              <option key={r.id} value={r.name}>
                {r.name} ({r.purpose}), its low corner
              </option>
            ))}
          </select>
        </Field>
        <Field label="Materials">
          <select
            className={inputClass}
            value={gatherRegion}
            onChange={(e) => setGatherRegion(e.target.value)}
          >
            <option value="">carried by the drone</option>
            {regions.map((r) => (
              <option key={r.id} value={r.name}>
                mined from {r.name} ({r.purpose})
              </option>
            ))}
          </select>
        </Field>
      </div>
      <div className="mt-4 flex flex-wrap items-end gap-2">
        <Button
          variant="primary"
          disabled={!controller || busy || !selection?.size || !hasDest}
          onClick={() => start({ task: "copy_region", ...placement })}
        >
          Copy source
        </Button>
        <Button
          disabled={!controller || busy || !selection?.dest}
          onClick={() => start({ task: "fly_to" })}
          title="Fly to the paste point, around whatever is in the way"
        >
          Fly to paste point
        </Button>
        <Button
          disabled={!controller || busy}
          onClick={() => start({ task: "follow_player" })}
          title="Keep 2 to 5 blocks from you until stopped"
        >
          Follow me
        </Button>
      </div>
      <div className="mt-4 flex flex-col gap-3">
        <div className="flex items-end gap-2">
          <Field label="Job">
            <select
              className={inputClass}
              value={mineKind}
              onChange={(e) => setMineKind(e.target.value as JobKind)}
            >
              <option value="mine">Mine</option>
              <option value="harvest">Harvest</option>
              <option value="patrol">Patrol</option>
              <option value="guard">Guard from hostile mobs</option>
              <option value="seek">Find a block</option>
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
            <Field label={JOB_FIELD[mineKind].label}>
              <input
                className={inputClass}
                placeholder={JOB_FIELD[mineKind].placeholder}
                value={mineBlock}
                onChange={(e) => setMineBlock(e.target.value)}
              />
            </Field>
          </div>
          <Button
            disabled={
              !controller ||
              busy ||
              (!mineBlock.trim() &&
                mineKind !== "patrol" &&
                mineKind !== "guard") ||
              (!mineRegion && !selection?.size)
            }
            onClick={() =>
              start({
                task: JOB_FIELD[mineKind].task,
                ...jobOptions(mineKind, mineBlock),
                ...(mineRegion ? { region: mineRegion } : {}),
              })
            }
          >
            Start
          </Button>
        </div>
        <div className="flex items-end gap-2">
          <div className="flex-1">
            <Field label="Build schematic">
              <input
                className={inputClass}
                placeholder="house.schem"
                value={schematic}
                onChange={(e) => setSchematic(e.target.value)}
              />
            </Field>
          </div>
          <Button
            disabled={!controller || busy || !schematic || !hasDest}
            onClick={() =>
              start({
                task: "build_schematic",
                schematic: schematic.endsWith(".schem")
                  ? schematic
                  : `${schematic}.schem`,
                ...placement,
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
