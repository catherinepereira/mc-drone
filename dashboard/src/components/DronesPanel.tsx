import type { DroneInfo } from "../protocol";
import { useBridge } from "../stores/bridge";
import { vec } from "../utils/format";
import { Card, Pill } from "./ui";

const TIER_TONE = {
  copper: "amber",
  iron: "neutral",
  diamond: "accent",
} as const;

/** Every loaded drone of the player's, with its charge, home station, and job queue in run order */
export function DronesPanel() {
  // select the status, a fresh empty array from the selector would re-render forever
  const status = useBridge((s) => s.status);
  const drones = status?.drones ?? [];

  return (
    <Card title="Drones" actions={<Pill>{drones.length} loaded</Pill>}>
      {drones.length === 0 ? (
        <p className="text-text-dim text-sm">
          No drones loaded. Place one from its item in game.
        </p>
      ) : (
        <ul className="divide-border divide-y">
          {drones.map((d) => (
            <DroneRow key={d.id} drone={d} />
          ))}
        </ul>
      )}
    </Card>
  );
}

function DroneRow({ drone }: { drone: DroneInfo }) {
  const percent = Math.round(drone.charge * 100);
  const low = drone.battery && drone.charge < 0.2;
  return (
    <li className="py-3 first:pt-0 last:pb-0">
      <div className="flex items-center gap-2">
        <span className="text-text-primary min-w-0 flex-1 truncate text-sm font-medium">
          {drone.name}
        </span>
        {drone.active && <Pill tone="green">active</Pill>}
        <Pill tone={TIER_TONE[drone.tier]}>{drone.tier}</Pill>
      </div>
      {drone.battery && (
        <div className="mt-2 flex items-center gap-2">
          <div className="bg-sunken h-1.5 flex-1 overflow-hidden rounded-full">
            <div
              className={`h-full rounded-full ${low ? "bg-red" : "bg-green"}`}
              style={{ width: `${percent}%` }}
            />
          </div>
          <span className="text-text-muted w-10 text-right font-mono text-xs">
            {percent}%
          </span>
        </div>
      )}
      <p className="text-text-muted mt-1 text-xs">
        {drone.home
          ? `${drone.docked ? "Docked at" : "Home"} ${vec(drone.home, 0)}`
          : "No charging station"}
        , at {vec(drone.pos, 0)}
      </p>
      {drone.queue.length === 0 ? (
        <p className="text-text-dim mt-1 text-xs">Queue empty</p>
      ) : (
        <ol className="mt-2 flex flex-col gap-1">
          {drone.queue.map((job, i) => (
            <li
              key={i}
              className="bg-sunken text-text-primary flex gap-2 rounded-md px-2 py-1 text-xs"
            >
              <span className="text-text-muted font-mono">{i + 1}</span>
              <span className="truncate">{job.label ?? job.task}</span>
            </li>
          ))}
        </ol>
      )}
    </li>
  );
}
