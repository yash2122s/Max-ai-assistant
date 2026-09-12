import { useState } from "react";
import { Header } from "@/components/layout/Header";
import { CommandBar } from "@/components/layout/CommandBar";
import { ChatTranscript } from "@/components/max/ChatTranscript";
import { ActivityFeed } from "@/components/activity/ActivityFeed";
import { AgentStatusCard, DeviceFleet } from "@/components/devices/DeviceFleet";
import { SystemPanel } from "@/components/system/SystemPanel";
import { TaskPanel } from "@/components/tasks/TaskPanel";
import { ContextPanel } from "@/components/context/ContextPanel";
import { ToolsPanel } from "@/components/context/ToolsPanel";
import { HolographicHeroCenter } from "@/components/max/HolographicHeroCenter";
import { MaxComposePreviewGallery } from "@/components/preview/MaxComposePreviewGallery";

export default function App() {
  const [activeTab, setActiveTab] = useState<"telemetry" | "compose_preview">("telemetry");
  const [centerView, setCenterView] = useState<"hologram" | "chat" | "split">("split");

  return (
    <div className="flex h-screen flex-col overflow-hidden bg-[#080B10]">
      <div className="flex items-center justify-between border-b border-[#1F2937] bg-[#0F141C] px-3 py-1.5">
        <div className="flex items-center gap-2">
          <button
            onClick={() => setActiveTab("telemetry")}
            className={`px-3 py-1 text-xs rounded-md font-mono transition-colors ${activeTab === "telemetry" ? "bg-[#00E5FF]/20 text-[#00E5FF] font-bold border border-[#00E5FF]/40" : "text-[#94A3B8] hover:text-white"}`}
          >
            SYSTEM TELEMETRY
          </button>
          <button
            onClick={() => setActiveTab("compose_preview")}
            className={`px-3 py-1 text-xs rounded-md font-mono transition-colors ${activeTab === "compose_preview" ? "bg-[#00E5FF]/20 text-[#00E5FF] font-bold border border-[#00E5FF]/40" : "text-[#94A3B8] hover:text-white"}`}
          >
            COMPOSE UI PREVIEW (DEV)
          </button>
        </div>
      </div>

      {activeTab === "compose_preview" ? (
        <div className="flex-1 min-h-0 overflow-hidden">
          <MaxComposePreviewGallery />
        </div>
      ) : (
        <>
          <Header />

          <div className="grid min-h-0 flex-1 grid-cols-1 gap-2 p-2 md:grid-cols-[300px_minmax(0,1fr)] xl:grid-cols-[300px_minmax(0,1fr)_320px]">
        {/* Left rail: agent status + device fleet + system */}
        <aside className="flex min-h-0 flex-col gap-2">
          <AgentStatusCard />
          <DeviceFleet />
          <SystemPanel />
        </aside>

        {/* Center: 3D Holographic AI Neural Core + conversation + live activity */}
        <main className="glass-panel flex min-h-0 flex-1 flex-col gap-2 p-2 overflow-hidden" aria-label="MAX activity">
          {centerView === "hologram" && (
            <div className="flex min-h-0 flex-1 flex-col">
              <HolographicHeroCenter activeView={centerView} onToggleView={setCenterView} />
            </div>
          )}

          {centerView === "split" && (
            <div className="flex min-h-0 flex-1 flex-col gap-2">
              <div className="h-[280px] shrink-0">
                <HolographicHeroCenter activeView={centerView} onToggleView={setCenterView} />
              </div>
              <div className="flex min-h-0 flex-1 flex-col rounded-xl border border-line bg-surface/60 overflow-hidden">
                <ChatTranscript />
              </div>
            </div>
          )}

          {centerView === "chat" && (
            <div className="flex min-h-0 flex-1 flex-col rounded-xl border border-line bg-surface/60 overflow-hidden">
              <div className="flex items-center justify-between border-b border-line px-3 py-1.5 bg-surface-raised/40">
                <span className="font-mono text-[11px] font-semibold text-ink">Conversation Transcript</span>
                <button
                  type="button"
                  onClick={() => setCenterView("split")}
                  className="font-mono text-[10px] text-accent hover:underline"
                >
                  Show 3D Core
                </button>
              </div>
              <ChatTranscript />
            </div>
          )}

          {/* Activity Feed at bottom of center panel */}
          <div className="h-[150px] shrink-0 rounded-xl border border-line bg-surface/40 p-2">
            <ActivityFeed />
          </div>
        </main>


        {/* Right rail: task engine + context + tool registry */}
        <aside className="hidden min-h-0 flex-col gap-2 overflow-y-auto scroll-thin xl:flex">
          <TaskPanel />
          <ContextPanel />
          <ToolsPanel />
        </aside>
      </div>

      <CommandBar />
    </>
    )}
  </div>
  );
}