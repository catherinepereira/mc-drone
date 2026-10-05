import type { DroneState, Vec3 } from "../protocol";
import { BLOCK_COLORS } from "../blockColors";
import { Card } from "./ui";

const SIZE = 240;

const LEGEND: { label: string; className: string }[] = [
  { label: "drone", className: "bg-accent rounded-full" },
  { label: "goal", className: "bg-marker rounded-sm" },
  { label: "chest", className: "bg-amber rounded-sm" },
  { label: "pillar or tree", className: "bg-text-muted rounded-sm" },
  {
    label: "stalactite",
    className: "border-2 border-text-muted rounded-full",
  },
];

/** The reference build one layer at a time, ground layer first */
function BlueprintLayers({
  cells,
}: {
  cells: { offset: Vec3; block: string }[];
}) {
  const at = (dx: number, dy: number, dz: number) =>
    cells.find(
      (c) => c.offset[0] === dx && c.offset[1] === dy && c.offset[2] === dz,
    )?.block;
  return (
    <div className="mt-3 flex gap-3">
      {[1, 2, 3].map((dy) => (
        <div key={dy}>
          <div className="text-text-muted mb-1 text-xs">layer {dy}</div>
          <div className="grid grid-cols-3 gap-0.5">
            {[-1, 0, 1].flatMap((dz) =>
              [-1, 0, 1].map((dx) => {
                const block = at(dx, dy, dz);
                return (
                  <div
                    key={`${dx},${dz}`}
                    title={block ? block.replace("minecraft:", "") : "empty"}
                    className="border-border h-5 w-5 rounded-sm border"
                    style={{
                      background: block
                        ? (BLOCK_COLORS[block] ?? "var(--color-text-muted)")
                        : "var(--color-sunken)",
                    }}
                  />
                );
              }),
            )}
          </div>
        </div>
      ))}
    </div>
  );
}

/**
 * Top-down view of the arena for debugging. It draws the true layout, which the drone itself never reads,
 * plus the geofence the drone does know
 */
export function ArenaMap({ state }: { state: DroneState | undefined }) {
  const arena = state?.arena;
  if (!arena || !state?.pos) {
    return (
      <Card title="Arena">
        <p className="text-text-dim text-sm">
          Starts drawing once an episode is running.
        </p>
      </Card>
    );
  }
  const [ox, , oz] = arena.origin;
  const bounds = state.bounds;
  const half = Math.max(
    arena.radius + 1,
    bounds ? (bounds[3] - bounds[0]) / 2 + 0.5 : 0,
  );
  const scale = SIZE / (half * 2);
  // Minecraft x grows east and z grows south, which maps straight onto screen x and y
  const sx = (x: number) => (x - (ox + 0.5 - half)) * scale;
  const sz = (z: number) => (z - (oz + 0.5 - half)) * scale;
  const [px, , pz] = state.pos;
  const yaw = ((state.yaw ?? 0) * Math.PI) / 180;
  const hx = -Math.sin(yaw);
  const hz = Math.cos(yaw);
  const cell = (
    p: Vec3,
    fill: string,
    key: string,
    title: string,
    inset = 0,
  ) => (
    <rect
      key={key}
      x={sx(p[0]) + inset}
      y={sz(p[2]) + inset}
      width={scale - inset * 2}
      height={scale - inset * 2}
      rx={2}
      fill={fill}
    >
      <title>{title}</title>
    </rect>
  );
  const goals: Vec3[] = [
    ...(arena.marker ? [arena.marker] : []),
    ...(arena.targets ?? []),
    ...(arena.goal ? [arena.goal] : []),
  ];

  return (
    <Card
      title="Arena"
      actions={
        <span className="text-text-muted text-xs">
          {arena.terrain ?? "flat"}, {arena.obstacles.length} pillars
          {arena.trees?.length ? `, ${arena.trees.length} trees` : ""}
          {arena.stalactites?.length
            ? `, ${arena.stalactites.length} stalactites`
            : ""}
        </span>
      }
    >
      <svg
        viewBox={`0 0 ${SIZE} ${SIZE}`}
        className="bg-sunken w-full rounded-md"
        role="img"
        aria-label="Top-down arena map"
      >
        <rect
          x={sx(ox - arena.radius)}
          y={sz(oz - arena.radius)}
          width={(arena.radius * 2 + 1) * scale}
          height={(arena.radius * 2 + 1) * scale}
          fill="var(--color-card)"
          stroke="var(--color-border)"
        />
        {bounds && (
          <rect
            x={sx(bounds[0])}
            y={sz(bounds[2])}
            width={(bounds[3] - bounds[0]) * scale}
            height={(bounds[5] - bounds[2]) * scale}
            fill="none"
            stroke="var(--color-red)"
            strokeDasharray="4 3"
          >
            <title>geofence, leaving it ends the episode</title>
          </rect>
        )}
        {arena.obstacles.map(([x, z, w, h]) => (
          <rect
            key={`p${x},${z}`}
            x={sx(x)}
            y={sz(z)}
            width={w * scale}
            height={w * scale}
            rx={1}
            fill="var(--color-text-muted)"
          >
            <title>
              pillar at {x}, {z}, {h} tall
            </title>
          </rect>
        ))}
        {arena.trees?.map(([x, z]) => (
          <circle
            key={`t${x},${z}`}
            cx={sx(x + 0.5)}
            cy={sz(z + 0.5)}
            r={scale * 2}
            fill="var(--color-text-muted)"
            opacity={0.35}
          >
            <title>
              tree at {x}, {z}
            </title>
          </circle>
        ))}
        {arena.stalactites?.map(([x, z, bottom]) => (
          <circle
            key={`s${x},${z}`}
            cx={sx(x + 0.5)}
            cy={sz(z + 0.5)}
            r={scale * 0.45}
            fill="none"
            stroke="var(--color-text-muted)"
            strokeWidth={2}
          >
            <title>
              stalactite at {x}, {z}, down to y {bottom}
            </title>
          </circle>
        ))}
        {arena.distractors?.map((p) =>
          cell(p, "var(--color-text-dim)", `d${p.join()}`, "stone pedestal"),
        )}
        {[arena.sourceChest, arena.targetChest].map(
          (p, i) =>
            p &&
            cell(
              p,
              "var(--color-amber)",
              `c${i}`,
              i === 0 ? "source chest" : "target chest",
            ),
        )}
        {arena.referenceBase && (
          <rect
            x={sx(arena.referenceBase[0] - 1)}
            y={sz(arena.referenceBase[2] - 1)}
            width={scale * 3}
            height={scale * 3}
            rx={2}
            fill="var(--color-text-dim)"
            opacity={0.6}
          >
            <title>
              reference build, {arena.blueprint?.length ?? 0} blocks
            </title>
          </rect>
        )}
        {arena.buildBase && (
          <rect
            x={sx(arena.buildBase[0] - 1)}
            y={sz(arena.buildBase[2] - 1)}
            width={scale * 3}
            height={scale * 3}
            rx={2}
            fill="none"
            stroke="var(--color-marker)"
            strokeWidth={2}
            strokeDasharray="3 2"
          >
            <title>build site</title>
          </rect>
        )}
        {goals.map((p) =>
          cell(
            p,
            "var(--color-marker)",
            `g${p.join()}`,
            `goal at ${p.join(", ")}`,
            1,
          ),
        )}
        <line
          x1={sx(px)}
          y1={sz(pz)}
          x2={sx(px + hx * 2.5)}
          y2={sz(pz + hz * 2.5)}
          stroke="var(--color-accent)"
          strokeWidth={2}
          strokeLinecap="round"
        />
        <circle
          cx={sx(px)}
          cy={sz(pz)}
          r={5}
          fill="var(--color-accent)"
          stroke="var(--color-card)"
          strokeWidth={2}
        />
      </svg>
      {arena.blueprint && <BlueprintLayers cells={arena.blueprint} />}
      <div className="text-text-muted mt-2 flex flex-wrap gap-x-4 gap-y-1 text-xs">
        {LEGEND.map((l) => (
          <span key={l.label} className="flex items-center gap-1.5">
            <span className={`inline-block h-2.5 w-2.5 ${l.className}`} />
            {l.label}
          </span>
        ))}
        <span className="flex items-center gap-1.5">
          <span className="border-red inline-block h-2.5 w-3 border border-dashed" />
          geofence
        </span>
      </div>
    </Card>
  );
}
