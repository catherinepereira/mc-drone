import { decodeObs, type LogLine, type Observation } from "./protocol";

export interface EpisodeMeta {
  id: string;
  task: string;
  seed: number;
  width: number;
  height: number;
  streams: string[];
  pilot?: string;
  startedAt?: string;
  outcome?: string;
  steps?: number;
  totalReward?: number;
  droppedFrames?: number;
  marker?: [number, number, number];
}

export interface StepRow {
  step: number;
  tick: number;
  state: Record<string, unknown>;
  action: { move: number[]; look: number[] } | null;
  reward: number;
  done: boolean;
}

async function json<T>(res: Response): Promise<T> {
  if (!res.ok) {
    const body = await res.json().catch(() => ({ error: res.statusText }));
    throw new Error(body.error ?? res.statusText);
  }
  return res.json();
}

export const api = {
  logs: (limit = 500) =>
    fetch(`/api/logs?limit=${limit}`).then((r) => json<LogLine[]>(r)),
  config: () =>
    fetch("/api/config").then((r) => json<Record<string, unknown>>(r)),
  saveConfig: (patch: Record<string, unknown>) =>
    fetch("/api/config", {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(patch),
    }).then((r) => json<Record<string, unknown>>(r)),
  episodes: () => fetch("/api/episodes").then((r) => json<EpisodeMeta[]>(r)),
  episode: (task: string, id: string) =>
    fetch(`/api/episodes/${task}/${id}`).then((r) =>
      json<{ meta: EpisodeMeta; steps: StepRow[] }>(r),
    ),
  frame: async (task: string, id: string, n: number): Promise<Observation> => {
    const res = await fetch(`/api/episodes/${task}/${id}/frame/${n}`);
    if (!res.ok) throw new Error(`frame ${n}: ${res.statusText}`);
    return decodeObs(await res.arrayBuffer());
  },
  deleteEpisode: (task: string, id: string) =>
    fetch(`/api/episodes/${task}/${id}`, { method: "DELETE" }).then((r) =>
      json<{ ok: boolean }>(r),
    ),
};
