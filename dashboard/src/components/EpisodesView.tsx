import { useCallback, useEffect, useRef, useState } from "react";
import { api, type EpisodeMeta, type StepRow } from "../api";
import type { Observation } from "../protocol";
import { useBridge } from "../stores/bridge";
import { fixed, shortId, vec } from "../utils/format";
import { ConfirmModal } from "./ConfirmModal";
import { LineChart } from "./LineChart";
import { StreamView } from "./StreamView";
import { Button, Card, Pill, Row } from "./ui";

function Outcome({ value }: { value?: string }) {
  if (value === "success") return <Pill tone="green">success</Pill>;
  if (value === "timeout") return <Pill tone="amber">timeout</Pill>;
  if (!value) return <Pill tone="accent">recording</Pill>;
  return <Pill>{value}</Pill>;
}

export function EpisodesView() {
  const maskIds = useBridge((s) => s.maskIds);
  const [episodes, setEpisodes] = useState<EpisodeMeta[]>([]);
  const [selected, setSelected] = useState<EpisodeMeta | null>(null);
  const [steps, setSteps] = useState<StepRow[]>([]);
  const [frameIndex, setFrameIndex] = useState(0);
  const [frame, setFrame] = useState<Observation | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const resolver = useRef<((ok: boolean) => void) | null>(null);

  const refresh = useCallback(() => {
    api
      .episodes()
      .then(setEpisodes)
      .catch((e) => setError(String(e.message ?? e)));
  }, []);

  useEffect(refresh, [refresh]);

  useEffect(() => {
    if (!selected) return;
    api
      .episode(selected.task, selected.id)
      .then((d) => {
        setSteps(d.steps);
        setFrameIndex(0);
      })
      .catch((e) => setError(String(e.message ?? e)));
  }, [selected]);

  useEffect(() => {
    if (!selected || steps.length === 0) return;
    let cancelled = false;
    api
      .frame(selected.task, selected.id, frameIndex)
      .then((f) => !cancelled && setFrame(f))
      .catch((e) => setError(String(e.message ?? e)));
    return () => {
      cancelled = true;
    };
  }, [selected, steps.length, frameIndex]);

  const askConfirm = () =>
    new Promise<boolean>((resolve) => {
      resolver.current = resolve;
      setConfirmOpen(true);
    });

  const resolveConfirm = useCallback((ok: boolean) => {
    resolver.current?.(ok);
    resolver.current = null;
    setConfirmOpen(false);
  }, []);

  async function remove() {
    if (!selected || !(await askConfirm())) return;
    await api
      .deleteEpisode(selected.task, selected.id)
      .catch((e) => setError(String(e.message ?? e)));
    setSelected(null);
    setSteps([]);
    setFrame(null);
    refresh();
  }

  const row = steps[frameIndex];

  return (
    <div className="grid gap-4 xl:grid-cols-[420px_minmax(0,1fr)]">
      <Card
        title="Recorded episodes"
        actions={<Button onClick={refresh}>Refresh</Button>}
      >
        {error && <p className="text-red mb-2 text-sm">{error}</p>}
        {episodes.length === 0 ? (
          <p className="text-text-dim text-sm">
            Nothing recorded yet. Arm recording from Control or press B in game.
          </p>
        ) : (
          <div className="max-h-[70vh] overflow-auto">
            <table className="w-full text-left text-sm">
              <thead className="bg-card text-text-muted sticky top-0 text-xs">
                <tr>
                  <th className="py-1.5 font-medium">Episode</th>
                  <th className="font-medium">Outcome</th>
                  <th className="text-right font-medium">Steps</th>
                  <th className="text-right font-medium">Reward</th>
                </tr>
              </thead>
              <tbody>
                {episodes.map((e) => (
                  <tr
                    key={e.id}
                    onClick={() => setSelected(e)}
                    className={`border-border/60 cursor-pointer border-t ${selected?.id === e.id ? "bg-accent-light" : "hover:bg-sunken"}`}
                  >
                    <td className="py-1.5 font-mono text-xs">
                      {shortId(e.id)}
                      <div className="text-text-dim">{e.pilot ?? ""}</div>
                    </td>
                    <td>
                      <Outcome value={e.outcome} />
                    </td>
                    <td className="text-right font-mono text-xs">
                      {e.steps ?? "-"}
                    </td>
                    <td className="text-right font-mono text-xs">
                      {fixed(e.totalReward, 1)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      {selected ? (
        <div className="flex min-w-0 flex-col gap-4">
          <Card
            title={shortId(selected.id)}
            actions={
              <Button variant="danger" onClick={remove}>
                Move to trash
              </Button>
            }
          >
            <div className="grid grid-cols-3 gap-4">
              <StreamView
                obs={frame}
                stream="rgb"
                maskIds={maskIds}
                depthMax={64}
                label="Camera"
              />
              <StreamView
                obs={frame}
                stream="depth"
                maskIds={maskIds}
                depthMax={64}
                label="Depth"
              />
              <StreamView
                obs={frame}
                stream="mask"
                maskIds={maskIds}
                depthMax={64}
                label="Semantic mask"
              />
            </div>
            <div className="mt-4 flex items-center gap-3">
              <span className="text-text-muted w-24 font-mono text-xs">
                step {frameIndex}/{Math.max(0, steps.length - 1)}
              </span>
              <input
                type="range"
                min={0}
                max={Math.max(0, steps.length - 1)}
                value={frameIndex}
                onChange={(e) => setFrameIndex(Number(e.target.value))}
                className="flex-1 accent-[var(--color-accent)]"
              />
            </div>
          </Card>
          <div className="grid gap-4 lg:grid-cols-2">
            <Card title="Reward per step">
              <LineChart
                values={steps.map((s) => s.reward)}
                labels={steps.map((s) => `step ${s.step}`)}
                digits={2}
                cursor={frameIndex}
                onSelect={setFrameIndex}
              />
            </Card>
            <Card title="This step">
              <Row label="Action move">
                {row?.action ? vec(row.action.move, 2) : "terminal"}
              </Row>
              <Row label="Action look">
                {row?.action ? vec(row.action.look, 1) : "-"}
              </Row>
              <Row label="Reward">{fixed(row?.reward, 3)}</Row>
              <Row label="Position">
                {vec(row?.state.pos as number[] | undefined)}
              </Row>
              <Row label="Seed">{selected.seed}</Row>
              <Row label="Marker">{vec(selected.marker, 0)}</Row>
              <Row label="Dropped frames">{selected.droppedFrames ?? 0}</Row>
            </Card>
          </div>
        </div>
      ) : (
        <Card>
          <p className="text-text-dim text-sm">
            Select an episode to scrub through its frames.
          </p>
        </Card>
      )}

      <ConfirmModal
        open={confirmOpen}
        title="Move episode to trash?"
        body="The folder moves to data/.trash, where you can restore it by moving it back."
        confirmLabel="Move to trash"
        onResolve={resolveConfirm}
      />
    </div>
  );
}
