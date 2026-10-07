import { useEffect, useState } from "react";
import { BLUEMAP_URL } from "../config";
import { DronesPanel } from "./DronesPanel";
import { Card, Pill } from "./ui";

/** BlueMap's web map of the world, with the mod's regions, drones, and charging stations as markers */
export function MapView() {
  const [up, setUp] = useState<boolean | null>(null);

  useEffect(() => {
    let cancelled = false;
    const check = () =>
      // an opaque reply still proves the server is there
      fetch(BLUEMAP_URL, { mode: "no-cors" })
        .then(() => !cancelled && setUp(true))
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
            <a
              href={BLUEMAP_URL}
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
            No map at {BLUEMAP_URL}. Install BlueMap next to mc-drone and load a
            world. The dev client runs it already. Toggle the drone and region
            markers from the map's menu.
          </p>
        ) : (
          <iframe
            title="BlueMap"
            src={BLUEMAP_URL}
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
