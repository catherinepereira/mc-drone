import { useState } from "react";

interface Props {
  values: (number | null)[];
  labels: string[];
  unit?: string;
  height?: number;
  digits?: number;
  cursor?: number;
  onSelect?: (index: number) => void;
}

const W = 600;
const PAD_L = 40;
const PAD_R = 8;
const PAD_T = 8;
const PAD_B = 18;

/** Single-series line, one axis, crosshair tooltip on hover */
export function LineChart({
  values,
  labels,
  unit = "",
  height = 120,
  digits = 1,
  cursor,
  onSelect,
}: Props) {
  const [hover, setHover] = useState<number | null>(null);
  const H = height;
  const finite = values.filter(
    (v): v is number => v != null && Number.isFinite(v),
  );
  const lo = Math.min(0, ...finite);
  const hi = Math.max(lo + (digits === 0 ? 1 : 1e-3), ...finite);
  const n = values.length;
  const x = (i: number) =>
    PAD_L + (n <= 1 ? 0 : (i / (n - 1)) * (W - PAD_L - PAD_R));
  const y = (v: number) =>
    PAD_T + (1 - (v - lo) / (hi - lo)) * (H - PAD_T - PAD_B);

  let path = "";
  values.forEach((v, i) => {
    if (v == null || !Number.isFinite(v)) return;
    path += `${path && values[i - 1] != null ? "L" : "M"}${x(i).toFixed(1)},${y(v).toFixed(1)}`;
  });

  function onMove(e: React.MouseEvent<SVGSVGElement>) {
    if (n === 0) return;
    const rect = e.currentTarget.getBoundingClientRect();
    const px = ((e.clientX - rect.left) / rect.width) * W;
    const i = Math.round(((px - PAD_L) / (W - PAD_L - PAD_R)) * (n - 1));
    setHover(Math.max(0, Math.min(n - 1, i)));
  }

  const shown = hover ?? cursor ?? null;
  const shownValue = shown != null ? values[shown] : null;

  return (
    <div className="relative">
      <svg
        viewBox={`0 0 ${W} ${H}`}
        className="w-full cursor-crosshair select-none"
        onMouseMove={onMove}
        onMouseLeave={() => setHover(null)}
        onClick={() => hover != null && onSelect?.(hover)}
        role="img"
      >
        {[lo, (lo + hi) / 2, hi].map((t) => (
          <g key={t}>
            <line
              x1={PAD_L}
              x2={W - PAD_R}
              y1={y(t)}
              y2={y(t)}
              stroke="var(--color-border)"
              strokeWidth={1}
            />
            <text
              x={PAD_L - 6}
              y={y(t) + 3}
              textAnchor="end"
              className="fill-text-dim font-mono text-[10px]"
            >
              {t.toFixed(digits)}
            </text>
          </g>
        ))}
        <path
          d={path}
          fill="none"
          stroke="var(--color-accent)"
          strokeWidth={2}
          strokeLinejoin="round"
          strokeLinecap="round"
        />
        {shown != null && (
          <g>
            <line
              x1={x(shown)}
              x2={x(shown)}
              y1={PAD_T}
              y2={H - PAD_B}
              stroke="var(--color-text-dim)"
              strokeWidth={1}
              strokeDasharray="3 3"
            />
            {shownValue != null && (
              <circle
                cx={x(shown)}
                cy={y(shownValue)}
                r={4}
                fill="var(--color-accent)"
                stroke="var(--color-card)"
                strokeWidth={2}
              />
            )}
          </g>
        )}
        {n > 0 && (
          <>
            <text
              x={PAD_L}
              y={H - 4}
              className="fill-text-dim font-mono text-[10px]"
            >
              {labels[0]}
            </text>
            <text
              x={W - PAD_R}
              y={H - 4}
              textAnchor="end"
              className="fill-text-dim font-mono text-[10px]"
            >
              {labels[n - 1]}
            </text>
          </>
        )}
      </svg>
      {hover != null && (
        <div className="border-border bg-card pointer-events-none absolute top-1 right-2 rounded-md border px-2 py-1 font-mono text-xs shadow-sm">
          <span className="text-text-muted">{labels[hover]}</span>{" "}
          <span className="text-text-primary">
            {shownValue == null ? "-" : shownValue.toFixed(digits)}
            {unit}
          </span>
        </div>
      )}
    </div>
  );
}
