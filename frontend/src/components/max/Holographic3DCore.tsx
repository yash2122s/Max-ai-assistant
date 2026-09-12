import { useEffect, useRef } from "react";
import * as THREE from "three";
import { useAgentStore } from "@/store/useAgentStore";
import type { OrbPhase } from "@/lib/types";

interface Holographic3DCoreProps {
  width?: number | string;
  height?: number | string;
  interactive?: boolean;
}

const PHASE_PALETTES: Record<
  OrbPhase,
  { core: number; accent: number; particle: number; lightning: number; heartRateHz: number; pulseScale: number }
> = {
  standby: {
    core: 0xffb300, // Golden Amber
    accent: 0xff8f00,
    particle: 0xffe082,
    lightning: 0xfff2b3,
    heartRateHz: 0.82, // ~49 BPM slow hypnotic resting rhythm
    pulseScale: 0.12,
  },
  listening: {
    core: 0x10b981, // Electric Emerald
    accent: 0x059669,
    particle: 0x6ee7b7,
    lightning: 0xecfdf5,
    heartRateHz: 1.65, // ~99 BPM alert
    pulseScale: 0.18,
  },
  thinking: {
    core: 0xa855f7, // Neural Violet
    accent: 0x7c3aed,
    particle: 0xd8b4fe,
    lightning: 0xf3e8ff,
    heartRateHz: 2.2, // ~132 BPM high cognition
    pulseScale: 0.22,
  },
  executing: {
    core: 0x00d1ff, // Cyan Electric
    accent: 0x0284c7,
    particle: 0x7dd3fc,
    lightning: 0xe0f2fe,
    heartRateHz: 2.6, // ~156 BPM execution drive
    pulseScale: 0.25,
  },
  speaking: {
    core: 0x38bdf8, // Luminous Azure
    accent: 0x2563eb,
    particle: 0x93c5fd,
    lightning: 0xffffff,
    heartRateHz: 2.4, // ~144 BPM resonant speech
    pulseScale: 0.24,
  },
  waiting_confirmation: {
    core: 0xf59e0b, // Solar Amber
    accent: 0xd97706,
    particle: 0xfcd34d,
    lightning: 0xfef3c7,
    heartRateHz: 1.1, // ~66 BPM
    pulseScale: 0.14,
  },
  error: {
    core: 0xf43f5e, // Crimson
    accent: 0xe11d48,
    particle: 0xfda4af,
    lightning: 0xffe4e6,
    heartRateHz: 0.75, // Arrhythmic
    pulseScale: 0.1,
  },
};

/**
 * Computes dual-pump cardiac heartbeat
 */
function computeHeartbeat(phase: number): { pulseScale: number; energyGlow: number } {
  if (phase >= 0 && phase < 0.16) {
    const t = phase / 0.16;
    const lub = Math.sin(t * Math.PI);
    return { pulseScale: Math.pow(lub, 1.8) * 0.22, energyGlow: Math.pow(lub, 1.2) * 1.0 };
  } else if (phase >= 0.22 && phase < 0.40) {
    const t = (phase - 0.22) / 0.18;
    const dub = Math.sin(t * Math.PI);
    return { pulseScale: Math.pow(dub, 2.0) * 0.12, energyGlow: Math.pow(dub, 1.5) * 0.55 };
  }
  const t = (phase - 0.40) / 0.60;
  return { pulseScale: Math.sin(t * Math.PI * 2) * 0.015, energyGlow: 0.04 * (1 - t) };
}

export function Holographic3DCore({
  width = "100%",
  height = "100%",
  interactive = true,
}: Holographic3DCoreProps) {
  const mountRef = useRef<HTMLDivElement>(null);
  const phase = useAgentStore((s) => s.orbPhase);
  const phaseRef = useRef(phase);
  phaseRef.current = phase;

  useEffect(() => {
    const container = mountRef.current;
    if (!container) return;

    let animFrameId: number;
    const w = container.clientWidth || 300;
    const h = container.clientHeight || 300;

    const scene = new THREE.Scene();
    const camera = new THREE.PerspectiveCamera(70, w / h, 0.1, 1000);
    camera.position.z = 4.2;

    const renderer = new THREE.WebGLRenderer({ alpha: true, antialias: true });
    renderer.setSize(w, h);
    renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
    renderer.setClearColor(0x000000, 0);
    container.appendChild(renderer.domElement);

    const palette = PHASE_PALETTES[phaseRef.current] ?? PHASE_PALETTES.standby;
    const coreColor = new THREE.Color(palette.core);
    const particleColor = new THREE.Color(palette.particle);
    const lightningColor = new THREE.Color(palette.lightning);

    // Glowing Particle Texture
    const createParticleTexture = () => {
      const canvas = document.createElement("canvas");
      canvas.width = 64;
      canvas.height = 64;
      const ctx = canvas.getContext("2d")!;
      const grad = ctx.createRadialGradient(32, 32, 0, 32, 32, 32);
      grad.addColorStop(0, "rgba(255,255,255,1)");
      grad.addColorStop(0.2, "rgba(200,245,255,0.9)");
      grad.addColorStop(0.5, "rgba(0,217,255,0.4)");
      grad.addColorStop(1, "rgba(0,0,0,0)");
      ctx.fillStyle = grad;
      ctx.fillRect(0, 0, 64, 64);
      return new THREE.CanvasTexture(canvas);
    };

    const particleTexture = createParticleTexture();

    // 1. Ethereal Spherical Neural Lattice with wide gaps (1400 points)
    const nodeCount = 1400;
    const positions = new Float32Array(nodeCount * 3);
    const basePositions = new Float32Array(nodeCount * 3);
    const normals = new Float32Array(nodeCount * 3);
    const hubs = new Float32Array(nodeCount);
    const layers = new Float32Array(nodeCount);

    const goldenRatio = (1 + Math.sqrt(5)) / 2;
    for (let i = 0; i < nodeCount; i++) {
      const theta = (2 * Math.PI * i) / goldenRatio;
      const y = 1 - (i / (nodeCount - 1)) * 2;
      const radiusAtY = Math.sqrt(Math.max(0, 1 - y * y));
      const x = Math.cos(theta) * radiusAtY;
      const z = Math.sin(theta) * radiusAtY;

      // Clear spatial voids / gaps between concentric layers:
      // Inner Nucleus (0.38-0.52), Gap, Middle Shell (1.18-1.38), Gap, Outer Orbitals (1.80-2.05)
      const rand = Math.random();
      let baseR = 1.25;
      let layerType = 1;
      if (rand < 0.14) {
        baseR = 0.40 + Math.random() * 0.14;
        layerType = 0;
      } else if (rand > 0.82) {
        baseR = 1.85 + Math.random() * 0.25;
        layerType = 2;
      } else {
        baseR = 1.22 + Math.random() * 0.18;
        layerType = 1;
      }

      const fold = Math.sin(x * 4 + y * 4) * 0.04;
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

      const norm = Math.hypot(px, py, pz) || 1;
      normals[i * 3] = px / norm;
      normals[i * 3 + 1] = py / norm;
      normals[i * 3 + 2] = pz / norm;

      layers[i] = layerType;
      hubs[i] = Math.random() < 0.04 ? 1 : 0;
    }

    const sphereGeo = new THREE.BufferGeometry();
    sphereGeo.setAttribute("position", new THREE.BufferAttribute(positions, 3));

    const sphereMat = new THREE.PointsMaterial({
      color: particleColor,
      size: 0.032,
      map: particleTexture,
      transparent: true,
      opacity: 0.9,
      blending: THREE.AdditiveBlending,
      depthWrite: false,
    });
    const sphereMesh = new THREE.Points(sphereGeo, sphereMat);
    scene.add(sphereMesh);

    // 2. Synaptic Hubs
    const hubPositions: number[] = [];
    for (let i = 0; i < nodeCount; i++) {
      if (hubs[i] === 1) {
        hubPositions.push(positions[i * 3], positions[i * 3 + 1], positions[i * 3 + 2]);
      }
    }
    const hubGeo = new THREE.BufferGeometry();
    hubGeo.setAttribute("position", new THREE.Float32BufferAttribute(hubPositions, 3));
    const hubMat = new THREE.PointsMaterial({
      color: lightningColor,
      size: 0.09,
      map: particleTexture,
      transparent: true,
      opacity: 0.95,
      blending: THREE.AdditiveBlending,
    });
    const hubMesh = new THREE.Points(hubGeo, hubMat);
    scene.add(hubMesh);

    // 3. Airy Axonal Plexus Lines with clear geometric gaps
    const plexusLinePts: THREE.Vector3[] = [];
    for (let i = 0; i < nodeCount; i += 2) {
      let conn = 0;
      const p1 = new THREE.Vector3(positions[i * 3], positions[i * 3 + 1], positions[i * 3 + 2]);
      const l1 = layers[i];
      for (let j = i + 1; j < Math.min(i + 35, nodeCount); j++) {
        if (l1 !== layers[j] && Math.random() > 0.06) continue;
        const p2 = new THREE.Vector3(positions[j * 3], positions[j * 3 + 1], positions[j * 3 + 2]);
        const distSq = p1.distanceToSquared(p2);
        if (distSq >= 0.12 * 0.12 && distSq <= 0.40 * 0.40) {
          plexusLinePts.push(p1, p2);
          conn++;
          if (conn >= 3) break;
        }
      }
    }
    const plexusGeo = new THREE.BufferGeometry().setFromPoints(plexusLinePts);
    const plexusMat = new THREE.LineBasicMaterial({
      color: coreColor,
      transparent: true,
      opacity: 0.32,
      blending: THREE.AdditiveBlending,
    });
    const plexusMesh = new THREE.LineSegments(plexusGeo, plexusMat);
    scene.add(plexusMesh);

    // 4. Inner Glowing Nucleus Sphere (tight seed floating in center void)
    const glowGeo = new THREE.SphereGeometry(0.35, 24, 24);
    const glowMat = new THREE.MeshBasicMaterial({
      color: coreColor,
      transparent: true,
      opacity: 0.35,
    });
    const glowMesh = new THREE.Mesh(glowGeo, glowMat);
    scene.add(glowMesh);

    // 5. Data Rings
    const createRing = (radius: number, tube: number, opacity: number, speedX: number, speedY: number) => {
      const geo = new THREE.TorusGeometry(radius, tube, 2, 80);
      const mat = new THREE.MeshBasicMaterial({
        color: coreColor,
        transparent: true,
        opacity,
        wireframe: true,
      });
      const ring = new THREE.Mesh(geo, mat);
      scene.add(ring);
      return { mesh: ring, mat, speedX, speedY };
    };

    const rings = [
      createRing(1.65, 0.007, 0.4, 0.006, 0.009),
      createRing(1.95, 0.005, 0.25, -0.008, -0.005),
    ];

    // Lighting
    const ambientLight = new THREE.AmbientLight(0xffffff, 0.6);
    scene.add(ambientLight);
    const pointLight = new THREE.PointLight(coreColor, 3.0, 10);
    pointLight.position.set(0, 0, 0);
    scene.add(pointLight);

    // Mouse Interaction
    let targetX = 0;
    let targetY = 0;
    let currentX = 0;
    let currentY = 0;

    const handleMouseMove = (e: MouseEvent) => {
      if (!interactive) return;
      const rect = container.getBoundingClientRect();
      const x = (e.clientX - rect.left - rect.width / 2) / (rect.width / 2);
      const y = (e.clientY - rect.top - rect.height / 2) / (rect.height / 2);
      targetX = x * 0.35;
      targetY = y * 0.35;
    };

    if (interactive) {
      container.addEventListener("mousemove", handleMouseMove);
    }

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

    let clock = 0;
    let cardiacPhase = 0;

    const animate = () => {
      animFrameId = requestAnimationFrame(animate);
      const dt = 0.016;
      clock += dt;

      const curPalette = PHASE_PALETTES[phaseRef.current] ?? PHASE_PALETTES.standby;
      const targetCore = new THREE.Color(curPalette.core);
      const targetParticle = new THREE.Color(curPalette.particle);
      const targetLightning = new THREE.Color(curPalette.lightning);

      // Lerp colors
      sphereMat.color.lerp(targetParticle, 0.05);
      hubMat.color.lerp(targetLightning, 0.05);
      plexusMat.color.lerp(targetCore, 0.05);
      glowMat.color.lerp(targetCore, 0.05);
      pointLight.color.lerp(targetCore, 0.05);
      rings.forEach((r) => r.mat.color.lerp(targetCore, 0.05));

      // Cardiac phase progress with phase-dependent heart rate
      cardiacPhase = (cardiacPhase + curPalette.heartRateHz * dt) % 1.0;
      const { pulseScale, energyGlow } = computeHeartbeat(cardiacPhase);

      // Dynamic vertex radial displacement for cardiac contraction & expansion
      const posAttr = sphereGeo.attributes.position as THREE.BufferAttribute;
      const pArr = posAttr.array as Float32Array;
      for (let i = 0; i < nodeCount; i++) {
        const bx = basePositions[i * 3];
        const by = basePositions[i * 3 + 1];
        const bz = basePositions[i * 3 + 2];
        const nx = normals[i * 3];
        const ny = normals[i * 3 + 1];
        const nz = normals[i * 3 + 2];

        const rDisplace = pulseScale * 1.1;
        pArr[i * 3] = bx + nx * rDisplace;
        pArr[i * 3 + 1] = by + ny * rDisplace;
        pArr[i * 3 + 2] = bz + nz * rDisplace;
      }
      posAttr.needsUpdate = true;

      // Rotation
      const spinSpeed = 0.008 * (curPalette.heartRateHz / 0.82);
      sphereMesh.rotation.y += spinSpeed;
      hubMesh.rotation.y += spinSpeed;
      plexusMesh.rotation.y += spinSpeed;

      // Hub & Glow pulsation
      hubMat.size = 0.09 + energyGlow * 0.06;
      glowMat.opacity = 0.25 + energyGlow * 0.35;
      pointLight.intensity = 2.5 + energyGlow * 2.5;

      // Rings rotation
      rings.forEach((r) => {
        const speedBoost = curPalette.heartRateHz / 0.82;
        r.mesh.rotation.x += r.speedX * speedBoost;
        r.mesh.rotation.y += r.speedY * speedBoost;
      });

      // Scene parallax
      currentX += (targetX - currentX) * 0.05;
      currentY += (targetY - currentY) * 0.05;
      scene.rotation.y = currentX;
      scene.rotation.x = currentY;

      renderer.render(scene, camera);
    };

    animate();

    return () => {
      cancelAnimationFrame(animFrameId);
      resizeObserver.disconnect();
      if (interactive) {
        container.removeEventListener("mousemove", handleMouseMove);
      }
      if (container.contains(renderer.domElement)) {
        container.removeChild(renderer.domElement);
      }
      scene.clear();
      renderer.dispose();
    };
  }, [interactive]);

  return (
    <div
      ref={mountRef}
      style={{ width, height }}
      className="relative flex items-center justify-center overflow-hidden cursor-crosshair"
      aria-label="MAX 3D Holographic AI Neural Core"
    />
  );
}
