import { useEffect, useState } from "react";
import { ConfigView } from "./components/ConfigView";
import { EpisodesView } from "./components/EpisodesView";
import { LiveView } from "./components/LiveView";
import { LogsView } from "./components/LogsView";
import { MapView } from "./components/MapView";
import { MetricsView } from "./components/MetricsView";
import { Pill } from "./components/ui";
import { useBridge } from "./stores/bridge";

const TABS = ["Live", "Map", "Episodes", "Logs", "Metrics", "Config"] as const;
type Tab = (typeof TABS)[number];

function readTab(): Tab {
  try {
    const saved = localStorage.getItem("mcdrone.tab");
    return TABS.includes(saved as Tab) ? (saved as Tab) : "Live";
  } catch {
    return "Live";
  }
}

export function App() {
  const { connect, connected, role, status, lastError, clearError } =
    useBridge();
  const [tab, setTab] = useState<Tab>(readTab);

  useEffect(connect, [connect]);

  useEffect(() => {
    try {
      localStorage.setItem("mcdrone.tab", tab);
    } catch {
      // private windows can refuse storage, then the tab isn't remembered
    }
  }, [tab]);

  return (
    <div className="min-h-screen">
      <header className="border-border bg-card border-b">
        <div className="mx-auto flex max-w-[1500px] flex-wrap items-center gap-x-6 gap-y-2 px-4 py-3">
          <div className="flex items-baseline gap-2">
            <h1 className="text-lg font-semibold">MC Drone</h1>
            <span className="text-text-muted text-sm">dev panel</span>
          </div>
          <nav className="flex gap-1">
            {TABS.map((t) => (
              <button
                key={t}
                onClick={() => setTab(t)}
                className={`rounded-md px-3 py-1.5 text-sm ${tab === t ? "bg-accent-light text-accent font-medium" : "text-text-muted hover:bg-sunken"}`}
              >
                {t}
              </button>
            ))}
          </nav>
          <div className="ml-auto flex flex-wrap items-center gap-2">
            {connected ? (
              <Pill tone="green">bridge connected</Pill>
            ) : (
              <Pill tone="red">bridge offline</Pill>
            )}
            {status && !status.inWorld && (
              <Pill tone="amber">no world loaded</Pill>
            )}
            {status && <Pill tone="accent">{status.mode}</Pill>}
            {role && <Pill>{role}</Pill>}
            {status?.recording ? (
              <Pill tone="red">recording</Pill>
            ) : status?.recordArmed ? (
              <Pill tone="amber">record armed</Pill>
            ) : null}
          </div>
        </div>
      </header>

      {lastError && (
        <div className="mx-auto mt-4 flex max-w-[1500px] items-center justify-between gap-4 px-4">
          <div className="border-red/30 bg-red-light text-red flex w-full items-center justify-between rounded-md border px-3 py-2 text-sm">
            <span>{lastError}</span>
            <button onClick={clearError} className="text-xs underline">
              Dismiss
            </button>
          </div>
        </div>
      )}

      <main className="mx-auto max-w-[1500px] px-4 py-4">
        {!connected && (
          <p className="border-border bg-card text-text-muted mb-4 rounded-md border px-3 py-2 text-sm">
            Waiting for the mod on port 8318. Start Minecraft with mc-drone
            installed, it reconnects on its own.
          </p>
        )}
        {tab === "Live" && <LiveView />}
        {tab === "Map" && <MapView />}
        {tab === "Episodes" && <EpisodesView />}
        {tab === "Logs" && <LogsView />}
        {tab === "Metrics" && <MetricsView />}
        {tab === "Config" && <ConfigView />}
      </main>
    </div>
  );
}
