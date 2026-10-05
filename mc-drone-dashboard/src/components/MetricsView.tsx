import { useBridge, type MetricPoint } from "../stores/bridge";
import { LineChart } from "./LineChart";
import { Card } from "./ui";

const SERIES: {
  key: keyof MetricPoint;
  title: string;
  unit: string;
  digits: number;
}[] = [
  { key: "sps", title: "Simulation steps per second", unit: "", digits: 0 },
  { key: "fps", title: "Frames per second", unit: "", digits: 0 },
  { key: "captureMs", title: "Capture time", unit: " ms", digits: 1 },
  { key: "raycastMs", title: "Depth and mask raycast", unit: " ms", digits: 1 },
  { key: "encodeMs", title: "Frame encode", unit: " ms", digits: 2 },
  { key: "latencyMs", title: "Bridge round trip", unit: " ms", digits: 1 },
  {
    key: "droppedFrames",
    title: "Dropped frames per second",
    unit: "",
    digits: 0,
  },
  { key: "queueDepth", title: "Recorder write queue", unit: "", digits: 0 },
];

export function MetricsView() {
  const metrics = useBridge((s) => s.metrics);
  const labels = metrics.map((m) =>
    new Date(m.t).toLocaleTimeString([], { hour12: false }),
  );

  if (metrics.length === 0) {
    return (
      <Card>
        <p className="text-text-dim text-sm">
          Metrics arrive once a second while the game is running.
        </p>
      </Card>
    );
  }

  const last = metrics[metrics.length - 1];
  return (
    <div className="grid gap-4 md:grid-cols-2">
      {SERIES.map((s) => {
        const current = last[s.key] as number | null;
        return (
          <Card
            key={s.key}
            title={s.title}
            actions={
              <span className="text-text-primary font-mono text-sm">
                {current == null ? "-" : current.toFixed(s.digits)}
                <span className="text-text-muted">{s.unit}</span>
              </span>
            }
          >
            <LineChart
              values={metrics.map((m) => m[s.key] as number | null)}
              labels={labels}
              unit={s.unit}
              digits={s.digits}
            />
          </Card>
        );
      })}
    </div>
  );
}
