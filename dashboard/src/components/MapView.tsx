import { useEffect, useState } from "react";
import { BLUEMAP_PATH, DEV_BLUEMAP_PORT } from "../config";
import type { Vec3 } from "../protocol";
import { useBridge } from "../stores/bridge";
import { DronesPanel } from "./DronesPanel";
import { Button, Card, Pill } from "./ui";

// BlueMap reads its camera from the address: map, the point it looks at, distance, rotation, angle, tilt,
// orthographic, and mode. The point rounds to whole blocks so the camera only moves when the drone does
function cameraHash(map: string, pos: Vec3): string {
  const [x, y, z] = pos.map(Math.round);
  return `#${map}:${x}:${y}:${z}:45:0.5:0.9:0:0:perspective`;
}

/** BlueMap's web map of the world, with the mod's regions, drones, and charging stations as markers */
export function MapView() {
  const status = useBridge((s) => s.status);
  const [up, setUp] = useState<boolean | null>(null);
  const [mapId, setMapId] = useState<string | null>(null);
  const [follow, setFollow] = useState(true);
  const [frozen, setFrozen] = useState("");
  const drones = status?.drones ?? [];
  const active = drones.find((d) => d.active) ?? drones[0];
  const live = mapId && active ? cameraHash(mapId, active.pos) : "";
  const hash = follow ? live : frozen;

  useEffect(() => {
    let cancelled = false;
    const check = () =>
      fetch(`${BLUEMAP_PATH}settings.json`)
        .then((r) => {
          if (!r.ok) throw new Error(`${r.status}`);
          return r.json();
        })
        .then((s: { maps?: string[] }) => {
          if (!cancelled) {
            setUp(true);
            setMapId(s.maps?.[0] ?? null);
          }
        })
        .catch(() => !cancelled && setUp(false));
    check();
    const timer = setInterval(check, 5000);
    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, []);

  return (
    <div className="grid gap-4 xl:grid-cols-[minmax(0,1fr)_360px]">
      <Card
        title="World map"
        actions={
          <div className="flex items-center gap-2">
            {up === false && <Pill tone="red">BlueMap offline</Pill>}
            {up && live && (
              <Button
                onClick={() => {
                  setFrozen(live);
                  setFollow(!follow);
                }}
                title="Keep the map on the active drone as it flies"
              >
                {follow ? "Stop following" : "Follow the drone"}
              </Button>
            )}
            <a
              href={BLUEMAP_PATH}
              target="_blank"
              rel="noreferrer"
              className="text-accent text-xs underline"
            >
              Open in a new tab
            </a>
          </div>
        }
      >
        {up === false ? (
          <p className="text-text-muted text-sm">
            No map on port {DEV_BLUEMAP_PORT}. BlueMap runs in the game once
            it's installed and <code>accept-download</code> is{" "}
            <code>true</code> in the game's{" "}
            <code>config/bluemap/core.conf</code>.
          </p>
        ) : (
          // a change to the hash alone moves BlueMap's camera without reloading the page
          <iframe
            title="BlueMap"
            src={`${BLUEMAP_PATH}${hash}`}
            className="border-border h-[calc(100vh-180px)] min-h-[480px] w-full rounded-md border"
          />
        )}
      </Card>
      <div className="flex flex-col gap-4">
        <DronesPanel />
      </div>
    </div>
  );
}
