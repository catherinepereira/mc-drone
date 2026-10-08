import { useEffect, useRef, useState } from "react";
import type { DroneAction, Tool } from "../protocol";
import { useBridge } from "../stores/bridge";
import { Button, Card, Field, inputClass } from "./ui";

const LOOK = 6;
const TASKS = [
  "navigate_to",
  "dig_block",
  "place_block",
  "chest_transfer",
  "mine_and_deliver",
  "replicate_build",
  "harvest_crops",
  "patrol_area",
  "hunt_mobs",
  "goto_point",
  "find_block",
  "follow_mob",
];

// WASD moves, space and shift climb and descend, arrow keys turn, F held mines, V held hits a hostile mob, G places,
// R opens or closes a container
function keysToAction(
  keys: Set<string>,
  tapped: Set<string>,
  containerOpen: boolean,
  slot: number,
): DroneAction {
  const k = (code: string) => (keys.has(code) ? 1 : 0);
  let tool: Tool = "none";
  if (keys.has("KeyF")) tool = "break";
  else if (keys.has("KeyV")) tool = "attack";
  else if (tapped.has("KeyG")) tool = "place";
  else if (tapped.has("KeyR")) tool = containerOpen ? "close" : "open";
  return {
    tool,
    slot,
    move: [
      k("KeyW") - k("KeyS"),
      k("KeyD") - k("KeyA"),
      k("Space") - k("ShiftLeft"),
    ],
    look: [
      (k("ArrowRight") - k("ArrowLeft")) * LOOK,
      (k("ArrowDown") - k("ArrowUp")) * LOOK,
    ],
  };
}

export function ControlPanel() {
  const {
    role,
    status,
    takeControl,
    release,
    setMode,
    reset,
    step,
    act,
    record,
    pilot,
    renameDrone,
    latest,
  } = useBridge();
  const [droneName, setDroneName] = useState("");
  const [task, setTask] = useState("navigate_to");
  const [terrain, setTerrain] = useState("flat");
  const [targets, setTargets] = useState(1);
  const tapped = useRef(new Set<string>());
  const containerOpen = !!latest?.state.container;
  const slot = latest?.state.selectedSlot ?? 0;
  const [seed, setSeed] = useState("");
  const [radius, setRadius] = useState(12);
  const [obstacles, setObstacles] = useState(0);
  const [ticks, setTicks] = useState(1);
  const [driving, setDriving] = useState(false);
  const [busy, setBusy] = useState(false);
  const keys = useRef(new Set<string>());

  const isController = role === "controller";
  const lockstep = status?.mode === "lockstep";

  useEffect(() => {
    if (!driving || !isController) return;
    const down = (e: KeyboardEvent) => {
      if ((e.target as HTMLElement).tagName === "INPUT") return;
      if (!e.repeat) tapped.current.add(e.code);
      keys.current.add(e.code);
      e.preventDefault();
    };
    const up = (e: KeyboardEvent) => keys.current.delete(e.code);
    window.addEventListener("keydown", down);
    window.addEventListener("keyup", up);
    // realtime sends the held keys at 10 Hz, lockstep steps once per interval while a key is held
    const timer = window.setInterval(() => {
      const action = keysToAction(
        keys.current,
        tapped.current,
        containerOpen,
        slot,
      );
      tapped.current.clear();
      if (!lockstep) act(action);
      else if (keys.current.size > 0) step(action, ticks).catch(() => {});
    }, 100);
    const held = keys.current;
    return () => {
      window.removeEventListener("keydown", down);
      window.removeEventListener("keyup", up);
      window.clearInterval(timer);
      held.clear();
      if (!lockstep) act({ move: [0, 0, 0], look: [0, 0] });
    };
  }, [driving, isController, lockstep, ticks, act, step, containerOpen, slot]);

  async function run(fn: () => Promise<unknown>) {
    setBusy(true);
    try {
      await fn();
    } catch {
      // the store records the error message for the banner
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card
      title="Control"
      actions={
        isController ? (
          <Button onClick={release}>Release</Button>
        ) : (
          <Button
            variant="primary"
            onClick={takeControl}
            disabled={!!status?.controller}
          >
            Take control
          </Button>
        )
      }
    >
      {!isController && (
        <p className="text-text-muted mb-3 text-sm">
          {status?.controller
            ? `${status.controller} is driving. Watching as an observer.`
            : "Watching as an observer. Take control to drive or reset."}
        </p>
      )}
      <fieldset
        disabled={!isController}
        className="flex flex-col gap-4 disabled:opacity-60"
      >
        <div className="flex items-center gap-2">
          <span className="text-text-muted text-xs">Mode</span>
          <div className="border-border flex rounded-md border p-0.5">
            {(["realtime", "lockstep"] as const).map((m) => (
              <button
                key={m}
                onClick={() => setMode(m)}
                className={`rounded px-3 py-1 text-sm ${status?.mode === m ? "bg-accent-light text-accent font-medium" : "text-text-muted"}`}
              >
                {m}
              </button>
            ))}
          </div>
        </div>

        <div className="grid grid-cols-3 gap-2">
          <Field label="Task">
            <select
              className={inputClass}
              value={task}
              onChange={(e) => setTask(e.target.value)}
            >
              {TASKS.map((t) => (
                <option key={t}>{t}</option>
              ))}
            </select>
          </Field>
          <Field label="Terrain">
            <select
              className={inputClass}
              value={terrain}
              onChange={(e) => setTerrain(e.target.value)}
            >
              <option>flat</option>
              <option>rough</option>
              <option>cave</option>
            </select>
          </Field>
          <Field label="Targets">
            <input
              className={inputClass}
              type="number"
              min={1}
              max={8}
              value={targets}
              onChange={(e) => setTargets(Number(e.target.value))}
            />
          </Field>
          <Field label="Seed">
            <input
              className={inputClass}
              placeholder="random"
              value={seed}
              onChange={(e) => setSeed(e.target.value.replace(/\D/g, ""))}
            />
          </Field>
          <Field label="Radius">
            <input
              className={inputClass}
              type="number"
              min={4}
              max={48}
              value={radius}
              onChange={(e) => setRadius(Number(e.target.value))}
            />
          </Field>
          <Field label="Obstacles">
            <input
              className={inputClass}
              type="number"
              min={0}
              max={32}
              value={obstacles}
              onChange={(e) => setObstacles(Number(e.target.value))}
            />
          </Field>
        </div>
        <div className="flex flex-wrap gap-2">
          <Button
            variant="primary"
            disabled={busy}
            onClick={() =>
              run(() =>
                reset(seed ? Number(seed) : null, {
                  task,
                  terrain,
                  targets,
                  radius,
                  obstacles,
                }),
              )
            }
          >
            New episode
          </Button>
          <Button onClick={() => record(!status?.recordArmed)}>
            {status?.recordArmed ? "Stop recording" : "Record episodes"}
          </Button>
          <Button onClick={() => pilot(!status?.piloting)}>
            {status?.piloting ? "Camera to player" : "Camera to drone"}
          </Button>
        </div>

        <div className="flex items-end gap-2">
          <div className="flex-1">
            <Field label="Drone name, shown as name (owner)">
              <input
                className={inputClass}
                placeholder="Harvester"
                maxLength={32}
                value={droneName}
                onChange={(e) => setDroneName(e.target.value)}
              />
            </Field>
          </div>
          <Button onClick={() => renameDrone(droneName)}>Rename</Button>
        </div>

        <div className="border-border bg-sunken flex flex-col gap-2 rounded-md border p-3">
          <div className="flex items-center justify-between">
            <span className="text-sm font-medium">Keyboard drive</span>
            <Button
              variant={driving ? "primary" : "secondary"}
              onClick={() => setDriving(!driving)}
            >
              {driving ? "Driving" : "Start"}
            </Button>
          </div>
          <p className="text-text-muted text-xs">
            WASD to move, Space and Shift to climb and descend, arrow keys to
            turn. Hold F to mine, G places from the selected slot, R opens or
            closes the container in front.
            {lockstep
              ? " Each held interval sends one step."
              : " Sends the held keys ten times a second."}
          </p>
          {lockstep && (
            <div className="flex items-end gap-2">
              <Field label="Ticks per step">
                <input
                  className={`${inputClass} w-20`}
                  type="number"
                  min={1}
                  max={100}
                  value={ticks}
                  onChange={(e) => setTicks(Number(e.target.value))}
                />
              </Field>
              <Button
                disabled={busy}
                onClick={() =>
                  run(() => step({ move: [0, 0, 0], look: [0, 0] }, ticks))
                }
              >
                Step idle
              </Button>
            </div>
          )}
        </div>
      </fieldset>
    </Card>
  );
}
