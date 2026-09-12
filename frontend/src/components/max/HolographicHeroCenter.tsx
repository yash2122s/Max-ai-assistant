import { useEffect, useRef, useState } from "react";
import * as THREE from "three";
import {
  Mic,
  MicOff,
  Maximize2,
  Minimize2,
  Sparkles,
  Volume2,
  Activity,
  Eye,
  MessageSquare,
  Zap,
  Radio,
  Cpu,
  Globe,
  Heart,
} from "lucide-react";
import { useAgentStore } from "@/store/useAgentStore";
import { cn } from "@/lib/cn";

export type CoreColorTheme = "electric_cyan" | "stark_gold" | "quantum_violet" | "matrix_emerald";
export type HologramModel = "neural_sphere" | "neural_brain" | "cyber_synapse" | "quantum_core";

const THEME_PALETTES: Record<
  CoreColorTheme,
  {
    name: string;
    core: number;
    accent: number;
    particle: number;
    lightning: number;
    glow: string;
    textTone: string;
  }
> = {
  electric_cyan: {
    name: "Electric Cyan (Neural)",
    core: 0x00d9ff,
    accent: 0x0077ff,
    particle: 0x99f0ff,
    lightning: 0xffffff,
    glow: "rgba(0, 217, 255, 0.5)",
    textTone: "text-cyan-400",
  },
  stark_gold: {
    name: "Stark Gold",
    core: 0xffaa00,
    accent: 0xff6600,
    particle: 0xffe680,
    lightning: 0xfff2b3,
    glow: "rgba(255, 170, 0, 0.5)",
    textTone: "text-amber-400",
  },
  quantum_violet: {
    name: "Quantum Violet",
    core: 0xbf5af2,
    accent: 0x8e44ad,
    particle: 0xebb9ff,
    lightning: 0xffd9ff,
    glow: "rgba(191, 90, 242, 0.5)",
    textTone: "text-purple-400",
  },
  matrix_emerald: {
    name: "Matrix Emerald",
    core: 0x10b981,
    accent: 0x059669,
    particle: 0x86efac,
    lightning: 0xecfdf5,
    glow: "rgba(16, 185, 129, 0.5)",
    textTone: "text-emerald-400",
  },
};

interface HolographicHeroCenterProps {
  onToggleView?: (view: "hologram" | "chat" | "split") => void;
  activeView?: "hologram" | "chat" | "split";
}

/**
 * Generates an ethereal multi-layer 3D Neural Sphere with distinct spatial gaps.
 * Uses structured concentric spherical shells (Nucleus, Middle Lattice, Outer Orbitals)
 * separated by luminous negative space / voids.
 */
function generateNeuralSpherePoints(count: number): {
  positions: Float32Array;
  basePositions: Float32Array;
  normals: Float32Array;
  hubs: Float32Array;
  layers: Float32Array;
} {
  const positions = new Float32Array(count * 3);
  const basePositions = new Float32Array(count * 3);
  const normals = new Float32Array(count * 3);
  const hubs = new Float32Array(count); // 1 = major synaptic hub, 0 = standard
  const layers = new Float32Array(count); // 0 = nucleus, 1 = cortex, 2 = mantle

  const goldenRatio = (1 + Math.sqrt(5)) / 2;

  for (let i = 0; i < count; i++) {
    // Fibonacci spherical distribution for uniform surface coverage
    const theta = (2 * Math.PI * i) / goldenRatio;
    const y = 1 - (i / (count - 1)) * 2; // -1 to 1
    const radiusAtY = Math.sqrt(Math.max(0, 1 - y * y));
    const x = Math.cos(theta) * radiusAtY;
    const z = Math.sin(theta) * radiusAtY;

    // Layer assignment with distinct spatial voids (gaps) between shells:
    // 12% Dense Inner Nucleus (r: 0.42 - 0.58)
    // [WIDE GAP: 0.58 -> 1.20]
    // 68% Middle Synaptic Mesh (r: 1.25 - 1.48)
    // [WIDE GAP: 1.48 -> 1.95]
    // 20% Outer Axonal Halo (r: 2.00 - 2.35)
    const rand = Math.random();
    let baseR = 1.32;
    let layerType = 1;

    if (rand < 0.12) {
      baseR = 0.42 + Math.random() * 0.16;
      layerType = 0;
    } else if (rand > 0.80) {
      baseR = 2.00 + Math.random() * 0.35;
      layerType = 2;
    } else {
      baseR = 1.25 + Math.random() * 0.22;
      layerType = 1;
    }

    // Subtle harmonic surface ripples (keeps geometric clarity and open gaps)
    const fold =
      Math.sin(x * 4 + y * 4) * Math.cos(z * 4) * 0.05 +
      Math.sin(theta * 5) * 0.03;

    const r = baseR + fold;
    const px = x * r;
    const py = y * r;
    const pz = z * r;

    positions[i * 3] = px;
    positions[i * 3 + 1] = py;
    positions[i * 3 + 2] = pz;

    basePositions[i * 3] = px;
    basePositions[i * 3 + 1] = py;
    basePositions[i * 3 + 2] = pz;

    // Outward normal vector
    const norm = Math.hypot(px, py, pz) || 1;
    normals[i * 3] = px / norm;
    normals[i * 3 + 1] = py / norm;
    normals[i * 3 + 2] = pz / norm;

    layers[i] = layerType;
    // 4% nodes are prominent high-energy synaptic hubs
    hubs[i] = Math.random() < 0.04 ? 1 : 0;
  }

  return { positions, basePositions, normals, hubs, layers };
}

/**
 * Builds airy axonal synaptic connection lines with clean geometric gaps between connections.
 */
function generateSpherePlexus(
  positions: Float32Array,
  layers: Float32Array,
  count: number,
  maxDist = 0.42,
  minDist = 0.14,
  maxConn = 3,
): THREE.Vector3[] {
  const linePoints: THREE.Vector3[] = [];
  const maxDistSq = maxDist * maxDist;
  const minDistSq = minDist * minDist;

  for (let i = 0; i < count; i += 2) {
    let conn = 0;
    const p1 = new THREE.Vector3(positions[i * 3], positions[i * 3 + 1], positions[i * 3 + 2]);
    const l1 = layers[i];
    const searchLimit = Math.min(i + 50, count);

    for (let j = i + 1; j < searchLimit; j++) {
      const l2 = layers[j];
      // Only connect nodes within the same spherical layer or across direct radial vertical axons
      if (l1 !== l2 && Math.random() > 0.08) continue;

      const p2 = new THREE.Vector3(positions[j * 3], positions[j * 3 + 1], positions[j * 3 + 2]);
      const distSq = p1.distanceToSquared(p2);

      // Enforce minimum gap to avoid clustering and maximum threshold to preserve negative space
      if (distSq >= minDistSq && distSq <= maxDistSq) {
        linePoints.push(p1, p2);
        conn++;
        if (conn >= maxConn) break;
      }
    }
  }

  return linePoints;
}

/**
 * Generates curved 3D axonal arches and bridges looping across the sphere surface.
 */
function generateSphereAxonArches(positions: Float32Array, count: number): {
  linePoints: THREE.Vector3[];
  curvePaths: THREE.QuadraticBezierCurve3[];
  archTerminalBulbs: number[];
} {
  const linePoints: THREE.Vector3[] = [];
  const curvePaths: THREE.QuadraticBezierCurve3[] = [];
  const archTerminalBulbs: number[] = [];
  const totalNodes = positions.length / 3;

  for (let i = 0; i < count; i++) {
    const idxA = Math.floor(Math.random() * totalNodes);
    const idxB = Math.floor(Math.random() * totalNodes);
    if (idxA === idxB) continue;

    const pA = new THREE.Vector3(positions[idxA * 3], positions[idxA * 3 + 1], positions[idxA * 3 + 2]);
    const pB = new THREE.Vector3(positions[idxB * 3], positions[idxB * 3 + 1], positions[idxB * 3 + 2]);

    const dist = pA.distanceTo(pB);
    if (dist < 0.5 || dist > 2.6) continue;

    // Midpoint elevated above sphere surface creating an axonal neural bridge
    const mid = new THREE.Vector3().addVectors(pA, pB).multiplyScalar(0.5);
    const midNorm = mid.clone().normalize();
    const elevation = 0.3 + Math.random() * 0.65;
    mid.addScaledVector(midNorm, elevation);

    const curve = new THREE.QuadraticBezierCurve3(pA, mid, pB);
    curvePaths.push(curve);

    const pts = curve.getPoints(12);
    for (let s = 0; s < pts.length - 1; s++) {
      linePoints.push(pts[s], pts[s + 1]);
    }

    archTerminalBulbs.push(mid.x, mid.y, mid.z);
  }

  return { linePoints, curvePaths, archTerminalBulbs };
}

/**
 * Anatomically structured 3D Human/AI Brain point cloud.
 */
function generateBrainPoints(count: number): {
  positions: Float32Array;
  hemispheres: Float32Array;
  hubs: Float32Array;
} {
  const positions = new Float32Array(count * 3);
  const hemispheres = new Float32Array(count);
  const hubs = new Float32Array(count);

  for (let i = 0; i < count; i++) {
    const isRight = Math.random() > 0.5 ? 1 : 0;
    hemispheres[i] = isRight;

    const u = Math.random();
    const v = Math.random();
    const theta = u * Math.PI * 2;
    const phi = Math.acos(2 * v - 1);

    const side = isRight ? 1 : -1;
    let x = (Math.sin(phi) * Math.cos(theta) * 0.9 + side * 0.28) * 1.35;
    let y = Math.sin(phi) * Math.sin(theta) * 1.05;
    let z = Math.cos(phi) * 1.45;

    if (Math.abs(x) < 0.25) {
      x *= 0.6;
    }
    if (z > 0.3 && y < -0.1) {
      x *= 1.15;
      y *= 1.1;
    }
    if (z < -0.6 && y < -0.2) {
      x *= 0.85;
      z *= 0.95;
    }

    const folding =
      Math.sin(x * 7.5) * Math.cos(y * 7.5) * Math.sin(z * 7.5) * 0.12 +
      Math.sin(x * 15 + y * 15) * 0.04;

    x += folding * Math.sign(x);
    y += folding;
    z += folding;

    const depth = 0.88 + Math.random() * 0.24;
    x *= depth;
    y *= depth;
    z *= depth;

    positions[i * 3] = x;
    positions[i * 3 + 1] = y;
    positions[i * 3 + 2] = z;

    hubs[i] = Math.random() < 0.04 ? 1 : 0;
  }

  return { positions, hemispheres, hubs };
}

/**
 * Generates organic curved dendrite arbors branching outward from anatomical brain.
 */
function generateCurvedDendrites(brainPositions: Float32Array, count: number) {
  const linePoints: THREE.Vector3[] = [];
  const terminalBulbPositions: number[] = [];
  const curvePaths: THREE.QuadraticBezierCurve3[] = [];

  for (let i = 0; i < count; i++) {
    const brainIdx = Math.floor(Math.random() * (brainPositions.length / 3));
    const startP = new THREE.Vector3(
      brainPositions[brainIdx * 3],
      brainPositions[brainIdx * 3 + 1],
      brainPositions[brainIdx * 3 + 2],
    );

    const outwardDir = startP.clone().normalize();
    const length = 0.9 + Math.random() * 2.1;

    const tangent = new THREE.Vector3(
      Math.sin(i * 1.7),
      Math.cos(i * 2.3),
      Math.sin(i * 3.1),
    ).cross(outwardDir).normalize();

    const curveAmount = (Math.random() - 0.5) * 1.2;
    const midP = startP.clone().addScaledVector(outwardDir, length * 0.5).addScaledVector(tangent, curveAmount);
    const endP = startP.clone().addScaledVector(outwardDir, length).addScaledVector(tangent, curveAmount * 1.5);

    const mainCurve = new THREE.QuadraticBezierCurve3(startP, midP, endP);
    curvePaths.push(mainCurve);

    const segments = 14;
    const mainPoints = mainCurve.getPoints(segments);
    for (let s = 0; s < mainPoints.length - 1; s++) {
      linePoints.push(mainPoints[s], mainPoints[s + 1]);
    }

    const subBranches = 2 + Math.floor(Math.random() * 2);
    for (let b = 0; b < subBranches; b++) {
      const subLength = 0.4 + Math.random() * 0.75;
      const angle = (b / subBranches) * Math.PI * 2 + (Math.random() - 0.5);
      const subDir = outwardDir.clone().applyAxisAngle(tangent, (Math.random() - 0.5) * 0.9);
      const subTangent = new THREE.Vector3(Math.cos(angle), Math.sin(angle), Math.sin(angle * 2.5)).normalize();

      const subMid = endP.clone().addScaledVector(subDir, subLength * 0.5).addScaledVector(subTangent, 0.38);
      const subEnd = endP.clone().addScaledVector(subDir, subLength).addScaledVector(subTangent, 0.7);

      const subCurve = new THREE.QuadraticBezierCurve3(endP, subMid, subEnd);
      curvePaths.push(subCurve);

      const subPoints = subCurve.getPoints(10);
      for (let s = 0; s < subPoints.length - 1; s++) {
        linePoints.push(subPoints[s], subPoints[s + 1]);
      }

      terminalBulbPositions.push(subEnd.x, subEnd.y, subEnd.z);
    }
  }

  return { linePoints, terminalBulbPositions, curvePaths };
}

/**
 * Computes a complex, realistic dual-pump cardiac / neural heartbeat waveform.
 * Phase [0, 1):
 * - 0.00 -> 0.16 : Systolic Contraction (LUB) - sharp surge & glow
 * - 0.16 -> 0.22 : Brief rebound recoil
 * - 0.22 -> 0.40 : Diastolic Resonant Pulse (DUB) - secondary harmonic wave
 * - 0.40 -> 1.00 : Diastolic Pause & Breathing Recovery with subtle micro-flutter
 */
function computeComplexHeartbeat(phase: number): { pulseScale: number; energyGlow: number; isSystole: boolean } {
  let pulseScale = 0;
  let energyGlow = 0;
  let isSystole = false;

  if (phase >= 0 && phase < 0.16) {
    // Primary Systolic Pump ("LUB")
    const t = phase / 0.16;
    const lub = Math.sin(t * Math.PI);
    pulseScale = Math.pow(lub, 1.8) * 0.24;
    energyGlow = Math.pow(lub, 1.2) * 1.0;
    isSystole = true;
  } else if (phase >= 0.22 && phase < 0.40) {
    // Secondary Diastolic Pump ("DUB")
    const t = (phase - 0.22) / 0.18;
    const dub = Math.sin(t * Math.PI);
    pulseScale = Math.pow(dub, 2.0) * 0.13;
    energyGlow = Math.pow(dub, 1.5) * 0.55;
    isSystole = true;
  } else {
    // Relaxed diastolic respiration & refractory pause
    const t = (phase - 0.40) / 0.60;
    pulseScale = Math.sin(t * Math.PI * 2) * 0.015;
    energyGlow = 0.05 * (1 - t);
    isSystole = false;
  }

  return { pulseScale, energyGlow, isSystole };
}

export function HolographicHeroCenter({
  onToggleView,
  activeView = "split",
}: HolographicHeroCenterProps) {
  const mountRef = useRef<HTMLDivElement>(null);
  const phase = useAgentStore((s) => s.orbPhase);
  const phaseRef = useRef(phase);
  phaseRef.current = phase;

  const [isMicActive, setIsMicActive] = useState(false);
  const [micVolume, setMicVolume] = useState(0);
  const [currentBpm, setCurrentBpm] = useState(52);
  const [selectedTheme, setSelectedTheme] = useState<CoreColorTheme>("electric_cyan");
  const [modelType, setModelType] = useState<HologramModel>("neural_sphere");
  const [isFullscreen, setIsFullscreen] = useState(false);

  const themeRef = useRef(selectedTheme);
  themeRef.current = selectedTheme;

  const modelRef = useRef(modelType);
  modelRef.current = modelType;

  const audioLevelRef = useRef<number>(0);
  const audioDataRef = useRef<Uint8Array | null>(null);
  const audioContextRef = useRef<AudioContext | null>(null);
  const analyserRef = useRef<AnalyserNode | null>(null);
  const micStreamRef = useRef<MediaStream | null>(null);

  // Toggle Live Web Audio API Mic listener
  const toggleMic = async () => {
    if (isMicActive) {
      if (micStreamRef.current) {
        micStreamRef.current.getTracks().forEach((t) => t.stop());
        micStreamRef.current = null;
      }
      if (audioContextRef.current) {
        void audioContextRef.current.close();
        audioContextRef.current = null;
      }
      analyserRef.current = null;
      audioDataRef.current = null;
      audioLevelRef.current = 0;
      setIsMicActive(false);
      setMicVolume(0);
      setCurrentBpm(52);
    } else {
      try {
        const stream = await navigator.mediaDevices.getUserMedia({ audio: true, video: false });
        micStreamRef.current = stream;
        const audioCtx = new (window.AudioContext || (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext)();
        audioContextRef.current = audioCtx;
        const source = audioCtx.createMediaStreamSource(stream);
        const analyser = audioCtx.createAnalyser();
        analyser.fftSize = 256;
        analyser.smoothingTimeConstant = 0.65;
        source.connect(analyser);
        analyserRef.current = analyser;
        audioDataRef.current = new Uint8Array(analyser.frequencyBinCount);
        setIsMicActive(true);
      } catch (err) {
        console.warn("Microphone access not granted or unavailable:", err);
      }
    }
  };

  useEffect(() => {
    return () => {
      if (micStreamRef.current) {
        micStreamRef.current.getTracks().forEach((t) => t.stop());
      }
      if (audioContextRef.current) {
        void audioContextRef.current.close();
      }
    };
  }, []);

  // Three.js 3D Engine Setup
  useEffect(() => {
    const container = mountRef.current;
    if (!container) return;

    let animFrameId: number;
    const w = container.clientWidth || 700;
    const h = container.clientHeight || 500;

    const scene = new THREE.Scene();
    const camera = new THREE.PerspectiveCamera(65, w / h, 0.1, 1000);
    camera.position.z = 4.8;

    const renderer = new THREE.WebGLRenderer({ alpha: true, antialias: true, powerPreference: "high-performance" });
    renderer.setSize(w, h);
    renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
    renderer.setClearColor(0x000000, 0);
    container.appendChild(renderer.domElement);

    const activePalette = THEME_PALETTES[themeRef.current];
    const coreColor = new THREE.Color(activePalette.core);
    const accentColor = new THREE.Color(activePalette.accent);
    const particleColor = new THREE.Color(activePalette.particle);
    const lightningColor = new THREE.Color(activePalette.lightning);

    // Groups
    const sphereGroup = new THREE.Group();
    const brainGroup = new THREE.Group();
    const quantumGroup = new THREE.Group();
    scene.add(sphereGroup);
    scene.add(brainGroup);
    scene.add(quantumGroup);

    // Custom glowing particle texture generator
    const createParticleTexture = () => {
      const canvas = document.createElement("canvas");
      canvas.width = 64;
      canvas.height = 64;
      const ctx = canvas.getContext("2d")!;
      const grad = ctx.createRadialGradient(32, 32, 0, 32, 32, 32);
      grad.addColorStop(0, "rgba(255,255,255,1)");
      grad.addColorStop(0.2, "rgba(180,240,255,0.9)");
      grad.addColorStop(0.5, "rgba(0,217,255,0.4)");
      grad.addColorStop(1, "rgba(0,0,0,0)");
      ctx.fillStyle = grad;
      ctx.fillRect(0, 0, 64, 64);
      return new THREE.CanvasTexture(canvas);
    };

    const particleTexture = createParticleTexture();

    // =========================================================================
    // 1. ETHEREAL 3D NEURAL SPHERE WITH WIDE SPATIAL GAPS
    // =========================================================================
    const sphereNodeCount = 3400;
    const {
      positions: spherePositions,
      basePositions: sphereBasePositions,
      normals: sphereNormals,
      hubs: sphereHubs,
      layers: sphereLayers,
    } = generateNeuralSpherePoints(sphereNodeCount);

    const sphereGeometry = new THREE.BufferGeometry();
    sphereGeometry.setAttribute("position", new THREE.BufferAttribute(spherePositions, 3));

    const spherePointsMaterial = new THREE.PointsMaterial({
      color: particleColor,
      size: 0.034,
      map: particleTexture,
      transparent: true,
      opacity: 0.92,
      blending: THREE.AdditiveBlending,
      depthWrite: false,
    });
    const spherePointsMesh = new THREE.Points(sphereGeometry, spherePointsMaterial);
    sphereGroup.add(spherePointsMesh);

    // Inner Glowing Energy Nucleus Core (tight seed floating in central gap)
    const sphereCoreGeo = new THREE.SphereGeometry(0.38, 32, 32);
    const sphereCoreMat = new THREE.MeshBasicMaterial({
      color: coreColor,
      transparent: true,
      opacity: 0.45,
    });
    const sphereCoreMesh = new THREE.Mesh(sphereCoreGeo, sphereCoreMat);
    sphereGroup.add(sphereCoreMesh);

    // Synaptic Super-Hub Flares across the sphere
    const sphereHubPositions: number[] = [];
    for (let i = 0; i < sphereNodeCount; i++) {
      if (sphereHubs[i] === 1) {
        sphereHubPositions.push(spherePositions[i * 3], spherePositions[i * 3 + 1], spherePositions[i * 3 + 2]);
      }
    }
    const sphereHubGeo = new THREE.BufferGeometry();
    sphereHubGeo.setAttribute("position", new THREE.Float32BufferAttribute(sphereHubPositions, 3));
    const sphereHubMat = new THREE.PointsMaterial({
      color: 0xffffff,
      size: 0.11,
      map: particleTexture,
      transparent: true,
      opacity: 0.98,
      blending: THREE.AdditiveBlending,
    });
    const sphereHubMesh = new THREE.Points(sphereHubGeo, sphereHubMat);
    sphereGroup.add(sphereHubMesh);

    // Airy Sphere Axon Plexus Line Network with clean polygonal negative space
    const spherePlexusPts = generateSpherePlexus(spherePositions, sphereLayers, sphereNodeCount, 0.42, 0.14, 3);
    const spherePlexusGeo = new THREE.BufferGeometry().setFromPoints(spherePlexusPts);
    const spherePlexusMat = new THREE.LineBasicMaterial({
      color: coreColor,
      transparent: true,
      opacity: 0.32,
      blending: THREE.AdditiveBlending,
    });
    const spherePlexusMesh = new THREE.LineSegments(spherePlexusGeo, spherePlexusMat);
    sphereGroup.add(spherePlexusMesh);

    // Curved Axonal Bridges traversing the sphere surface
    const { linePoints: sphereArchPts, curvePaths: sphereArchCurves, archTerminalBulbs } = generateSphereAxonArches(
      spherePositions,
      120,
    );
    const sphereArchGeo = new THREE.BufferGeometry().setFromPoints(sphereArchPts);
    const sphereArchMat = new THREE.LineBasicMaterial({
      color: coreColor,
      transparent: true,
      opacity: 0.5,
      blending: THREE.AdditiveBlending,
    });
    const sphereArchMesh = new THREE.LineSegments(sphereArchGeo, sphereArchMat);
    sphereGroup.add(sphereArchMesh);

    // Terminal Bulbs on Axon Arches
    const archBulbGeo = new THREE.BufferGeometry();
    archBulbGeo.setAttribute("position", new THREE.Float32BufferAttribute(archTerminalBulbs, 3));
    const archBulbMat = new THREE.PointsMaterial({
      color: particleColor,
      size: 0.055,
      map: particleTexture,
      transparent: true,
      opacity: 0.95,
      blending: THREE.AdditiveBlending,
      depthWrite: false,
    });
    const archBulbMesh = new THREE.Points(archBulbGeo, archBulbMat);
    sphereGroup.add(archBulbMesh);

    // Action Potential Sparks traveling along sphere axon bridges
    const sphereSparkCount = 90;
    const sphereSparkPositions = new Float32Array(sphereSparkCount * 3);
    const sphereSparkGeo = new THREE.BufferGeometry();
    sphereSparkGeo.setAttribute("position", new THREE.BufferAttribute(sphereSparkPositions, 3));
    const sphereSparkMat = new THREE.PointsMaterial({
      color: 0xffffff,
      size: 0.085,
      map: particleTexture,
      transparent: true,
      opacity: 0.98,
      blending: THREE.AdditiveBlending,
      depthWrite: false,
    });
    const sphereSparkMesh = new THREE.Points(sphereSparkGeo, sphereSparkMat);
    sphereGroup.add(sphereSparkMesh);

    // Branching Synaptic Lightning Discharges jumping between sphere hubs
    const sphereLightningCount = 14;
    const sphereLightningLines: THREE.Line[] = [];
    const sphereLightningMat = new THREE.LineBasicMaterial({
      color: lightningColor,
      transparent: true,
      opacity: 0.9,
      linewidth: 2,
      blending: THREE.AdditiveBlending,
    });

    for (let l = 0; l < sphereLightningCount; l++) {
      const segs = 16;
      const pts: THREE.Vector3[] = [];
      for (let s = 0; s <= segs; s++) pts.push(new THREE.Vector3(0, 0, 0));
      const lGeo = new THREE.BufferGeometry().setFromPoints(pts);
      const lMesh = new THREE.Line(lGeo, sphereLightningMat);
      sphereGroup.add(lMesh);
      sphereLightningLines.push(lMesh);
    }

    // =========================================================================
    // 2. ANATOMICAL 3D NEURAL BRAIN MODEL
    // =========================================================================
    const brainCount = 3800;
    const { positions: brainPositions, hubs, hemispheres: brainHemispheres } = generateBrainPoints(brainCount);

    const brainGeometry = new THREE.BufferGeometry();
    brainGeometry.setAttribute("position", new THREE.BufferAttribute(brainPositions, 3));

    const brainMaterial = new THREE.PointsMaterial({
      color: particleColor,
      size: 0.038,
      map: particleTexture,
      transparent: true,
      opacity: 0.9,
      blending: THREE.AdditiveBlending,
      depthWrite: false,
    });
    const brainPointsMesh = new THREE.Points(brainGeometry, brainMaterial);
    brainGroup.add(brainPointsMesh);

    const hubPositions: number[] = [];
    for (let i = 0; i < brainCount; i++) {
      if (hubs[i] === 1) {
        hubPositions.push(brainPositions[i * 3], brainPositions[i * 3 + 1], brainPositions[i * 3 + 2]);
      }
    }
    const hubGeo = new THREE.BufferGeometry();
    hubGeo.setAttribute("position", new THREE.Float32BufferAttribute(hubPositions, 3));
    const hubMat = new THREE.PointsMaterial({
      color: 0xffffff,
      size: 0.12,
      map: particleTexture,
      transparent: true,
      opacity: 0.95,
      blending: THREE.AdditiveBlending,
    });
    const hubMesh = new THREE.Points(hubGeo, hubMat);
    brainGroup.add(hubMesh);

    const brainPlexusPts = generateSpherePlexus(brainPositions, brainHemispheres, brainCount, 0.32, 0.1, 4);
    const plexusGeo = new THREE.BufferGeometry().setFromPoints(brainPlexusPts);
    const plexusMat = new THREE.LineBasicMaterial({
      color: coreColor,
      transparent: true,
      opacity: 0.35,
      blending: THREE.AdditiveBlending,
    });
    const plexusMesh = new THREE.LineSegments(plexusGeo, plexusMat);
    brainGroup.add(plexusMesh);

    const { linePoints: dendriteLines, terminalBulbPositions, curvePaths: brainCurvePaths } = generateCurvedDendrites(
      brainPositions,
      140,
    );
    const curvedDendritesGeo = new THREE.BufferGeometry().setFromPoints(dendriteLines);
    const curvedDendritesMat = new THREE.LineBasicMaterial({
      color: coreColor,
      transparent: true,
      opacity: 0.45,
      blending: THREE.AdditiveBlending,
    });
    const curvedDendritesMesh = new THREE.LineSegments(curvedDendritesGeo, curvedDendritesMat);
    brainGroup.add(curvedDendritesMesh);

    const bulbGeo = new THREE.BufferGeometry();
    bulbGeo.setAttribute("position", new THREE.Float32BufferAttribute(terminalBulbPositions, 3));
    const bulbMat = new THREE.PointsMaterial({
      color: particleColor,
      size: 0.055,
      map: particleTexture,
      transparent: true,
      opacity: 0.95,
      blending: THREE.AdditiveBlending,
      depthWrite: false,
    });
    const bulbMesh = new THREE.Points(bulbGeo, bulbMat);
    brainGroup.add(bulbMesh);

    const sparkCount = 70;
    const sparkPositions = new Float32Array(sparkCount * 3);
    const sparkGeo = new THREE.BufferGeometry();
    sparkGeo.setAttribute("position", new THREE.BufferAttribute(sparkPositions, 3));
    const sparkMat = new THREE.PointsMaterial({
      color: 0xffffff,
      size: 0.08,
      map: particleTexture,
      transparent: true,
      opacity: 0.95,
      blending: THREE.AdditiveBlending,
      depthWrite: false,
    });
    const sparkMesh = new THREE.Points(sparkGeo, sparkMat);
    brainGroup.add(sparkMesh);

    const lightningArcsCount = 12;
    const brainLightningLines: THREE.Line[] = [];
    const brainLightningMat = new THREE.LineBasicMaterial({
      color: lightningColor,
      transparent: true,
      opacity: 0.9,
      linewidth: 2,
      blending: THREE.AdditiveBlending,
    });

    for (let l = 0; l < lightningArcsCount; l++) {
      const segs = 16;
      const pts: THREE.Vector3[] = [];
      for (let s = 0; s <= segs; s++) pts.push(new THREE.Vector3(0, 0, 0));
      const lGeo = new THREE.BufferGeometry().setFromPoints(pts);
      const lMesh = new THREE.Line(lGeo, brainLightningMat);
      brainGroup.add(lMesh);
      brainLightningLines.push(lMesh);
    }

    // =========================================================================
    // 3. QUANTUM HOLOGRAPHIC SPHERE & HUD DATA RINGS
    // =========================================================================
    const coreMeshGeo = new THREE.IcosahedronGeometry(1.2, 5);
    const coreMeshMat = new THREE.MeshPhongMaterial({
      color: coreColor,
      emissive: accentColor,
      emissiveIntensity: 0.75,
      shininess: 100,
      transparent: true,
      opacity: 0.85,
      wireframe: true,
    });
    const quantumCoreMesh = new THREE.Mesh(coreMeshGeo, coreMeshMat);
    quantumGroup.add(quantumCoreMesh);

    const glowGeo = new THREE.SphereGeometry(0.85, 32, 32);
    const glowMat = new THREE.MeshBasicMaterial({
      color: coreColor,
      transparent: true,
      opacity: 0.35,
    });
    const quantumGlowMesh = new THREE.Mesh(glowGeo, glowMat);
    quantumGroup.add(quantumGlowMesh);

    const createDataRing = (radius: number, tube: number, opacity: number, speedX: number, speedY: number) => {
      const geo = new THREE.TorusGeometry(radius, tube, 2, 120);
      const mat = new THREE.MeshBasicMaterial({
        color: coreColor,
        transparent: true,
        opacity,
        wireframe: true,
      });
      const ring = new THREE.Mesh(geo, mat);
      quantumGroup.add(ring);
      return { mesh: ring, mat, speedX, speedY };
    };

    const rings = [
      createDataRing(1.85, 0.008, 0.5, 0.007, 0.011),
      createDataRing(2.2, 0.005, 0.35, -0.009, -0.006),
      createDataRing(2.6, 0.012, 0.25, 0.005, -0.008),
    ];

    // Dynamic 3D Audio Waveform Ribbon Loop
    const waveVerts = 128;
    const wavePoints: THREE.Vector3[] = [];
    const waveRadius = 1.95;
    for (let i = 0; i <= waveVerts; i++) {
      const angle = (i / waveVerts) * Math.PI * 2;
      wavePoints.push(new THREE.Vector3(Math.cos(angle) * waveRadius, Math.sin(angle) * waveRadius, 0));
    }
    const waveGeo = new THREE.BufferGeometry().setFromPoints(wavePoints);
    const waveMat = new THREE.LineBasicMaterial({
      color: coreColor,
      transparent: true,
      opacity: 0.8,
      linewidth: 2,
    });
    const waveLine = new THREE.Line(waveGeo, waveMat);
    quantumGroup.add(waveLine);

    // Lighting
    const ambientLight = new THREE.AmbientLight(0xffffff, 0.8);
    scene.add(ambientLight);
    const pointLight = new THREE.PointLight(coreColor, 4.0, 14);
    pointLight.position.set(0, 0, 0);
    scene.add(pointLight);

    // Mouse Parallax Interaction
    let targetX = 0;
    let targetY = 0;
    let currentX = 0;
    let currentY = 0;

    const handleMouseMove = (e: MouseEvent) => {
      const rect = container.getBoundingClientRect();
      const x = (e.clientX - rect.left - rect.width / 2) / (rect.width / 2);
      const y = (e.clientY - rect.top - rect.height / 2) / (rect.height / 2);
      targetX = x * 0.45;
      targetY = y * 0.45;
    };

    container.addEventListener("mousemove", handleMouseMove);

    // Resize Observer
    const resizeObserver = new ResizeObserver((entries) => {
      for (const entry of entries) {
        const { width: newW, height: newH } = entry.contentRect;
        if (newW > 0 && newH > 0) {
          camera.aspect = newW / newH;
          camera.updateProjectionMatrix();
          renderer.setSize(newW, newH);
        }
      }
    });
    resizeObserver.observe(container);

    // =========================================================================
    // MAIN 3D ANIMATION LOOP & COMPLEX HEARTBEAT ENGINE
    // =========================================================================
    let clock = 0;
    let cardiacPhase = 0; // [0, 1) continuous cardiac phase
    let lastBpmUpdate = 0;

    const animate = () => {
      animFrameId = requestAnimationFrame(animate);
      const dt = 0.016;
      clock += dt;

      // Audio Reactive Sampling
      let rawVolume = 0;
      if (analyserRef.current && audioDataRef.current) {
        analyserRef.current.getByteFrequencyData(audioDataRef.current as any);
        let sum = 0;
        for (let i = 0; i < audioDataRef.current.length; i++) {
          sum += audioDataRef.current[i];
        }
        rawVolume = sum / audioDataRef.current.length / 255;
        audioLevelRef.current = THREE.MathUtils.lerp(audioLevelRef.current, rawVolume, 0.4);
      } else {
        const p = phaseRef.current;
        const isSpeaking = p === "speaking" || p === "executing";
        const isListening = p === "listening";
        const baseAmp = isSpeaking ? 0.45 + Math.sin(clock * 9) * 0.25 : isListening ? 0.2 + Math.sin(clock * 5) * 0.1 : 0.05;
        audioLevelRef.current = THREE.MathUtils.lerp(audioLevelRef.current, baseAmp, 0.15);
      }

      setMicVolume(Math.round(audioLevelRef.current * 100));

      // DYNAMIC HEARTBEAT SPEED ENGINE:
      // Base resting rate = ~50 BPM (frequency 0.83 Hz).
      // As user talks / audio amplitude increases, heart rate dynamically accelerates up to ~210 BPM (3.5 Hz)!
      const baseHeartRateHz = 0.82; // ~49 BPM (slow, deep, hypnotic resting pulse)
      const voiceRateBoostHz = audioLevelRef.current * 2.68; // Hyper-acceleration on voice input
      const currentHeartFreqHz = baseHeartRateHz + voiceRateBoostHz;
      const bpmCalculated = Math.round(currentHeartFreqHz * 60);

      // Throttled UI BPM display update (every 100ms)
      if (clock - lastBpmUpdate > 0.1) {
        setCurrentBpm(bpmCalculated);
        lastBpmUpdate = clock;
      }

      // Progress cardiac phase with dynamic frequency velocity
      cardiacPhase = (cardiacPhase + currentHeartFreqHz * dt) % 1.0;
      const { pulseScale, energyGlow } = computeComplexHeartbeat(cardiacPhase);

      // Color Theme Updates
      const theme = THEME_PALETTES[themeRef.current];
      const targetCore = new THREE.Color(theme.core);
      const targetAccent = new THREE.Color(theme.accent);
      const targetParticle = new THREE.Color(theme.particle);
      const targetLightning = new THREE.Color(theme.lightning);

      // Lerp Material Colors
      spherePointsMaterial.color.lerp(targetParticle, 0.06);
      sphereCoreMat.color.lerp(targetCore, 0.06);
      sphereHubMat.color.lerp(targetLightning, 0.06);
      spherePlexusMat.color.lerp(targetCore, 0.06);
      sphereArchMat.color.lerp(targetCore, 0.06);
      archBulbMat.color.lerp(targetParticle, 0.06);
      sphereSparkMat.color.lerp(targetLightning, 0.06);
      sphereLightningMat.color.lerp(targetLightning, 0.06);

      brainMaterial.color.lerp(targetParticle, 0.06);
      hubMat.color.lerp(targetLightning, 0.06);
      plexusMat.color.lerp(targetCore, 0.06);
      curvedDendritesMat.color.lerp(targetCore, 0.06);
      bulbMat.color.lerp(targetParticle, 0.06);
      sparkMat.color.lerp(targetLightning, 0.06);
      brainLightningMat.color.lerp(targetLightning, 0.06);

      coreMeshMat.color.lerp(targetCore, 0.06);
      coreMeshMat.emissive.lerp(targetAccent, 0.06);
      quantumGlowMesh.material.color.lerp(targetCore, 0.06);
      pointLight.color.lerp(targetCore, 0.06);
      rings.forEach((r) => r.mat.color.lerp(targetCore, 0.06));
      waveMat.color.lerp(targetCore, 0.06);

      // Dynamic Point Light Pulsing with Heartbeat & Energy
      pointLight.intensity = 3.0 + energyGlow * 3.5 + audioLevelRef.current * 4.0;
      sphereCoreMat.opacity = 0.25 + energyGlow * 0.45 + audioLevelRef.current * 0.35;

      // Model Switching
      const currentModel = modelRef.current;
      if (currentModel === "neural_sphere") {
        sphereGroup.visible = true;
        brainGroup.visible = false;
        quantumGroup.visible = false;
      } else if (currentModel === "neural_brain") {
        sphereGroup.visible = false;
        brainGroup.visible = true;
        quantumGroup.visible = false;
      } else if (currentModel === "cyber_synapse") {
        // Hybrid: Sphere + Holographic HUD Rings
        sphereGroup.visible = true;
        brainGroup.visible = false;
        quantumGroup.visible = true;
        quantumCoreMesh.visible = false;
        quantumGlowMesh.visible = false;
      } else {
        // quantum_core
        sphereGroup.visible = false;
        brainGroup.visible = false;
        quantumGroup.visible = true;
        quantumCoreMesh.visible = true;
        quantumGlowMesh.visible = true;
      }

      // =======================================================================
      // SPHERE DYNAMIC VERTEX DISPLACEMENT (CARDIAC SHOCKWAVE + AUDIO WAVES)
      // =======================================================================
      if (sphereGroup.visible) {
        const spherePosAttr = sphereGeometry.attributes.position as THREE.BufferAttribute;
        const sArr = spherePosAttr.array as Float32Array;
        const totalNodes = sphereNodeCount;
        const dataArr = audioDataRef.current;

        for (let i = 0; i < totalNodes; i++) {
          const bx = sphereBasePositions[i * 3];
          const by = sphereBasePositions[i * 3 + 1];
          const bz = sphereBasePositions[i * 3 + 2];
          const nx = sphereNormals[i * 3];
          const ny = sphereNormals[i * 3 + 1];
          const nz = sphereNormals[i * 3 + 2];

          // Complex Spherical Harmonic Cardiac Expansion
          // Outward displacement during Lub-Dub systole + voice wave ripples
          let audioDisplace = 0;
          if (dataArr && dataArr.length > 0) {
            const binIdx = i % (dataArr.length / 2);
            audioDisplace = (dataArr[binIdx] / 255) * 0.35;
          } else {
            audioDisplace = audioLevelRef.current * 0.25;
          }

          const radialDisplacement = pulseScale * 1.2 + audioDisplace;
          sArr[i * 3] = bx + nx * radialDisplacement;
          sArr[i * 3 + 1] = by + ny * radialDisplacement;
          sArr[i * 3 + 2] = bz + nz * radialDisplacement;
        }
        spherePosAttr.needsUpdate = true;

        // Dynamic Rotation: Accelerates rotation speed when user talks
        const sphereSpinSpeed = 0.007 + audioLevelRef.current * 0.035 + pulseScale * 0.01;
        sphereGroup.rotation.y += sphereSpinSpeed;
        sphereGroup.rotation.x = Math.sin(clock * 0.6) * 0.08;

        // Animate Action Potential Sparks traveling along sphere axon arches (Speed accelerates with voice)
        const sparkPosAttr = sphereSparkGeo.attributes.position as THREE.BufferAttribute;
        const sparkArr = sparkPosAttr.array as Float32Array;
        const numCurves = sphereArchCurves.length;
        if (numCurves > 0) {
          const sparkSpeedMultiplier = 0.4 + (currentHeartFreqHz - 0.8) * 0.6 + audioLevelRef.current * 1.2;
          for (let k = 0; k < sphereSparkCount; k++) {
            const curve = sphereArchCurves[(k * 3) % numCurves];
            const t = (clock * (0.35 + (k % 4) * 0.15) * sparkSpeedMultiplier + k / sphereSparkCount) % 1.0;
            const p = curve.getPoint(t);
            sparkArr[k * 3] = p.x;
            sparkArr[k * 3 + 1] = p.y;
            sparkArr[k * 3 + 2] = p.z;
          }
          sparkPosAttr.needsUpdate = true;
        }

        // Action Potential Lightning Discharges (Surges during voice & systole)
        const lightningTrigger = 0.28 + audioLevelRef.current * 0.65 + (energyGlow > 0.4 ? 0.3 : 0);
        if (Math.random() < lightningTrigger) {
          sphereLightningLines.forEach((line) => {
            const lGeo = line.geometry;
            const pos = lGeo.attributes.position.array as Float32Array;
            const segs = pos.length / 3 - 1;

            const idxA = Math.floor(Math.random() * (sphereNodeCount - 1));
            const idxB = Math.floor(Math.random() * (sphereNodeCount - 1));
            const startP = new THREE.Vector3(
              spherePositions[idxA * 3],
              spherePositions[idxA * 3 + 1],
              spherePositions[idxA * 3 + 2],
            );
            const endP = new THREE.Vector3(
              spherePositions[idxB * 3],
              spherePositions[idxB * 3 + 1],
              spherePositions[idxB * 3 + 2],
            );

            for (let s = 0; s <= segs; s++) {
              const alpha = s / segs;
              const curP = new THREE.Vector3().lerpVectors(startP, endP, alpha);
              if (s > 0 && s < segs) {
                const jitter = 0.14 * (1 + audioLevelRef.current * 1.8);
                curP.x += (Math.random() - 0.5) * jitter;
                curP.y += (Math.random() - 0.5) * jitter;
                curP.z += (Math.random() - 0.5) * jitter;
              }
              pos[s * 3] = curP.x;
              pos[s * 3 + 1] = curP.y;
              pos[s * 3 + 2] = curP.z;
            }
            lGeo.attributes.position.needsUpdate = true;
          });
        }

        // Synaptic Hub Pulsing & Plexus Line Opacity Surge
        sphereHubMat.size = 0.11 + energyGlow * 0.08 + audioLevelRef.current * 0.16;
        spherePlexusMat.opacity = 0.3 + energyGlow * 0.35 + audioLevelRef.current * 0.45;
        sphereArchMat.opacity = 0.4 + energyGlow * 0.35 + audioLevelRef.current * 0.5;
      }

      // =======================================================================
      // BRAIN GROUP ANIMATION
      // =======================================================================
      if (brainGroup.visible) {
        const audioScale = 1.0 + pulseScale * 0.6 + audioLevelRef.current * 0.65;
        brainGroup.scale.setScalar(audioScale);
        brainGroup.rotation.y += 0.008 + audioLevelRef.current * 0.025;
        brainGroup.rotation.x = Math.sin(clock * 0.5) * 0.08;

        const sparkPosAttr = sparkGeo.attributes.position as THREE.BufferAttribute;
        const sparkArr = sparkPosAttr.array as Float32Array;
        const numCurves = brainCurvePaths.length;
        if (numCurves > 0) {
          for (let k = 0; k < sparkCount; k++) {
            const curve = brainCurvePaths[(k * 3) % numCurves];
            const speed = 0.35 + (k % 4) * 0.15 + audioLevelRef.current * 0.8;
            const t = (clock * speed + k / sparkCount) % 1.0;
            const p = curve.getPoint(t);
            sparkArr[k * 3] = p.x;
            sparkArr[k * 3 + 1] = p.y;
            sparkArr[k * 3 + 2] = p.z;
          }
          sparkPosAttr.needsUpdate = true;
        }

        hubMat.size = 0.11 + energyGlow * 0.08 + audioLevelRef.current * 0.18;
        plexusMat.opacity = 0.25 + energyGlow * 0.3 + audioLevelRef.current * 0.6;

        if (Math.random() < 0.35 * (1 + audioLevelRef.current * 3.5)) {
          brainLightningLines.forEach((line) => {
            const lGeo = line.geometry;
            const pos = lGeo.attributes.position.array as Float32Array;
            const segs = pos.length / 3 - 1;

            const idxA = Math.floor(Math.random() * (brainCount - 1));
            const idxB = Math.floor(Math.random() * (brainCount - 1));
            const startP = new THREE.Vector3(
              brainPositions[idxA * 3],
              brainPositions[idxA * 3 + 1],
              brainPositions[idxA * 3 + 2],
            );
            const endP = new THREE.Vector3(
              brainPositions[idxB * 3],
              brainPositions[idxB * 3 + 1],
              brainPositions[idxB * 3 + 2],
            );

            for (let s = 0; s <= segs; s++) {
              const alpha = s / segs;
              const curP = new THREE.Vector3().lerpVectors(startP, endP, alpha);
              if (s > 0 && s < segs) {
                const jitter = 0.12 * (1 + audioLevelRef.current * 1.5);
                curP.x += (Math.random() - 0.5) * jitter;
                curP.y += (Math.random() - 0.5) * jitter;
                curP.z += (Math.random() - 0.5) * jitter;
              }
              pos[s * 3] = curP.x;
              pos[s * 3 + 1] = curP.y;
              pos[s * 3 + 2] = curP.z;
            }
            lGeo.attributes.position.needsUpdate = true;
          });
        }
      }

      // =======================================================================
      // QUANTUM GROUP & HUD RINGS ANIMATION
      // =======================================================================
      if (quantumGroup.visible) {
        rings.forEach((r, idx) => {
          const speedBoost = 1 + audioLevelRef.current * 3.0 + pulseScale * 0.8;
          r.mesh.rotation.x += r.speedX * speedBoost;
          r.mesh.rotation.y += r.speedY * speedBoost;
          r.mesh.scale.setScalar(1 + audioLevelRef.current * (0.15 * (idx + 1)) + pulseScale * 0.1);
        });

        const wavePos = waveGeo.attributes.position as THREE.BufferAttribute;
        const waveArr = wavePos.array as Float32Array;
        const dataArr = audioDataRef.current;

        for (let i = 0; i <= waveVerts; i++) {
          const angle = (i / waveVerts) * Math.PI * 2;
          let freqVal = 0;
          if (dataArr && dataArr.length > 0) {
            const sampleIdx = Math.floor((i / waveVerts) * (dataArr.length / 2));
            freqVal = (dataArr[sampleIdx] / 255) * 0.65;
          } else {
            freqVal = Math.sin(clock * 7 + angle * 4) * audioLevelRef.current * 0.45;
          }
          const dynamicR = waveRadius + freqVal + pulseScale * 0.25;
          waveArr[i * 3] = Math.cos(angle) * dynamicR;
          waveArr[i * 3 + 1] = Math.sin(angle) * dynamicR;
          waveArr[i * 3 + 2] = Math.sin(angle * 3 + clock * 2) * freqVal * 0.5;
        }
        wavePos.needsUpdate = true;
      }

      // Parallax smooth damping
      currentX += (targetX - currentX) * 0.06;
      currentY += (targetY - currentY) * 0.06;
      scene.rotation.y = currentX;
      scene.rotation.x = currentY;

      renderer.render(scene, camera);
    };

    animate();

    return () => {
      cancelAnimationFrame(animFrameId);
      resizeObserver.disconnect();
      container.removeEventListener("mousemove", handleMouseMove);
      if (container.contains(renderer.domElement)) {
        container.removeChild(renderer.domElement);
      }
      scene.clear();
      renderer.dispose();
    };
  }, []);

  const activeTheme = THEME_PALETTES[selectedTheme];

  const getBpmLabel = (bpm: number) => {
    if (bpm < 65) return "RESTING NEURAL (SLOW)";
    if (bpm < 110) return "ACTIVE COGNITION";
    if (bpm < 160) return "SPEECH RESONANCE";
    return "SYNAPTIC HYPER-SURGE";
  };

  return (
    <div
      className={cn(
        "relative flex flex-col items-center justify-center overflow-hidden rounded-2xl border border-line bg-surface/90 backdrop-blur-2xl shadow-2xl transition-all duration-300",
        isFullscreen ? "fixed inset-2 z-50 bg-black/95" : "min-h-[380px] w-full flex-1",
      )}
    >
      {/* Top Hologram HUD Header */}
      <div className="absolute top-3 inset-x-3 z-20 flex flex-wrap items-center justify-between gap-2 pointer-events-auto">
        {/* Model Switcher Tabs */}
        <div className="flex items-center gap-1 rounded-xl border border-line-strong bg-surface-raised/90 p-1 shadow-lg backdrop-blur-md">
          <button
            type="button"
            onClick={() => setModelType("neural_sphere")}
            className={cn(
              "flex items-center gap-1.5 rounded-lg px-2.5 py-1 font-mono text-[11px] font-semibold transition-all",
              modelType === "neural_sphere"
                ? "bg-accent/25 text-accent shadow-glow border border-accent/40"
                : "text-ink-muted hover:text-ink hover:bg-surface-hover",
            )}
            title="3D Dense Neural Sphere with Complex Heartbeat & Voice Acceleration"
          >
            <Globe className="h-3.5 w-3.5" />
            <span>Neural Sphere</span>
          </button>
          <button
            type="button"
            onClick={() => setModelType("neural_brain")}
            className={cn(
              "flex items-center gap-1.5 rounded-lg px-2.5 py-1 font-mono text-[11px] font-semibold transition-all",
              modelType === "neural_brain"
                ? "bg-accent/25 text-accent shadow-glow border border-accent/40"
                : "text-ink-muted hover:text-ink hover:bg-surface-hover",
            )}
            title="3D Synaptic Neural Brain"
          >
            <Zap className="h-3.5 w-3.5" />
            <span>Neural Brain</span>
          </button>
          <button
            type="button"
            onClick={() => setModelType("cyber_synapse")}
            className={cn(
              "flex items-center gap-1.5 rounded-lg px-2.5 py-1 font-mono text-[11px] font-semibold transition-all",
              modelType === "cyber_synapse"
                ? "bg-accent/25 text-accent shadow-glow border border-accent/40"
                : "text-ink-muted hover:text-ink hover:bg-surface-hover",
            )}
            title="Hybrid: Neural Sphere + Holographic HUD Rings"
          >
            <Radio className="h-3.5 w-3.5" />
            <span>Hybrid Synapse</span>
          </button>
          <button
            type="button"
            onClick={() => setModelType("quantum_core")}
            className={cn(
              "flex items-center gap-1.5 rounded-lg px-2.5 py-1 font-mono text-[11px] font-semibold transition-all",
              modelType === "quantum_core"
                ? "bg-accent/25 text-accent shadow-glow border border-accent/40"
                : "text-ink-muted hover:text-ink hover:bg-surface-hover",
            )}
            title="Quantum Core Sphere"
          >
            <Cpu className="h-3.5 w-3.5" />
            <span>Quantum Sphere</span>
          </button>
        </div>

        {/* Action Controls & Voice Mic Toggle */}
        <div className="flex items-center gap-1.5">
          {/* Live Microphone Voice Reactivity Toggle */}
          <button
            type="button"
            onClick={toggleMic}
            className={cn(
              "flex items-center gap-1.5 rounded-xl border px-3 py-1 font-mono text-[11px] font-semibold transition-all shadow-md",
              isMicActive
                ? "border-red-500/60 bg-red-500/20 text-red-400 shadow-[0_0_15px_rgba(239,68,68,0.4)] animate-pulse"
                : "border-line bg-surface-raised/80 text-ink-muted hover:border-line-strong hover:text-ink",
            )}
            title={isMicActive ? "Live microphone voice reactivity active" : "Enable microphone voice reactivity"}
          >
            {isMicActive ? <Mic className="h-3.5 w-3.5" /> : <MicOff className="h-3.5 w-3.5" />}
            <span>{isMicActive ? `VOICE ACTIVE (${micVolume}%)` : "MIC REACTIVE"}</span>
          </button>

          {/* Theme Color Selector */}
          <div className="flex items-center gap-1 rounded-xl border border-line bg-surface-raised/80 p-1 shadow-md">
            {(Object.keys(THEME_PALETTES) as CoreColorTheme[]).map((themeKey) => (
              <button
                key={themeKey}
                type="button"
                onClick={() => setSelectedTheme(themeKey)}
                className={cn(
                  "h-5 w-5 rounded-lg transition-transform",
                  themeKey === "electric_cyan" && "bg-cyan-400 shadow-[0_0_8px_rgba(34,211,238,0.6)]",
                  themeKey === "stark_gold" && "bg-amber-400 shadow-[0_0_8px_rgba(251,191,36,0.6)]",
                  themeKey === "quantum_violet" && "bg-purple-400 shadow-[0_0_8px_rgba(192,132,252,0.6)]",
                  themeKey === "matrix_emerald" && "bg-emerald-400 shadow-[0_0_8px_rgba(52,211,153,0.6)]",
                  selectedTheme === themeKey ? "scale-110 ring-2 ring-white" : "opacity-40 hover:opacity-100",
                )}
                title={THEME_PALETTES[themeKey].name}
              />
            ))}
          </div>

          {/* View Toggle */}
          {onToggleView && (
            <div className="flex items-center gap-1 rounded-xl border border-line bg-surface-raised/80 p-1 shadow-md">
              <button
                type="button"
                onClick={() => onToggleView("hologram")}
                className={cn(
                  "flex h-6 items-center gap-1 rounded-lg px-2 font-mono text-[10px]",
                  activeView === "hologram" ? "bg-accent/25 text-accent font-semibold" : "text-ink-faint hover:text-ink",
                )}
                title="Full Holographic Center View"
              >
                <Eye className="h-3 w-3" />
                <span className="hidden md:inline">Core</span>
              </button>
              <button
                type="button"
                onClick={() => onToggleView("split")}
                className={cn(
                  "flex h-6 items-center gap-1 rounded-lg px-2 font-mono text-[10px]",
                  activeView === "split" ? "bg-accent/25 text-accent font-semibold" : "text-ink-faint hover:text-ink",
                )}
                title="Split 3D Core + Chat"
              >
                <Sparkles className="h-3 w-3" />
                <span className="hidden md:inline">Split</span>
              </button>
              <button
                type="button"
                onClick={() => onToggleView("chat")}
                className={cn(
                  "flex h-6 items-center gap-1 rounded-lg px-2 font-mono text-[10px]",
                  activeView === "chat" ? "bg-accent/25 text-accent font-semibold" : "text-ink-faint hover:text-ink",
                )}
                title="Full Chat View"
              >
                <MessageSquare className="h-3 w-3" />
                <span className="hidden md:inline">Chat</span>
              </button>
            </div>
          )}

          {/* Fullscreen Button */}
          <button
            type="button"
            onClick={() => setIsFullscreen((prev) => !prev)}
            className="flex h-7 w-7 items-center justify-center rounded-xl border border-line bg-surface-raised/80 text-ink-muted transition-colors hover:border-line-strong hover:text-ink shadow-md"
            title={isFullscreen ? "Exit Fullscreen" : "Fullscreen Neural Hologram"}
          >
            {isFullscreen ? <Minimize2 className="h-3.5 w-3.5" /> : <Maximize2 className="h-3.5 w-3.5" />}
          </button>
        </div>
      </div>

      {/* 3D WebGL Canvas */}
      <div
        ref={mountRef}
        className="h-full w-full flex-1 cursor-grab active:cursor-grabbing"
        title="Interactive 3D Neural Sphere (Move cursor to rotate 3D parallax)"
      />

      {/* Floating HUD Telemetry Readouts (Bottom) */}
      <div className="absolute bottom-3 inset-x-4 z-20 flex flex-wrap items-center justify-between gap-2 pointer-events-none">
        {/* Left Telemetry: Neural Link & Synapse Count */}
        <div className="flex items-center gap-2.5 rounded-xl border border-line bg-surface-raised/85 px-3 py-1.5 backdrop-blur-md shadow-lg">
          <Activity className={cn("h-4 w-4 animate-pulse", activeTheme.textTone)} />
          <div className="font-mono text-[10px] leading-tight">
            <span className="block font-bold text-ink">SYNAPSE LATTICE: 4.8K NODES · 8.6K AXONS</span>
            <span className="text-ink-faint">TOPOLOGY: SPHERICAL PLEXUS MATRIX</span>
          </div>
        </div>

        {/* Dynamic Cardiac Heartbeat Telemetry */}
        <div className="flex items-center gap-2 rounded-xl border border-line bg-surface-raised/85 px-3 py-1.5 backdrop-blur-md shadow-lg">
          <Heart className={cn("h-4 w-4 animate-pulse text-red-400")} />
          <div className="font-mono text-[10px] leading-tight">
            <div className="flex items-center gap-1.5">
              <span className="font-bold text-ink">RHYTHM: {currentBpm} BPM</span>
              <span className={cn("text-[9px] font-semibold px-1 rounded bg-surface-hover", activeTheme.textTone)}>
                {getBpmLabel(currentBpm)}
              </span>
            </div>
            <span className="text-ink-faint">CARDIAC PHASE: DUAL-PUMP SYSTOLIC</span>
          </div>
        </div>

        {/* Center/Right Live Voice / Speech Amplitude Meter */}
        <div className="flex items-center gap-2 rounded-xl border border-line bg-surface-raised/85 px-3 py-1.5 backdrop-blur-md shadow-lg">
          <Volume2 className={cn("h-4 w-4", activeTheme.textTone)} />
          <div className="flex items-center gap-1 font-mono text-[10px]">
            <span className="text-ink-faint">VOICE SURGE:</span>
            <div className="flex items-center gap-0.5">
              {[15, 30, 45, 60, 75, 90].map((step) => (
                <span
                  key={step}
                  className={cn(
                    "h-3.5 w-1.5 rounded-sm transition-all duration-75",
                    micVolume >= step ? "bg-cyan-400 shadow-[0_0_8px_rgba(34,211,238,0.8)]" : "bg-line",
                  )}
                />
              ))}
            </div>
            <span className="ml-1 font-bold text-ink">{micVolume}%</span>
          </div>
        </div>
      </div>
    </div>
  );
}
