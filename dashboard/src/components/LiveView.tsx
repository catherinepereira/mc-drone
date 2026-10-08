import { useBridge } from "../stores/bridge";
import { fixed, vec } from "../utils/format";
import { ArenaMap } from "./ArenaMap";
import { ControlPanel } from "./ControlPanel";
import { DronesPanel } from "./DronesPanel";
import { InventoryPanel } from "./InventoryPanel";
import { JobsPanel } from "./JobsPanel";
import { MemoryPanel } from "./MemoryPanel";
import { RegionsPanel } from "./RegionsPanel";
import { StreamView } from "./StreamView";
import { Card, Pill, Row } from "./ui";

export function LiveView() {
  const { latest, maskIds, config, status, actions } = useBridge();
  const depthMax = Number(config?.depthMax ?? 64);
  const state = latest?.state;
  const episode = latest?.episode ?? status?.episode ?? null;

  return (
    <div className="grid gap-4 xl:grid-cols-[minmax(0,1fr)_360px]">
      <div className="flex min-w-0 flex-col gap-4">
        <Card>
          <StreamView
            obs={latest}
            stream="rgb"
            maskIds={maskIds}
            depthMax={depthMax}
            label="Camera"
          />
          <div className="mt-4 grid grid-cols-2 gap-4">
            <StreamView
              obs={latest}
              stream="depth"
              maskIds={maskIds}
              depthMax={depthMax}
              label="Depth"
            />
            <StreamView
              obs={latest}
              stream="mask"
              maskIds={maskIds}
              depthMax={depthMax}
              label="Semantic mask"
            />
          </div>
        </Card>
        <InventoryPanel />
        <Card title="Recent actions">
          {actions.length === 0 ? (
            <p className="text-text-dim text-sm">No actions yet.</p>
          ) : (
            <div className="max-h-64 overflow-auto">
              <table className="w-full text-left font-mono text-xs">
                <thead className="bg-card text-text-muted sticky top-0">
                  <tr>
                    <th className="py-1 font-medium">tick</th>
                    <th className="font-medium">forward</th>
                    <th className="font-medium">right</th>
                    <th className="font-medium">up</th>
                    <th className="font-medium">yaw</th>
                    <th className="font-medium">pitch</th>
                    <th className="text-right font-medium">reward</th>
                  </tr>
                </thead>
                <tbody>
                  {actions.map((a, i) => (
                    <tr
                      key={`${a.tick}-${i}`}
                      className="border-border/60 border-t"
                    >
                      <td className="text-text-muted py-1">{a.tick}</td>
                      <td>{fixed(a.action.move[0])}</td>
                      <td>{fixed(a.action.move[1])}</td>
                      <td>{fixed(a.action.move[2])}</td>
                      <td>{fixed(a.action.look[0], 1)}</td>
                      <td>{fixed(a.action.look[1], 1)}</td>
                      <td className="text-right">{fixed(a.reward, 3)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Card>
      </div>

      <div className="flex flex-col gap-4">
        <ControlPanel />
        <DronesPanel />
        <JobsPanel />
        <RegionsPanel />
        <Card title="Drone">
          <Row label="Position">{vec(state?.pos)}</Row>
          <Row label="Velocity">{vec(state?.vel, 2)}</Row>
          <Row label="Yaw, pitch">
            {fixed(state?.yaw, 0)}°, {fixed(state?.pitch, 0)}°
          </Row>
          <Row label="Looking at">
            {state?.lookingAt
              ? `${state.lookingAt.block.replace("minecraft:", "")} at ${fixed(state.lookingAt.dist, 1)}`
              : "-"}
          </Row>
          <Row label="Tick">{latest?.tick ?? "-"}</Row>
        </Card>
        <ArenaMap state={state} />
        <MemoryPanel />
        <Card
          title="Episode"
          actions={
            episode ? (
              episode.success ? (
                <Pill tone="green">success</Pill>
              ) : episode.outOfBounds ? (
                <Pill tone="red">out of bounds</Pill>
              ) : episode.truncated ? (
                <Pill tone="amber">timed out</Pill>
              ) : (
                <Pill tone="accent">running</Pill>
              )
            ) : null
          }
        >
          {episode ? (
            <>
              <Row label="Id">{episode.id.replace("navigate_to-", "")}</Row>
              <Row label="Step">{episode.step}</Row>
              <Row label="Distance">{fixed(episode.distance, 2)}</Row>
              <Row label="Total reward">{fixed(episode.totalReward, 2)}</Row>
              <Row label="Collisions">
                {episode.collisions ?? 0}
                {episode.droneCollisions
                  ? `, ${episode.droneCollisions} with drones`
                  : ""}
              </Row>
              {episode.patrol && (
                <Row label="Patrol">
                  {episode.patrol.visited} of {episode.patrol.cells} cells,
                  round{" "}
                  {Math.min(episode.patrol.round + 1, episode.patrol.rounds)} of{" "}
                  {episode.patrol.rounds}
                </Row>
              )}
              {(episode.task === "hunt_mobs" ||
                episode.task === "guard_region") &&
                episode.metrics && (
                  <Row label="Hostile mobs">
                    {episode.metrics[1]} killed, {episode.metrics[0]} left
                  </Row>
                )}
              <Row label="Marker">{vec(state?.marker ?? undefined, 0)}</Row>
            </>
          ) : (
            <p className="text-text-dim text-sm">
              No episode yet. Start one from Control or press N in game.
            </p>
          )}
        </Card>
      </div>
    </div>
  );
}
