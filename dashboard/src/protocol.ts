// Mirrors docs/PROTOCOL.md

export type Vec3 = [number, number, number];

export const TOOLS = ["none", "break", "place", "open", "close"] as const;
export type Tool = (typeof TOOLS)[number];

export interface Transfer {
  from: "drone" | "container";
  slot: number;
  toSlot?: number;
  count?: number;
}

export interface DroneAction {
  move: Vec3;
  look: [number, number];
  tool?: Tool;
  slot?: number;
  transfer?: Transfer | null;
}

/** [item id, count], "minecraft:air" with 0 for an empty slot */
export type Slot = [string, number];

export interface Arena {
  task?: string;
  terrain?: string;
  origin: Vec3;
  radius: number;
  obstacles: [number, number, number, number][];
  trees?: [number, number, number][];
  stalactites?: [number, number, number][];
  marker?: Vec3;
  targets?: Vec3[];
  distractors?: Vec3[];
  goal?: Vec3;
  sourceChest?: Vec3;
  targetChest?: Vec3;
  referenceBase?: Vec3;
  buildBase?: Vec3;
  blueprint?: { offset: Vec3; block: string }[];
}

export interface DroneState {
  pos?: Vec3;
  vel?: Vec3;
  yaw?: number;
  pitch?: number;
  tier?: string;
  battery?: Battery;
  lookingAt?: { block: string; pos: Vec3; dist: number } | null;
  marker?: Vec3 | null;
  collided?: boolean;
  arena?: Arena | null;
  job?: Job | null;
  bounds?: [number, number, number, number, number, number] | null;
  inventory?: Slot[];
  selectedSlot?: number;
  container?: { pos: Vec3; block: string; slots: Slot[] } | null;
  breaking?: { pos: Vec3; progress: number } | null;
  events?: { type: string; [key: string]: unknown }[];
}

export interface EpisodeInfo {
  id: string;
  task: string;
  seed?: number;
  step: number;
  reward: number;
  totalReward?: number;
  distance?: number;
  done: boolean;
  success?: boolean;
  truncated?: boolean;
  collisions?: number;
  droneCollisions?: number;
  outOfBounds?: boolean;
  metrics?: number[];
}

export interface Status {
  type: "status";
  mode: "realtime" | "lockstep";
  controller: string | null;
  observers: number;
  recording: boolean;
  recordArmed: boolean;
  piloting: boolean;
  droneId: number;
  inWorld: boolean;
  episode: EpisodeInfo | null;
  selection?: Selection;
  regions?: Region[];
  drones?: DroneInfo[];
}

/** A queued job: the options it starts with and what the tablet calls it */
export interface QueuedJob {
  task: string;
  label?: string;
  [option: string]: unknown;
}

/** One of the player's loaded drones, charge runs 0 to 1 */
export interface DroneInfo {
  id: number;
  name: string;
  tier: "copper" | "iron" | "diamond";
  active: boolean;
  pos: Vec3;
  charge: number;
  battery: boolean;
  home: Vec3 | null;
  docked: boolean;
  queue: QueuedJob[];
  // the bridge client flying it and the episode it's on, null while it isn't working
  controller: string | null;
  episode: { id: string; task: string; done: boolean; success: boolean } | null;
}

/** What the drone knows of its battery, costs are charge per tick or per block broken */
export interface Battery {
  enabled: boolean;
  charge: number;
  home: Vec3 | null;
  docked: boolean;
  reserve: number;
  flightPerTick: number;
  hoverShare: number;
  breakCost: number;
  chargePerTick: number;
}

/** One belief change in the drone's voxel memory */
export interface MemoryChange {
  step: number;
  cell: Vec3;
  before: string;
  after: string;
  cause: string;
}

/** The drone's voxel memory as the dashboard rebuilds it: a snapshot and the changes since */
export interface Memory {
  baseStep: number;
  base: Record<string, string>;
  log: MemoryChange[];
  latestStep: number;
  focus: [number, number, number, number, number, number] | null;
}

export type RegionPurpose = "general" | "safe" | "mine" | "farm";

/** A named box saved with the world, box is inclusive min and max corners */
export interface Region {
  id: string;
  name: string;
  purpose: RegionPurpose;
  box: [number, number, number, number, number, number];
}

/** The player's copy selection, set with the tablet or the dashboard */
export interface Selection {
  cornerA: Vec3 | null;
  cornerB: Vec3 | null;
  dest: Vec3 | null;
  size: Vec3 | null;
  problem: string | null;
}

/** The drone's instruction for a copy or build job, inclusive min and max corners */
export interface Job {
  kind: "copy" | "build" | "mine" | "harvest" | "return_home";
  source?: [number, number, number, number, number, number];
  schematic?: string;
  dest: [number, number, number, number, number, number];
  size: Vec3;
}

export interface Metrics {
  type: "metrics";
  sps: number;
  fps: number;
  captures: number;
  captureMs: number;
  raycastMs: number;
  encodeMs: number;
  droppedFrames: number;
  queueDepth: number;
  observers: number;
}

export interface LogLine {
  ts: string;
  source: string;
  level: "debug" | "info" | "warn" | "error";
  event: string;
  session?: string;
  episode?: string;
  tick?: number;
  [field: string]: unknown;
}

export interface MaskIds {
  blocks: string[];
  entities: string[];
  entityBase: number;
}

export interface Observation {
  seq: number;
  tick: number;
  replyTo: number | null;
  state: DroneState;
  episode: EpisodeInfo | null;
  action: DroneAction | null;
  width: number;
  height: number;
  rgb?: Uint8Array;
  depth?: Float32Array;
  mask?: Uint16Array;
}

interface StreamSpec {
  offset: number;
  length: number;
  shape: number[];
  dtype: string;
}

export function decodeObs(buffer: ArrayBuffer): Observation {
  const view = new DataView(buffer);
  const headerLen = view.getUint32(0, true);
  const header = JSON.parse(
    new TextDecoder().decode(new Uint8Array(buffer, 4, headerLen)),
  );
  const base = 4 + headerLen;
  const streams: Record<string, StreamSpec> = header.streams ?? {};
  const any = Object.values(streams)[0];
  const obs: Observation = {
    seq: header.seq,
    tick: header.tick,
    replyTo: header.replyTo ?? null,
    state: header.state ?? {},
    episode: header.episode ?? null,
    action: header.action ?? null,
    height: any?.shape[0] ?? 0,
    width: any?.shape[1] ?? 0,
  };
  // copy each stream, typed array views need aligned offsets
  if (streams.rgb) {
    obs.rgb = new Uint8Array(
      buffer.slice(
        base + streams.rgb.offset,
        base + streams.rgb.offset + streams.rgb.length,
      ),
    );
  }
  if (streams.depth) {
    obs.depth = new Float32Array(
      buffer.slice(
        base + streams.depth.offset,
        base + streams.depth.offset + streams.depth.length,
      ),
    );
  }
  if (streams.mask) {
    obs.mask = new Uint16Array(
      buffer.slice(
        base + streams.mask.offset,
        base + streams.mask.offset + streams.mask.length,
      ),
    );
  }
  return obs;
}

export function maskName(id: number, ids: MaskIds | null): string {
  if (id === 0) return "sky";
  if (!ids) return `id ${id}`;
  if (id >= ids.entityBase)
    return ids.entities[id - ids.entityBase] ?? `entity ${id}`;
  return ids.blocks[id - 1] ?? `block ${id}`;
}
