"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { createOrbScene, type OrbSceneApi } from "@/lib/orbScene";
import { HandTracker, type TrackerStatus } from "@/lib/handTracker";

type CameraState = "off" | "starting" | "on" | "error";

const MODE_LABEL: Record<TrackerStatus["mode"], string> = {
  idle: "STANDBY",
  spin: "SPIN",
  zoom: "ZOOM",
};

export default function JarvisOrb() {
  const containerRef = useRef<HTMLDivElement>(null);
  const videoRef = useRef<HTMLVideoElement>(null);
  const overlayRef = useRef<HTMLCanvasElement>(null);
  const sceneRef = useRef<OrbSceneApi | null>(null);
  const trackerRef = useRef<HandTracker | null>(null);
  const wsRef = useRef<WebSocket | null>(null);

  const [camera, setCamera] = useState<CameraState>("off");
  const [status, setStatus] = useState<TrackerStatus>({ hands: 0, mode: "idle" });
  const [error, setError] = useState<string | null>(null);

  // Telemetry & Agent state
  const [agentState, setAgentState] = useState<string>("INITIALIZING");
  const [voiceState, setVoiceState] = useState<string>("WAKE_WORD_LISTENING");
  const [coreStability, setCoreStability] = useState<number>(99.4);
  const [cpuUsage, setCpuUsage] = useState<number>(18);
  const [memUsage, setMemUsage] = useState<number>(44);
  const [showTelemetry, setShowTelemetry] = useState<boolean>(true);
  const [autoSpin, setAutoSpin] = useState<boolean>(true);
  const [timeStr, setTimeStr] = useState<string>("");

  // Terminal & transcript
  const [inputText, setInputText] = useState<string>("");
  const [lastMessage, setLastMessage] = useState<string>("JARVIS Holographic Interface active. Say 'Hey Max' or enter a command below.");

  // Clock
  useEffect(() => {
    const updateTime = () => {
      const now = new Date();
      setTimeStr(now.toTimeString().split(" ")[0]);
    };
    updateTime();
    const timer = setInterval(updateTime, 1000);
    return () => clearInterval(timer);
  }, []);

  // Periodic Telemetry Simulation / Fallback
  useEffect(() => {
    const interval = setInterval(() => {
      setCpuUsage(Math.floor(14 + Math.random() * 12));
      setMemUsage(Math.floor(42 + Math.random() * 5));
      setCoreStability(Number((99.1 + Math.random() * 0.7).toFixed(1)));
    }, 2500);
    return () => clearInterval(interval);
  }, []);

  // WebSocket Connection to Windows Agent (WS: 9000)
  useEffect(() => {
    let ws: WebSocket | null = null;
    let timer: ReturnType<typeof setTimeout> | null = null;

    const connect = () => {
      try {
        ws = new WebSocket("ws://localhost:9000");
        wsRef.current = ws;

        ws.onopen = () => {
          setAgentState("ONLINE");
          setVoiceState("WAKE_WORD_LISTENING");
          sceneRef.current?.setAudioPulse(0.8);
        };

        ws.onmessage = (event) => {
          try {
            const data = JSON.parse(event.data);
            if (data.type === "agent_state" || data.type === "voice_state") {
              const state = (data.state || data.voice_state || "").toUpperCase();
              setVoiceState(state || "ONLINE");
              if (state.includes("SPEAK") || state.includes("LIVE")) {
                sceneRef.current?.setAudioPulse(0.95);
              }
            } else if (data.type === "task_event" || data.type === "response") {
              const text = data.text || data.summary || data.message;
              if (text) {
                setLastMessage(text);
                sceneRef.current?.setAudioPulse(0.9);
              }
            }
          } catch {}
        };

        ws.onclose = () => {
          setAgentState("STANDBY");
          timer = setTimeout(connect, 3000);
        };

        ws.onerror = () => {
          setAgentState("STANDBY");
        };
      } catch {
        setAgentState("STANDBY");
        timer = setTimeout(connect, 3000);
      }
    };

    connect();
    return () => {
      if (ws) ws.close();
      if (timer) clearTimeout(timer);
    };
  }, []);

  // Initialize Three.js 3D Scene
  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;
    const scene = createOrbScene(container);
    sceneRef.current = scene;
    return () => {
      trackerRef.current?.stop();
      trackerRef.current = null;
      scene.dispose();
      sceneRef.current = null;
    };
  }, []);

  // Hand Gestures Management
  const stopGestures = useCallback(() => {
    trackerRef.current?.stop();
    trackerRef.current = null;
    setCamera("off");
    setStatus({ hands: 0, mode: "idle" });
  }, []);

  const startGestures = useCallback(async () => {
    const video = videoRef.current;
    const overlay = overlayRef.current;
    if (!video || !overlay || trackerRef.current) return;

    setCamera("starting");
    setError(null);

    const tracker = new HandTracker(video, overlay, {
      onRotate: (dt, dp) => sceneRef.current?.rotateBy(dt, dp),
      onZoom: (factor) => sceneRef.current?.zoomBy(factor),
      onStatus: setStatus,
    });
    trackerRef.current = tracker;

    try {
      await tracker.start();
      setCamera("on");
      sceneRef.current?.setAudioPulse(0.7);
    } catch (err) {
      trackerRef.current = null;
      tracker.stop();
      setCamera("error");
      setError(
        err instanceof DOMException && err.name === "NotAllowedError"
          ? "CAMERA ACCESS DENIED"
          : "TRACKING INIT FAILED",
      );
    }
  }, []);

  const toggleGestures = useCallback(() => {
    if (trackerRef.current) stopGestures();
    else void startGestures();
  }, [startGestures, stopGestures]);

  // Keyboard Shortcuts
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      // Don't trigger shortcuts if typing inside the input field
      if (e.target instanceof HTMLInputElement) return;

      switch (e.key) {
        case "+":
        case "=":
          sceneRef.current?.zoomIn();
          break;
        case "-":
        case "_":
          sceneRef.current?.zoomOut();
          break;
        case "r":
        case "R":
          sceneRef.current?.resetView();
          break;
        case "g":
        case "G":
          toggleGestures();
          break;
        case "p":
        case "P":
          sceneRef.current?.setAudioPulse(1.0);
          break;
        case "t":
        case "T":
          setShowTelemetry((prev) => !prev);
          break;
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [toggleGestures]);

  // Handle Command Submission
  const handleSendCommand = (e?: React.FormEvent) => {
    if (e) e.preventDefault();
    const query = inputText.trim();
    if (!query) return;

    setLastMessage(`Command dispatched: "${query}"`);
    sceneRef.current?.setAudioPulse(1.0);

    // Send to agent WebSocket if connected
    if (wsRef.current && wsRef.current.readyState === WebSocket.OPEN) {
      wsRef.current.send(JSON.stringify({ type: "user_command", text: query }));
    } else {
      // Direct HTTP API fallback
      fetch("http://localhost:9001/api/chat", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ message: query }),
      })
        .then((res) => res.json())
        .then((data) => {
          if (data?.response) setLastMessage(data.response);
        })
        .catch(() => {});
    }

    setInputText("");
  };

  const cameraOn = camera === "on";

  return (
    <>
      {/* 3D WebGL Canvas */}
      <div ref={containerRef} className="orb-root" />

      {/* Holographic atmospheric overlays */}
      <div className="overlay-vignette" />
      <div className="overlay-grid" />
      <div className="overlay-grain" />
      <div className="overlay-scanlines" />

      {/* Sci-Fi Holographic Corner Reticles */}
      <div className="hud-corner top-left">
        <span className="corner-tag">MK-LIII // SYS: {agentState}</span>
        <span>GATE: {voiceState}</span>
      </div>

      <div className="hud-corner top-right">
        <span className="corner-tag">REACTOR FLUX: {coreStability}%</span>
        <span>TIME: {timeStr || "ACTIVE"}</span>
      </div>

      <div className="hud-corner bottom-left">
        <span className="corner-tag">AUDIO DUPLEX // 16.0 kHz</span>
        <span>VOSK & GEMINI LIVE LINKED</span>
      </div>

      <div className="hud-corner bottom-right">
        <span className="corner-tag">GESTURE HUD // 360° SENSING</span>
        <span>STATUS: {cameraOn ? "TRACKING ACTIVE" : "STANDBY"}</span>
      </div>

      {/* Top Title & Status Banner */}
      <div className="hud hud-title">
        <div className="hud-title-line">
          <span>M.A.X. · J.A.R.V.I.S.</span>
          <div className="hud-title-badge">
            <span className="pulse-dot" />
            <span>{agentState}</span>
          </div>
        </div>
      </div>

      {/* Left Telemetry & Equalizer Panel */}
      {showTelemetry && (
        <div className="hud hud-telemetry">
          <div className="telemetry-header">
            <span>NEURAL TELEMETRY</span>
            <span style={{ color: "#38bdf8", fontSize: "9px" }}>[LIVE]</span>
          </div>

          <div className="metric-row">
            <span className="metric-label">CORE FREQUENCY</span>
            <span className="metric-val">{coreStability}%</span>
          </div>
          <div className="metric-bar-bg">
            <div className="metric-bar-fill" style={{ width: `${coreStability}%` }} />
          </div>

          <div className="metric-row">
            <span className="metric-label">PROCESSOR LOAD</span>
            <span className="metric-val">{cpuUsage}%</span>
          </div>
          <div className="metric-bar-bg">
            <div className="metric-bar-fill" style={{ width: `${cpuUsage}%` }} />
          </div>

          <div className="metric-row">
            <span className="metric-label">SHARED MEMORY</span>
            <span className="metric-val">{memUsage}%</span>
          </div>
          <div className="metric-bar-bg">
            <div className="metric-bar-fill" style={{ width: `${memUsage}%` }} />
          </div>

          <div style={{ marginTop: "4px" }}>
            <div className="metric-row">
              <span className="metric-label">AUDIO WAVEFORM</span>
              <span className="metric-val" style={{ color: "#38bdf8" }}>FULL DUPLEX</span>
            </div>
            <div className="eq-container">
              {[0.4, 0.7, 0.9, 0.6, 0.8, 1.0, 0.75, 0.85, 0.5, 0.65, 0.9, 0.45].map((val, idx) => (
                <div
                  key={idx}
                  className="eq-bar"
                  style={{
                    animationDelay: `${idx * 0.08}s`,
                    animationDuration: `${0.8 + (idx % 4) * 0.2}s`,
                    height: `${val * 100}%`,
                  }}
                />
              ))}
            </div>
          </div>
        </div>
      )}

      {/* Bottom Interactive Command / Transcript Console */}
      <div className="hud hud-bottom-bar">
        {lastMessage && (
          <div className="hud-transcript-card">
            <span style={{ color: "#7dd3fc", fontWeight: "bold" }}>✨ MAX: </span>
            {lastMessage}
          </div>
        )}

        <form onSubmit={handleSendCommand} className="hud-input-row">
          <input
            type="text"
            className="hud-input"
            value={inputText}
            onChange={(e) => setInputText(e.target.value)}
            placeholder="Type query or command for MAX (e.g. open notepad, run status)..."
          />
          <button type="submit" className="hud-send-btn">
            TRANSMIT
          </button>
        </form>
      </div>

      {/* Keyboard Controls Hint */}
      <div className="hud hud-hint">
        <div>
          <span className="key">DRAG</span> rotate&nbsp;&nbsp;
          <span className="key">SCROLL</span> zoom
        </div>
        {cameraOn ? (
          <div>
            <span className="key">PINCH + MOVE</span> rotate&nbsp;&nbsp;
            <span className="key">PINCH BOTH ± SPREAD</span> zoom
          </div>
        ) : (
          <div>
            <span className="key">G</span> gestures&nbsp;&nbsp;
            <span className="key">P</span> pulse&nbsp;&nbsp;
            <span className="key">T</span> telemetry&nbsp;&nbsp;
            <span className="key">R</span> reset
          </div>
        )}
      </div>

      {/* Right Controls Deck */}
      <div className="hud hud-controls">
        <div className={`camera-panel${cameraOn ? " visible" : ""}`}>
          <video ref={videoRef} muted playsInline className="camera-video" />
          <canvas ref={overlayRef} width={208} height={156} className="camera-overlay" />
          <div className="camera-status">
            {status.hands > 0
              ? `${status.hands} HAND${status.hands > 1 ? "S" : ""} · ${MODE_LABEL[status.mode]}`
              : "SHOW HANDS"}
          </div>
        </div>

        {error && <div className="hud-error">{error}</div>}

        <div className="hud-row">
          <button
            type="button"
            className={`hud-btn${cameraOn ? " active" : ""}`}
            aria-pressed={cameraOn}
            onClick={toggleGestures}
            disabled={camera === "starting"}
          >
            {camera === "starting" ? "INITIALIZING…" : cameraOn ? "GESTURES ON" : "GESTURES OFF"}
          </button>
        </div>

        <div className="hud-row">
          <button
            type="button"
            className="hud-btn"
            onClick={() => {
              const res = sceneRef.current?.toggleAutoSpin();
              setAutoSpin(res ?? true);
            }}
          >
            {autoSpin ? "SPIN: ON" : "SPIN: OFF"}
          </button>
          <button
            type="button"
            className="hud-btn"
            onClick={() => sceneRef.current?.setAudioPulse(1.0)}
          >
            PULSE
          </button>
          <button
            type="button"
            className={`hud-btn${showTelemetry ? " active" : ""}`}
            onClick={() => setShowTelemetry((prev) => !prev)}
          >
            STATS
          </button>
        </div>

        <div className="hud-row">
          <button
            type="button"
            className="hud-btn"
            onClick={() => sceneRef.current?.zoomIn()}
            aria-label="Zoom in"
          >
            +
          </button>
          <button
            type="button"
            className="hud-btn"
            onClick={() => sceneRef.current?.zoomOut()}
            aria-label="Zoom out"
          >
            −
          </button>
          <button
            type="button"
            className="hud-btn"
            onClick={() => sceneRef.current?.resetView()}
          >
            RESET
          </button>
        </div>
      </div>
    </>
  );
}
