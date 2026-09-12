import React, { useState, useEffect, useRef } from "react";

export function MaxComposePreviewGallery() {
  const [selectedTab, setSelectedTab] = useState<"atoms" | "orb" | "cards" | "home_mockup">("orb");
  const [orbState, setOrbState] = useState<"ready" | "listening" | "speaking" | "thinking" | "off">("ready");
  const [audioRms, setAudioRms] = useState(0.4);
  const [sampleText, setSampleText] = useState("Explain quantum computing in simple Telugu");
  const [isMaxEnabled, setIsMaxEnabled] = useState(true);

  // Simulated RMS pulsation for live demo
  useEffect(() => {
    if (orbState === "listening" || orbState === "speaking") {
      const interval = setInterval(() => {
        setAudioRms(0.2 + Math.random() * 0.7);
      }, 100);
      return () => clearInterval(interval);
    } else {
      setAudioRms(0);
    }
  }, [orbState]);

  return (
    <div className="flex flex-col h-full bg-[#080B10] text-[#E1E7EF] font-sans overflow-hidden">
      {/* Dev Preview Header */}
      <div className="flex items-center justify-between px-4 py-3 bg-[#0F141C] border-b border-[#1F2937]">
        <div className="flex items-center gap-3">
          <div className="w-2.5 h-2.5 rounded-full bg-[#00E5FF] shadow-[0_0_10px_#00E5FF]"></div>
          <div>
            <span className="font-mono text-xs font-bold tracking-widest text-[#00E5FF]">MAX COMPOSE UI PREVIEW</span>
            <span className="ml-2 px-1.5 py-0.5 rounded text-[10px] bg-[#161D26] text-[#94A3B8] border border-[#232F3E]">DEV ONLY</span>
          </div>
        </div>

        {/* Tab Selector */}
        <div className="flex items-center gap-1 bg-[#080B10] p-1 rounded-lg border border-[#1F2937]">
          <button
            onClick={() => setSelectedTab("orb")}
            className={`px-3 py-1 text-xs rounded font-medium transition-colors ${selectedTab === "orb" ? "bg-[#00E5FF]/20 text-[#00E5FF] border border-[#00E5FF]/40" : "text-[#94A3B8] hover:text-white"}`}
          >
            Neural Orb
          </button>
          <button
            onClick={() => setSelectedTab("atoms")}
            className={`px-3 py-1 text-xs rounded font-medium transition-colors ${selectedTab === "atoms" ? "bg-[#00E5FF]/20 text-[#00E5FF] border border-[#00E5FF]/40" : "text-[#94A3B8] hover:text-white"}`}
          >
            Design Tokens & Atoms
          </button>
          <button
            onClick={() => setSelectedTab("cards")}
            className={`px-3 py-1 text-xs rounded font-medium transition-colors ${selectedTab === "cards" ? "bg-[#00E5FF]/20 text-[#00E5FF] border border-[#00E5FF]/40" : "text-[#94A3B8] hover:text-white"}`}
          >
            Cards & Widgets
          </button>
          <button
            onClick={() => setSelectedTab("home_mockup")}
            className={`px-3 py-1 text-xs rounded font-medium transition-colors ${selectedTab === "home_mockup" ? "bg-[#00E5FF]/20 text-[#00E5FF] border border-[#00E5FF]/40" : "text-[#94A3B8] hover:text-white"}`}
          >
            Home Layout Mockup
          </button>
        </div>
      </div>

      {/* Main Workspace */}
      <div className="flex-1 p-6 overflow-y-auto">
        {selectedTab === "orb" && (
          <div className="max-w-4xl mx-auto flex flex-col items-center gap-6">
            {/* Orb Canvas Container */}
            <div className="relative w-80 h-80 rounded-2xl bg-[#0F141C] border border-[#1F2937] flex flex-col items-center justify-center p-4 shadow-[0_0_50px_rgba(0,229,255,0.05)]">
              <OrbSimulator state={orbState} rms={audioRms} isEnabled={isMaxEnabled} />
              
              <div className="mt-4 text-center">
                <span className="font-mono text-xs font-semibold tracking-wider" style={{
                  color: !isMaxEnabled ? "#64748B" : orbState === "listening" ? "#00E5FF" : orbState === "speaking" ? "#6366F1" : orbState === "thinking" ? "#A855F7" : "#38BDF8"
                }}>
                  {!isMaxEnabled ? "MAX OFF" : orbState === "listening" ? "Listening..." : orbState === "speaking" ? "MAX Speaking..." : orbState === "thinking" ? "Processing..." : "Ready"}
                </span>
              </div>
            </div>

            {/* Interactive State Controls */}
            <div className="w-full max-w-lg bg-[#0F141C] p-4 rounded-xl border border-[#1F2937] flex flex-col gap-3">
              <span className="font-mono text-[11px] text-[#94A3B8] uppercase font-bold tracking-wider">Simulate Runtime State</span>
              <div className="grid grid-cols-5 gap-2">
                {(["ready", "listening", "speaking", "thinking", "off"] as const).map((st) => (
                  <button
                    key={st}
                    onClick={() => {
                      if (st === "off") {
                        setIsMaxEnabled(false);
                        setOrbState("off");
                      } else {
                        setIsMaxEnabled(true);
                        setOrbState(st);
                      }
                    }}
                    className={`px-2 py-2 text-xs rounded-lg font-medium capitalize border transition-all ${
                      (st === "off" && !isMaxEnabled) || (orbState === st && isMaxEnabled)
                        ? "bg-[#00E5FF]/20 border-[#00E5FF] text-[#00E5FF]"
                        : "bg-[#161D26] border-[#232F3E] text-[#94A3B8] hover:border-[#374151]"
                    }`}
                  >
                    {st}
                  </button>
                ))}
              </div>

              {(orbState === "listening" || orbState === "speaking") && isMaxEnabled && (
                <div className="mt-2 flex flex-col gap-1">
                  <div className="flex justify-between text-[11px] font-mono text-[#94A3B8]">
                    <span>Audio RMS Amplitude:</span>
                    <span>{(audioRms * 100).toFixed(0)}%</span>
                  </div>
                  <input
                    type="range"
                    min="0"
                    max="1"
                    step="0.01"
                    value={audioRms}
                    onChange={(e) => setAudioRms(parseFloat(e.target.value))}
                    className="w-full accent-[#00E5FF]"
                  />
                </div>
              )}
            </div>
          </div>
        )}

        {selectedTab === "atoms" && (
          <div className="max-w-4xl mx-auto grid grid-cols-1 md:grid-cols-2 gap-6">
            {/* Buttons */}
            <div className="bg-[#0F141C] p-5 rounded-xl border border-[#1F2937] flex flex-col gap-4">
              <span className="font-mono text-xs font-bold text-[#00E5FF] tracking-wider">MAX BUTTONS (MaxButton)</span>
              <div className="flex flex-wrap gap-2">
                <button className="px-4 py-2 rounded-xl bg-gradient-to-r from-[#00E5FF] to-[#38BDF8] text-[#080B10] font-bold text-sm shadow-[0_0_15px_rgba(0,229,255,0.3)] hover:opacity-90">
                  Primary Action
                </button>
                <button className="px-4 py-2 rounded-xl bg-gradient-to-r from-[#6366F1] to-[#A855F7] text-white font-semibold text-sm hover:opacity-90">
                  Secondary Action
                </button>
                <button className="px-4 py-2 rounded-xl border border-[#00E5FF]/40 text-[#00E5FF] font-semibold text-sm hover:bg-[#00E5FF]/10">
                  Outline Button
                </button>
                <button className="px-4 py-2 rounded-xl bg-[#EF4444]/20 border border-[#EF4444]/40 text-[#EF4444] font-semibold text-sm hover:bg-[#EF4444]/30">
                  Danger Action
                </button>
              </div>
            </div>

            {/* Badges & Status */}
            <div className="bg-[#0F141C] p-5 rounded-xl border border-[#1F2937] flex flex-col gap-4">
              <span className="font-mono text-xs font-bold text-[#00E5FF] tracking-wider">STATUS BADGES (MaxStatusBadge)</span>
              <div className="flex flex-wrap gap-2 items-center">
                <span className="px-2.5 py-1 rounded-full text-xs font-bold bg-[#10B981]/15 text-[#10B981] border border-[#10B981]/30">
                  CONNECTED
                </span>
                <span className="px-2.5 py-1 rounded-full text-xs font-bold bg-[#F59E0B]/15 text-[#F59E0B] border border-[#F59E0B]/30">
                  CONNECTING...
                </span>
                <span className="px-2.5 py-1 rounded-full text-xs font-bold bg-[#EF4444]/15 text-[#EF4444] border border-[#EF4444]/30">
                  DISCONNECTED
                </span>
                <span className="px-2.5 py-1 rounded-full text-xs font-bold bg-[#6366F1]/15 text-[#6366F1] border border-[#6366F1]/30">
                  OPTIMAL
                </span>
              </div>
            </div>

            {/* Input Fields */}
            <div className="bg-[#0F141C] p-5 rounded-xl border border-[#1F2937] flex flex-col gap-4 md:col-span-2">
              <span className="font-mono text-xs font-bold text-[#00E5FF] tracking-wider">TEXT FIELDS (MaxTextField)</span>
              <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                <div>
                  <label className="text-xs font-semibold text-[#94A3B8] mb-1 block">Single Line Input</label>
                  <input
                    type="text"
                    value={sampleText}
                    onChange={(e) => setSampleText(e.target.value)}
                    placeholder="Enter prompt..."
                    className="w-full px-3.5 py-2.5 rounded-xl bg-[#161D26] border border-[#232F3E] text-white text-sm focus:outline-none focus:border-[#00E5FF]"
                  />
                </div>
                <div>
                  <label className="text-xs font-semibold text-[#94A3B8] mb-1 block">Multiline Input (Steps / Code / Notes)</label>
                  <textarea
                    rows={3}
                    defaultValue={'[{"action": "SET_DND", "arguments": {"enabled": true}}]'}
                    className="w-full px-3.5 py-2.5 rounded-xl bg-[#161D26] border border-[#232F3E] text-[#00E5FF] font-mono text-xs focus:outline-none focus:border-[#00E5FF]"
                  />
                </div>
              </div>
            </div>
          </div>
        )}

        {selectedTab === "cards" && (
          <div className="max-w-4xl mx-auto grid grid-cols-1 md:grid-cols-2 gap-6">
            {/* Quick Action Card */}
            <div className="bg-[#0F141C] p-4 rounded-2xl border border-[#1F2937] flex flex-col gap-3">
              <div className="flex items-center justify-between">
                <span className="text-xs font-mono text-[#00E5FF] font-bold">QUICK ACTION CARDS</span>
                <span className="text-[10px] text-[#94A3B8]">HomeScreen Quick Bar</span>
              </div>
              <div className="grid grid-cols-2 gap-2">
                <div className="p-3 rounded-xl bg-[#161D26] border border-[#232F3E] flex items-center gap-3 cursor-pointer hover:border-[#00E5FF]/50 transition-colors">
                  <div className="w-8 h-8 rounded-lg bg-[#00E5FF]/10 flex items-center justify-center text-[#00E5FF]">⚡</div>
                  <div>
                    <div className="text-xs font-bold text-white">Flashlight</div>
                    <div className="text-[10px] text-[#94A3B8]">Toggle LED</div>
                  </div>
                </div>
                <div className="p-3 rounded-xl bg-[#161D26] border border-[#232F3E] flex items-center gap-3 cursor-pointer hover:border-[#00E5FF]/50 transition-colors">
                  <div className="w-8 h-8 rounded-lg bg-[#6366F1]/10 flex items-center justify-center text-[#6366F1]">📡</div>
                  <div>
                    <div className="text-xs font-bold text-white">Wi-Fi</div>
                    <div className="text-[10px] text-[#94A3B8]">Open Settings</div>
                  </div>
                </div>
              </div>
            </div>

            {/* Memory Item Card */}
            <div className="bg-[#0F141C] p-4 rounded-2xl border border-[#1F2937] flex flex-col gap-3">
              <div className="flex items-center justify-between">
                <span className="text-xs font-mono text-[#00E5FF] font-bold">MEMORY CARD (MemoryItemCard)</span>
                <span className="text-[10px] text-[#94A3B8]">Memory Wallet</span>
              </div>
              <div className="p-4 rounded-xl bg-[#161D26] border border-[#232F3E] flex flex-col gap-2">
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <span className="text-sm font-bold text-white">Favorite Coffee</span>
                    <span className="px-1.5 py-0.5 rounded text-[9px] font-bold bg-[#00E5FF]/10 text-[#00E5FF]">PREFERENCES</span>
                  </div>
                  <div className="flex items-center gap-1 text-xs text-[#00E5FF]">
                    📌 <span className="text-[10px] text-[#94A3B8]">Pinned</span>
                  </div>
                </div>
                <p className="text-xs text-[#94A3B8] leading-relaxed">
                  Loves double shot espresso with oat milk in the morning. Never add sugar.
                </p>
                <div className="flex flex-wrap gap-1.5 mt-1">
                  <span className="px-2 py-0.5 rounded text-[10px] bg-[#0F141C] text-[#38BDF8] border border-[#232F3E]">#coffee</span>
                  <span className="px-2 py-0.5 rounded text-[10px] bg-[#0F141C] text-[#38BDF8] border border-[#232F3E]">#morning</span>
                  <span className="px-2 py-0.5 rounded text-[10px] bg-[#0F141C] text-[#38BDF8] border border-[#232F3E]">#espresso</span>
                </div>
              </div>
            </div>
          </div>
        )}

        {selectedTab === "home_mockup" && (
          <div className="max-w-sm mx-auto bg-[#080B10] rounded-3xl border border-[#1F2937] overflow-hidden shadow-2xl flex flex-col h-[560px]">
            {/* App TopBar */}
            <div className="px-4 py-3 bg-[#0F141C] border-b border-[#1F2937] flex items-center justify-between">
              <div className="flex items-center gap-2">
                <span className="font-black text-sm text-[#00E5FF] tracking-widest">MAX</span>
                <span className="px-1.5 py-0.5 rounded-full text-[9px] font-bold bg-[#10B981]/15 text-[#10B981] border border-[#10B981]/30">CONNECTED</span>
              </div>
              <div className="flex items-center gap-2">
                <button
                  onClick={() => setIsMaxEnabled(!isMaxEnabled)}
                  className={`w-7 h-4 rounded-full transition-colors flex items-center p-0.5 ${isMaxEnabled ? "bg-[#00E5FF]" : "bg-[#374151]"}`}
                >
                  <div className={`w-3 h-3 rounded-full bg-[#080B10] transition-transform ${isMaxEnabled ? "translate-x-3" : "translate-x-0"}`}></div>
                </button>
              </div>
            </div>

            {/* Chat Feed */}
            <div className="flex-1 p-3 overflow-y-auto flex flex-col gap-3">
              {/* AI Bubble */}
              <div className="flex gap-2 max-w-[85%]">
                <div className="w-6 h-6 rounded-full bg-[#00E5FF]/20 text-[#00E5FF] font-bold text-[10px] flex items-center justify-center shrink-0">M</div>
                <div className="p-2.5 rounded-2xl rounded-tl-none bg-[#0F141C] border border-[#1F2937] text-xs text-[#E1E7EF] leading-relaxed">
                  Namaskaram! How can I assist you with your phone or tasks today?
                </div>
              </div>

              {/* User Bubble */}
              <div className="self-end max-w-[85%]">
                <div className="p-2.5 rounded-2xl rounded-tr-none bg-gradient-to-r from-[#00E5FF] to-[#38BDF8] text-[#080B10] text-xs font-medium leading-relaxed">
                  Turn on flashlight and remind me at 8 PM.
                </div>
              </div>
            </div>

            {/* Composer */}
            <div className="p-3 bg-[#0F141C] border-t border-[#1F2937] flex items-center gap-2">
              <input
                type="text"
                placeholder="Ask MAX or command phone..."
                className="flex-1 px-3 py-2 rounded-xl bg-[#161D26] border border-[#232F3E] text-xs text-white focus:outline-none"
              />
              <button className="w-8 h-8 rounded-full bg-[#00E5FF] text-[#080B10] font-bold text-xs flex items-center justify-center">
                🎙️
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

// Canvas simulation of the Jetpack Compose JarvisNeuralOrb
function OrbSimulator({ state, rms, isEnabled }: { state: string; rms: number; isEnabled: boolean }) {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext("2d");
    if (!ctx) return;

    let animId: number;
    let angle1 = 0;
    let angle2 = 0;
    let angle3 = 360;

    const render = () => {
      ctx.clearRect(0, 0, canvas.width, canvas.height);
      const cx = canvas.width / 2;
      const cy = canvas.height / 2;
      const baseRadius = 45;

      if (!isEnabled) {
        // Dormant static core
        ctx.beginPath();
        ctx.arc(cx, cy, baseRadius * 0.8, 0, Math.PI * 2);
        ctx.fillStyle = "#1E293B";
        ctx.fill();
        ctx.strokeStyle = "#334155";
        ctx.lineWidth = 1.5;
        ctx.stroke();
        return;
      }

      const activeColor = state === "listening" ? "#00E5FF" : state === "speaking" ? "#6366F1" : state === "thinking" ? "#A855F7" : "#38BDF8";
      const scale = 1 + (rms * 0.35);

      // Aura
      const grad = ctx.createRadialGradient(cx, cy, 0, cx, cy, baseRadius * 2.2 * scale);
      grad.addColorStop(0, `${activeColor}33`);
      grad.addColorStop(1, "transparent");
      ctx.fillStyle = grad;
      ctx.beginPath();
      ctx.arc(cx, cy, baseRadius * 2.2 * scale, 0, Math.PI * 2);
      ctx.fill();

      // Orbits
      angle1 = (angle1 + 2) % 360;
      angle2 = (angle2 + 1.5) % 360;
      angle3 = (angle3 - 2.5 + 360) % 360;

      const drawOrbit = (angleDeg: number, rx: number, ry: number, color: string) => {
        ctx.beginPath();
        ctx.ellipse(cx, cy, rx * scale, ry * scale, 0, 0, Math.PI * 2);
        ctx.strokeStyle = `${color}66`;
        ctx.lineWidth = 1.2;
        ctx.stroke();

        const rad = (angleDeg * Math.PI) / 180;
        const px = cx + rx * scale * Math.cos(rad);
        const py = cy + ry * scale * Math.sin(rad);

        ctx.beginPath();
        ctx.arc(px, py, 4 + rms * 3, 0, Math.PI * 2);
        ctx.fillStyle = color;
        ctx.shadowColor = color;
        ctx.shadowBlur = 8;
        ctx.fill();
        ctx.shadowBlur = 0;
      };

      drawOrbit(angle1, baseRadius * 1.5, baseRadius * 0.7, activeColor);
      drawOrbit(angle2, baseRadius * 1.3, baseRadius * 1.1, "#38BDF8");
      drawOrbit(angle3, baseRadius * 1.4, baseRadius * 0.5, "#EC4899");

      // Core
      const coreGrad = ctx.createRadialGradient(cx, cy - 8, 2, cx, cy, baseRadius * scale);
      coreGrad.addColorStop(0, "#FFFFFF");
      coreGrad.addColorStop(0.3, activeColor);
      coreGrad.addColorStop(1, `${activeColor}22`);
      ctx.fillStyle = coreGrad;
      ctx.beginPath();
      ctx.arc(cx, cy, baseRadius * scale, 0, Math.PI * 2);
      ctx.fill();

      animId = requestAnimationFrame(render);
    };

    render();
    return () => cancelAnimationFrame(animId);
  }, [state, rms, isEnabled]);

  return <canvas ref={canvasRef} width={240} height={240} className="w-60 h-60" />;
}
