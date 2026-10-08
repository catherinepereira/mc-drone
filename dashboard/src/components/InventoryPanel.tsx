import { shortName } from "../blockColors";
import type { DroneAction, Slot, Transfer } from "../protocol";
import { useBridge } from "../stores/bridge";
import { Card, Pill } from "./ui";

const IDLE: DroneAction = { move: [0, 0, 0], look: [0, 0] };

function SlotGrid({
  slots,
  selected,
  onClick,
  disabled,
}: {
  slots: Slot[];
  selected?: number;
  onClick: (i: number) => void;
  disabled: boolean;
}) {
  return (
    <div className="grid max-w-[420px] grid-cols-9 gap-1">
      {slots.map(([item, count], i) => (
        <button
          key={i}
          disabled={disabled}
          onClick={() => onClick(i)}
          title={count > 0 ? `${count} ${shortName(item)}` : `slot ${i}`}
          className={`bg-sunken flex aspect-square flex-col items-center justify-center rounded border text-[10px] leading-tight ${
            i === selected ? "border-accent" : "border-border"
          } enabled:hover:border-accent/60 disabled:cursor-default`}
        >
          {count > 0 ? (
            <>
              <span className="text-text-primary line-clamp-2 px-0.5 text-center">
                {shortName(item)}
              </span>
              <span className="text-text-muted font-mono">{count}</span>
            </>
          ) : (
            <span className="text-text-dim font-mono">{i}</span>
          )}
        </button>
      ))}
    </div>
  );
}

/** The drone's 27 slots and the open container. Clicks become the same actions a policy sends */
export function InventoryPanel() {
  const { latest, role, status, act, step } = useBridge();
  const state = latest?.state;
  const inventory = state?.inventory ?? [];
  const container = state?.container ?? null;
  const breaking = state?.breaking ?? null;
  const isController = role === "controller";

  function send(action: DroneAction) {
    if (status?.mode === "lockstep") step(action, 1).catch(() => {});
    else act(action);
  }

  function transfer(t: Transfer) {
    send({ ...IDLE, slot: state?.selectedSlot ?? 0, transfer: t });
  }

  if (!inventory.length) {
    return (
      <Card title="Inventory">
        <p className="text-text-dim text-sm">
          Shows once an episode is running.
        </p>
      </Card>
    );
  }

  return (
    <Card
      title="Inventory"
      actions={
        <div className="flex gap-2">
          {breaking && (
            <Pill tone="amber">
              mining {Math.round(breaking.progress * 100)}%
            </Pill>
          )}
          {container && <Pill tone="accent">container open</Pill>}
        </div>
      }
    >
      {container && (
        <div className="mb-3">
          <p className="text-text-muted mb-1.5 text-xs">
            {shortName(container.block)} at {container.pos.join(", ")}
            {isController ? ", click a slot to take it" : ""}
          </p>
          <SlotGrid
            slots={container.slots}
            disabled={!isController}
            onClick={(i) => transfer({ from: "container", slot: i })}
          />
        </div>
      )}
      <p className="text-text-muted mb-1.5 text-xs">
        Drone
        {isController
          ? container
            ? ", click a slot to store it"
            : ", click a slot to select it for placing"
          : ""}
      </p>
      <SlotGrid
        slots={inventory}
        selected={state?.selectedSlot}
        disabled={!isController}
        onClick={(i) =>
          container
            ? transfer({ from: "drone", slot: i })
            : send({ ...IDLE, slot: i })
        }
      />
    </Card>
  );
}
