import { create } from "zustand";
import { SCHEMA } from "../config";
import {
  decodeObs,
  type DroneAction,
  type LogLine,
  type MaskIds,
  type Memory,
  type MemoryChange,
  type Metrics,
  type Observation,
  type RegionPurpose,
  type Status,
  type Vec3,
} from "../protocol";

const LOG_LIMIT = 2000;
const METRIC_LIMIT = 180;
const ACTION_LIMIT = 60;

export interface MetricPoint extends Metrics {
  t: number;
  latencyMs: number | null;
}

interface Pending {
  resolve: (obs: Observation) => void;
  reject: (err: Error) => void;
}

interface BridgeState {
  connected: boolean;
  role: "controller" | "observer" | null;
  status: Status | null;
  config: Record<string, unknown> | null;
  maskIds: MaskIds | null;
  latest: Observation | null;
  actions: { tick: number; action: DroneAction; reward: number }[];
  metrics: MetricPoint[];
  logs: LogLine[];
  lastError: string | null;
  latencyMs: number | null;
  memory: Memory | null;

  connect: () => void;
  takeControl: () => void;
  release: () => void;
  setMode: (mode: "realtime" | "lockstep") => void;
  reset: (
    seed: number | null,
    options: Record<string, unknown>,
  ) => Promise<Observation>;
  step: (action: DroneAction, ticks: number) => Promise<Observation>;
  act: (action: DroneAction) => void;
  record: (on: boolean) => void;
  pilot: (on: boolean) => void;
  select: (
    patch: Partial<Record<"cornerA" | "cornerB" | "dest", Vec3 | null>>,
  ) => void;
  exportSchematic: (name: string) => void;
  saveRegion: (name: string, purpose: RegionPurpose) => void;
  deleteRegion: (id: string) => void;
  selectRegion: (id: string) => void;
  clearError: () => void;
}

let socket: WebSocket | null = null;
let nextId = 1;
let pingSentAt = new Map<number, number>();
const pending = new Map<number, Pending>();

function send(msg: Record<string, unknown>) {
  if (socket?.readyState === WebSocket.OPEN) socket.send(JSON.stringify(msg));
}

function request(msg: Record<string, unknown>): Promise<Observation> {
  const id = nextId++;
  return new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject });
    send({ ...msg, id });
  });
}

const MEMORY_LOG_LIMIT = 50000;

interface MemoryMessage {
  step: number;
  changes: Omit<MemoryChange, "step">[];
  snapshot?: [number, number, number, string][];
  focus?: Memory["focus"];
}

/**
 * Folds one memory message in. A snapshot from an earlier step than the latest means a new episode, so the history
 * restarts. Later snapshots are kept as the base and the log keeps every change since, for scrubbing
 */
function applyMemory(prev: Memory | null, msg: MemoryMessage): Memory {
  const restart = !prev || (msg.snapshot && msg.step < prev.latestStep);
  let next: Memory = restart
    ? {
        baseStep: msg.step,
        base: {},
        log: [],
        latestStep: msg.step,
        focus: null,
      }
    : { ...prev! };
  if (msg.snapshot && restart) {
    next.base = Object.fromEntries(
      msg.snapshot.map(([x, y, z, label]) => [`${x},${y},${z}`, label]),
    );
  }
  const changes = msg.changes.map((c) => ({ ...c, step: msg.step }));
  next = {
    ...next,
    log: [...next.log, ...changes].slice(-MEMORY_LOG_LIMIT),
    latestStep: msg.step,
    focus: msg.focus ?? next.focus,
  };
  return next;
}

export const useBridge = create<BridgeState>((set, get) => ({
  connected: false,
  role: null,
  status: null,
  config: null,
  maskIds: null,
  latest: null,
  actions: [],
  metrics: [],
  logs: [],
  lastError: null,
  latencyMs: null,
  memory: null,

  connect: () => {
    if (socket && socket.readyState <= WebSocket.OPEN) return;
    const proto = location.protocol === "https:" ? "wss" : "ws";
    const ws = new WebSocket(`${proto}://${location.host}/ws`);
    ws.binaryType = "arraybuffer";
    socket = ws;

    ws.onopen = () => {
      set({ connected: true });
      send({
        type: "hello",
        role: "observer",
        schema: SCHEMA,
        client: "dashboard",
      });
      send({ type: "subscribe", obs: true, logs: true, metrics: true });
    };

    ws.onclose = () => {
      socket = null;
      pingSentAt = new Map();
      for (const p of pending.values())
        p.reject(new Error("bridge disconnected"));
      pending.clear();
      set({ connected: false, role: null });
      setTimeout(() => get().connect(), 2000);
    };

    ws.onmessage = (event) => {
      if (event.data instanceof ArrayBuffer) {
        const obs = decodeObs(event.data);
        if (obs.replyTo != null) {
          pending.get(obs.replyTo)?.resolve(obs);
          pending.delete(obs.replyTo);
        }
        set((s) => ({
          latest: obs,
          actions: obs.action
            ? [
                {
                  tick: obs.tick,
                  action: obs.action,
                  reward: obs.episode?.reward ?? 0,
                },
                ...s.actions,
              ].slice(0, ACTION_LIMIT)
            : s.actions,
        }));
        return;
      }
      const msg = JSON.parse(event.data);
      switch (msg.type) {
        case "welcome":
          set({
            role: msg.role,
            config: msg.config,
            status: msg.status,
            maskIds: msg.maskIds,
          });
          break;
        case "status":
          set({ status: msg });
          break;
        case "metrics":
          set((s) => ({
            metrics: [
              ...s.metrics,
              { ...msg, t: Date.now(), latencyMs: s.latencyMs },
            ].slice(-METRIC_LIMIT),
          }));
          {
            const id = nextId++;
            pingSentAt.set(id, performance.now());
            send({ type: "ping", id });
          }
          break;
        case "pong": {
          const sent = pingSentAt.get(msg.replyTo);
          if (sent != null) {
            pingSentAt.delete(msg.replyTo);
            set({ latencyMs: performance.now() - sent });
          }
          break;
        }
        case "memory":
          set((s) => ({ memory: applyMemory(s.memory, msg) }));
          break;
        case "log":
          set((s) => ({ logs: [...s.logs, msg.entry].slice(-LOG_LIMIT) }));
          break;
        case "error":
          if (msg.replyTo != null && pending.has(msg.replyTo)) {
            pending.get(msg.replyTo)!.reject(new Error(msg.message));
            pending.delete(msg.replyTo);
          }
          set({ lastError: msg.message });
          break;
      }
    };
  },

  takeControl: () =>
    send({
      type: "hello",
      role: "controller",
      schema: SCHEMA,
      client: "dashboard",
    }),
  release: () => {
    send({ type: "release" });
    set({ role: "observer" });
  },
  setMode: (mode) => send({ type: "configure", mode }),
  reset: (seed, options) => request({ type: "reset", seed, options }),
  step: (action, ticks) => request({ type: "step", action, ticks }),
  act: (action) => send({ type: "act", action }),
  record: (on) => send({ type: "record", on }),
  pilot: (on) => send({ type: "pilot", on }),
  select: (patch) => send({ type: "select", ...patch }),
  exportSchematic: (name) => send({ type: "export", name }),
  saveRegion: (name, purpose) => send({ type: "region_save", name, purpose }),
  deleteRegion: (region) => send({ type: "region_delete", region }),
  selectRegion: (region) => send({ type: "region_use", region }),
  clearError: () => set({ lastError: null }),
}));

export function seedLogs(lines: LogLine[]) {
  useBridge.setState((s) => {
    const seen = new Set(s.logs.map((l) => l.ts + l.event));
    const merged = [
      ...lines.filter((l) => !seen.has(l.ts + l.event)),
      ...s.logs,
    ];
    merged.sort((a, b) => a.ts.localeCompare(b.ts));
    return { logs: merged.slice(-LOG_LIMIT) };
  });
}
