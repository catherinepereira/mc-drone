import { useMemo, useState } from "react";
import { blockColor, shortName } from "../blockColors";
import type { Memory } from "../protocol";
import { useBridge } from "../stores/bridge";
import { Card, Pill } from "./ui";

const MAX_SIDE = 32;
const CELL = 14;

/** The memory as it stood after step: the snapshot plus every change up to then */
function cellsAt(memory: Memory, step: number): Map<string, string> {
  const cells = new Map(Object.entries(memory.base));
  for (const c of memory.log) {
    if (c.step > step) break;
    const key = c.cell.join(",");
    if (c.after === "minecraft:air") cells.delete(key);
    else cells.set(key, c.after);
  }
  return cells;
}

/** The focus box, or the box around every remembered cell, capped so the grid stays readable */
function viewBox(memory: Memory, cells: Map<string, string>) {
  if (memory.focus) return memory.focus;
  const pts = [...cells.keys()].map((k) => k.split(",").map(Number));
  if (pts.length === 0) return null;
  const lo = [0, 1, 2].map((i) => Math.min(...pts.map((p) => p[i])));
  const hi = [0, 1, 2].map((i) =>
    Math.min(Math.max(...pts.map((p) => p[i])), lo[i] + MAX_SIDE - 1),
  );
  return [...lo, ...hi] as Memory["focus"];
}

/**
 * The drone's voxel memory while a policy works: what it believes is in each cell, one layer at a time, and the
 * changes it made to that belief step by step. Scrub back to see the memory as it was at any step
 */
export function MemoryPanel() {
  const memories = useBridge((s) => s.memories);
  const latestDrone = useBridge((s) => s.memoryDrone);
  const status = useBridge((s) => s.status);
  const [picked, setPicked] = useState<number | null>(null);
  const shownDrone = picked != null && memories[picked] ? picked : latestDrone;
  const memory = shownDrone != null ? memories[shownDrone] : undefined;
  const droneIds = Object.keys(memories).map(Number);
  const droneName = (id: number) =>
    status?.drones?.find((d) => d.id === id)?.name ?? `drone ${id}`;
  const [step, setStep] = useState<number | null>(null);
  const [layer, setLayer] = useState<number | null>(null);
  const shown = step ?? memory?.latestStep ?? 0;
  const cells = useMemo(
    () => (memory ? cellsAt(memory, shown) : new Map<string, string>()),
    [memory, shown],
  );

  if (!memory) {
    return (
      <Card title="Drone memory">
        <p className="text-text-dim text-sm">
          Shows up while a learned policy runs a job and sends its voxel memory.
        </p>
      </Card>
    );
  }

  const box = viewBox(memory, cells);
  const y = box ? Math.min(Math.max(layer ?? box[1], box[1]), box[4]) : 0;
  const recent = memory.log
    .filter((c) => c.step <= shown)
    .slice(-40)
    .reverse();
  const counts = new Map<string, number>();
  for (const label of cells.values())
    counts.set(label, (counts.get(label) ?? 0) + 1);

  return (
    <Card
      title="Drone memory"
      actions={
        <div className="flex items-center gap-2">
          {droneIds.length > 1 && (
            <select
              className="border-border bg-card rounded-sm border px-1 text-xs"
              value={shownDrone ?? undefined}
              onChange={(e) => {
                setPicked(Number(e.target.value));
                setStep(null);
              }}
            >
              {droneIds.map((id) => (
                <option key={id} value={id}>
                  {droneName(id)}
                </option>
              ))}
            </select>
          )}
          <Pill tone={step == null ? "accent" : "neutral"}>
            {step == null ? "live" : "replay"}, step {shown}
          </Pill>
        </div>
      }
    >
      <label className="text-text-muted flex items-center gap-2 text-xs">
        Step
        <input
          type="range"
          className="flex-1"
          min={memory.baseStep}
          max={memory.latestStep}
          value={shown}
          onChange={(e) => {
            const v = Number(e.target.value);
            setStep(v >= memory.latestStep ? null : v);
          }}
        />
      </label>
      {box && (
        <>
          <label className="text-text-muted mt-2 flex items-center gap-2 text-xs">
            Layer y {y}
            <input
              type="range"
              className="flex-1"
              min={box[1]}
              max={box[4]}
              value={y}
              onChange={(e) => setLayer(Number(e.target.value))}
            />
          </label>
          <svg
            className="bg-sunken mt-2 w-full rounded-md"
            viewBox={`0 0 ${(box[3] - box[0] + 1) * CELL} ${(box[5] - box[2] + 1) * CELL}`}
            role="img"
            aria-label={`Remembered blocks at height ${y}`}
          >
            {[...cells.entries()].map(([key, label]) => {
              const [x, cy, z] = key.split(",").map(Number);
              if (
                cy !== y ||
                x < box[0] ||
                x > box[3] ||
                z < box[2] ||
                z > box[5]
              )
                return null;
              return (
                <rect
                  key={key}
                  x={(x - box[0]) * CELL + 1}
                  y={(z - box[2]) * CELL + 1}
                  width={CELL - 2}
                  height={CELL - 2}
                  rx={2}
                  fill={blockColor(label)}
                >
                  <title>
                    {shortName(label)} at {key.replaceAll(",", " ")}
                  </title>
                </rect>
              );
            })}
          </svg>
        </>
      )}
      <div className="text-text-muted mt-2 flex flex-wrap gap-x-3 gap-y-1 text-xs">
        {[...counts.entries()]
          .sort((a, b) => b[1] - a[1])
          .slice(0, 8)
          .map(([label, n]) => (
            <span key={label} className="flex items-center gap-1.5">
              <span
                className="inline-block h-2.5 w-2.5 rounded-sm"
                style={{ background: blockColor(label) }}
              />
              {shortName(label)} {n}
            </span>
          ))}
      </div>
      <ul className="divide-border mt-3 max-h-56 divide-y overflow-y-auto text-xs">
        {recent.map((c, i) => (
          <li key={i} className="flex gap-2 py-1">
            <span className="text-text-dim w-12 shrink-0 font-mono">
              {c.step}
            </span>
            <span className="text-text-primary">
              {c.cause} {c.cell.join(" ")}:{" "}
              <span className="text-text-muted">{shortName(c.before)}</span> to{" "}
              {shortName(c.after)}
            </span>
          </li>
        ))}
        {recent.length === 0 && (
          <li className="text-text-dim py-1">No changes yet.</li>
        )}
      </ul>
    </Card>
  );
}
