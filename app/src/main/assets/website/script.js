/**
 * MAX AI — 3D Interactive Experience
 * Three.js WebGL Core with MAX Brand Pillars, Ethereal Circular Neural Constellations,
 * 3D Card Physics Tilt & Holographic Specular Shine, 3D Phone Model Stage, and Web Audio FX.
 */

// Core Utilities
const $ = (s, root = document) => root.querySelector(s);
const $$ = (s, root = document) => [...root.querySelectorAll(s)];

// Toast Notification
const toast = $('#toast');
let toastTimer;
function notify(message) {
  if (!toast) return;
  toast.textContent = message;
  toast.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => toast.classList.remove('show'), 3000);
}

// Footer Year
const yearEl = $('#year');
if (yearEl) yearEl.textContent = new Date().getFullYear();

// Mobile Navigation
const menu = $('#mainNav');
const mobileBtn = $('#mobileMenu');
if (mobileBtn && menu) {
  mobileBtn.addEventListener('click', () => {
    const open = menu.classList.toggle('open');
    mobileBtn.setAttribute('aria-expanded', String(open));
  });
  $$('#mainNav a').forEach(link => {
    link.addEventListener('click', () => {
      menu.classList.remove('open');
      mobileBtn.setAttribute('aria-expanded', 'false');
    });
  });
}

// Download & Legal Links
const downloadLink = '#';
const androidBtn = $('#androidDownload');
if (androidBtn) {
  androidBtn.addEventListener('click', e => {
    if (downloadLink === '#') {
      e.preventDefault();
      notify('Android download link is not configured yet. Add your Google Play URL in script.js.');
      soundFX.playSnap();
    }
  });
}

const privacyBtn = $('#privacyLink');
if (privacyBtn) {
  privacyBtn.addEventListener('click', e => {
    e.preventDefault();
    notify('Add your published privacy policy URL before launch.');
  });
}

const termsBtn = $('#termsLink');
if (termsBtn) {
  termsBtn.addEventListener('click', e => {
    e.preventDefault();
    notify('Add your published terms of service URL before launch.');
  });
}

// Gentle Reveal Animation with IntersectionObserver
const revealTargets = $$('.feature-card, .step, .price-card, .faq-list details, .section-heading, .faq-heading');
revealTargets.forEach(el => el.classList.add('reveal'));
if ('IntersectionObserver' in window) {
  const observer = new IntersectionObserver(entries => {
    entries.forEach(entry => {
      if (entry.isIntersecting) {
        entry.target.classList.add('visible');
        observer.unobserve(entry.target);
      }
    });
  }, { threshold: 0.1 });
  revealTargets.forEach(el => observer.observe(el));
} else {
  revealTargets.forEach(el => el.classList.add('visible'));
}

/* ==========================================================================
   HELPER: Circular Ethereal Glow Texture for Three.js Point Particles
   ========================================================================== */
function createGlowTexture() {
  const canvas = document.createElement('canvas');
  canvas.width = 64;
  canvas.height = 64;
  const ctx = canvas.getContext('2d');
  const grad = ctx.createRadialGradient(32, 32, 0, 32, 32, 32);
  grad.addColorStop(0, 'rgba(255, 255, 255, 1)');
  grad.addColorStop(0.2, 'rgba(220, 205, 255, 0.9)');
  grad.addColorStop(0.5, 'rgba(164, 138, 255, 0.35)');
  grad.addColorStop(0.8, 'rgba(121, 232, 237, 0.1)');
  grad.addColorStop(1, 'rgba(0, 0, 0, 0)');
  ctx.fillStyle = grad;
  ctx.fillRect(0, 0, 64, 64);
  return new THREE.CanvasTexture(canvas);
}

/* ==========================================================================
   MODULE 1: Web Audio FX Synthesizer (Native Web Audio API, Zero Assets)
   ========================================================================== */
class SoundFXManager {
  constructor() {
    this.ctx = null;
    this.enabled = false;
    this.toggleBtn = $('#soundToggle');
    this.initUI();
  }

  ensureContext() {
    if (!this.ctx) {
      const AudioContext = window.AudioContext || window.webkitAudioContext;
      if (AudioContext) {
        this.ctx = new AudioContext();
      }
    }
    if (this.ctx && this.ctx.state === 'suspended') {
      this.ctx.resume();
    }
  }

  initUI() {
    if (!this.toggleBtn) return;
    this.toggleBtn.addEventListener('click', () => {
      this.ensureContext();
      this.enabled = !this.enabled;
      this.toggleBtn.classList.toggle('active', this.enabled);
      const icon = this.toggleBtn.querySelector('.sound-icon');
      if (icon) icon.textContent = this.enabled ? '🔊' : '🔈';
      if (this.enabled) {
        this.playChime(660, 'sine', 0.15);
        notify('3D Audio Effects: ON');
      } else {
        notify('3D Audio Effects: OFF');
      }
    });
  }

  playChime(freq = 520, type = 'sine', volume = 0.12) {
    if (!this.enabled) return;
    try {
      this.ensureContext();
      if (!this.ctx) return;
      const osc = this.ctx.createOscillator();
      const gain = this.ctx.createGain();
      const filter = this.ctx.createBiquadFilter();

      filter.type = 'lowpass';
      filter.frequency.setValueAtTime(1400, this.ctx.currentTime);

      osc.type = type;
      osc.frequency.setValueAtTime(freq, this.ctx.currentTime);
      osc.frequency.exponentialRampToValueAtTime(freq * 1.5, this.ctx.currentTime + 0.35);

      gain.gain.setValueAtTime(volume, this.ctx.currentTime);
      gain.gain.exponentialRampToValueAtTime(0.0001, this.ctx.currentTime + 0.5);

      osc.connect(filter);
      filter.connect(gain);
      gain.connect(this.ctx.destination);

      osc.start();
      osc.stop(this.ctx.currentTime + 0.5);
    } catch (_) {}
  }

  playPulse() {
    if (!this.enabled) return;
    try {
      this.ensureContext();
      if (!this.ctx) return;
      [440, 660, 880].forEach((freq, idx) => {
        setTimeout(() => this.playChime(freq, 'sine', 0.1), idx * 60);
      });
    } catch (_) {}
  }

  playHover() {
    if (!this.enabled) return;
    try {
      this.ensureContext();
      if (!this.ctx) return;
      const osc = this.ctx.createOscillator();
      const gain = this.ctx.createGain();
      osc.type = 'triangle';
      osc.frequency.setValueAtTime(320, this.ctx.currentTime);
      osc.frequency.exponentialRampToValueAtTime(480, this.ctx.currentTime + 0.08);

      gain.gain.setValueAtTime(0.03, this.ctx.currentTime);
      gain.gain.exponentialRampToValueAtTime(0.0001, this.ctx.currentTime + 0.1);

      osc.connect(gain);
      gain.connect(this.ctx.destination);
      osc.start();
      osc.stop(this.ctx.currentTime + 0.1);
    } catch (_) {}
  }

  playSnap() {
    if (!this.enabled) return;
    this.playChime(780, 'sine', 0.08);
  }
}
const soundFX = new SoundFXManager();


/* ==========================================================================
   MODULE 2: 3D Interactive MAX AI Core (Three.js WebGL Hero Canvas)
   ========================================================================== */
function initHeroCore3D() {
  const canvas = $('#hero-core-canvas');
  const container = $('#heroArt');
  if (!canvas || !container || typeof THREE === 'undefined') return;

  const glowTexture = createGlowTexture();
  const scene = new THREE.Scene();
  const camera = new THREE.PerspectiveCamera(45, container.clientWidth / container.clientHeight, 0.1, 100);
  camera.position.z = 11;

  const renderer = new THREE.WebGLRenderer({
    canvas,
    alpha: true,
    antialias: true,
    powerPreference: 'high-performance'
  });
  renderer.setSize(container.clientWidth, container.clientHeight);
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));

  // Lighting
  const ambientLight = new THREE.AmbientLight(0x2a1f4e, 1.8);
  scene.add(ambientLight);

  const keyLight = new THREE.PointLight(0xa48aff, 3.2, 35);
  keyLight.position.set(6, 6, 8);
  scene.add(keyLight);

  const rimLight = new THREE.PointLight(0x79e8ed, 2.6, 30);
  rimLight.position.set(-6, -4, 6);
  scene.add(rimLight);

  const magentaLight = new THREE.PointLight(0xff8fd2, 2.2, 30);
  magentaLight.position.set(0, -6, -4);
  scene.add(magentaLight);

  // Core Group
  const coreGroup = new THREE.Group();
  scene.add(coreGroup);

  // 1. Outer Faceted Geodesic Icosahedron Crystal
  const icoGeo = new THREE.IcosahedronGeometry(2.35, 1);
  const icoMat = new THREE.MeshPhysicalMaterial({
    color: 0x9966ff,
    emissive: 0x412275,
    emissiveIntensity: 0.55,
    roughness: 0.15,
    metalness: 0.3,
    transmission: 0.65,
    transparent: true,
    opacity: 0.65,
    wireframe: false
  });
  const icoMesh = new THREE.Mesh(icoGeo, icoMat);
  coreGroup.add(icoMesh);

  // 2. Faceted Wireframe Overlay with glowing edge vertices
  const wireGeo = new THREE.IcosahedronGeometry(2.38, 1);
  const wireMat = new THREE.MeshBasicMaterial({
    color: 0xcbb3ff,
    wireframe: true,
    transparent: true,
    opacity: 0.45
  });
  const wireMesh = new THREE.Mesh(wireGeo, wireMat);
  coreGroup.add(wireMesh);

  // 3. Volumetric Inner Breathing Quantum Nucleus
  const innerGeo = new THREE.SphereGeometry(1.35, 32, 32);
  const innerMat = new THREE.MeshStandardMaterial({
    color: 0xffffff,
    emissive: 0x9a6eff,
    emissiveIntensity: 1.6,
    roughness: 0.2,
    metalness: 0.1
  });
  const innerMesh = new THREE.Mesh(innerGeo, innerMat);
  coreGroup.add(innerMesh);

  // 4. Embedded 3D MAX Brand Bars in the Center
  const barsGroup = new THREE.Group();
  function createBrandBar(height, color, xOffset) {
    const barGeo = new THREE.CylinderGeometry(0.08, 0.08, height, 16);
    const barMat = new THREE.MeshStandardMaterial({
      color,
      emissive: color,
      emissiveIntensity: 1.4,
      roughness: 0.1,
      metalness: 0.1
    });
    const barMesh = new THREE.Mesh(barGeo, barMat);
    barMesh.position.x = xOffset;
    return barMesh;
  }
  const bar1 = createBrandBar(0.9, 0xbba6ff, -0.32);
  const bar2 = createBrandBar(1.6, 0x7be8e9, 0);
  const bar3 = createBrandBar(1.2, 0xbba6ff, 0.32);
  barsGroup.add(bar1);
  barsGroup.add(bar2);
  barsGroup.add(bar3);
  barsGroup.rotation.z = -0.16;
  innerMesh.add(barsGroup);

  // 5. Three 3D Gimbal Holographic Gyro Rings
  function createGimbalRing(radius, tube, color, rotX, rotY) {
    const group = new THREE.Group();
    const ringGeo = new THREE.TorusGeometry(radius, tube, 16, 120);
    const ringMat = new THREE.MeshBasicMaterial({
      color,
      transparent: true,
      opacity: 0.75
    });
    const ringMesh = new THREE.Mesh(ringGeo, ringMat);
    group.add(ringMesh);

    // Orbiting Satellite Beads on the Ring
    const beadCount = 3;
    const beads = [];
    for (let i = 0; i < beadCount; i++) {
      const beadGeo = new THREE.SphereGeometry(tube * 3.2, 16, 16);
      const beadMat = new THREE.MeshBasicMaterial({ color: 0xffffff });
      const bead = new THREE.Mesh(beadGeo, beadMat);
      group.add(bead);
      beads.push({ mesh: bead, angle: (i / beadCount) * Math.PI * 2 });
    }

    group.rotation.x = rotX;
    group.rotation.y = rotY;
    coreGroup.add(group);
    return { group, beads, radius };
  }

  const ring1 = createGimbalRing(3.6, 0.035, 0xa48aff, 0.55, 0.4);
  const ring2 = createGimbalRing(4.2, 0.028, 0x79e8ed, -0.65, 0.5);
  const ring3 = createGimbalRing(4.8, 0.022, 0xff8fd2, 1.1, -0.35);

  // 6. Swirling Particle Star Vortex around the Core
  const particleCount = 200;
  const particleGeo = new THREE.BufferGeometry();
  const particlePositions = new Float32Array(particleCount * 3);
  const particleSpeeds = [];

  for (let i = 0; i < particleCount; i++) {
    const angle = Math.random() * Math.PI * 2;
    const distance = 3.2 + Math.random() * 2.5;
    const height = (Math.random() - 0.5) * 3.2;
    particlePositions[i * 3] = Math.cos(angle) * distance;
    particlePositions[i * 3 + 1] = height;
    particlePositions[i * 3 + 2] = Math.sin(angle) * distance;
    particleSpeeds.push({
      angle,
      distance,
      height,
      speed: 0.008 + Math.random() * 0.015,
      yOffset: Math.random() * Math.PI * 2
    });
  }
  particleGeo.setAttribute('position', new THREE.BufferAttribute(particlePositions, 3));

  const particleMat = new THREE.PointsMaterial({
    size: 0.28,
    map: glowTexture,
    color: 0xd8cbff,
    transparent: true,
    opacity: 0.85,
    depthWrite: false,
    blending: THREE.AdditiveBlending
  });
  const particlePoints = new THREE.Points(particleGeo, particleMat);
  coreGroup.add(particlePoints);

  // 7. 3D Shockwave Ring for Click Bursts
  const shockGeo = new THREE.RingGeometry(0.1, 0.35, 64);
  const shockMat = new THREE.MeshBasicMaterial({
    color: 0x79e8ed,
    side: THREE.DoubleSide,
    transparent: true,
    opacity: 0,
    blending: THREE.AdditiveBlending
  });
  const shockMesh = new THREE.Mesh(shockGeo, shockMat);
  coreGroup.add(shockMesh);

  // Shockwave state
  let shockwave = { active: false, radius: 0.1, opacity: 0 };
  let pulseEnergy = 0;

  function triggerShockwave() {
    shockwave.active = true;
    shockwave.radius = 0.5;
    shockwave.opacity = 1.0;
    pulseEnergy = 1.0;
    soundFX.playPulse();
    const hint = $('#coreHint');
    if (hint) hint.style.opacity = '0.3';
  }

  // Interactive Drag & Pointer Parallax
  let isDragging = false;
  let prevMouseX = 0;
  let prevMouseY = 0;
  let rotVelocityX = 0.003;
  let rotVelocityY = 0.004;
  let targetTiltX = 0;
  let targetTiltY = 0;

  function onPointerDown(e) {
    isDragging = true;
    prevMouseX = e.clientX || (e.touches && e.touches[0].clientX) || 0;
    prevMouseY = e.clientY || (e.touches && e.touches[0].clientY) || 0;
    soundFX.playHover();
  }

  function onPointerMove(e) {
    const clientX = e.clientX || (e.touches && e.touches[0].clientX) || 0;
    const clientY = e.clientY || (e.touches && e.touches[0].clientY) || 0;
    
    if (isDragging) {
      const deltaX = clientX - prevMouseX;
      const deltaY = clientY - prevMouseY;
      rotVelocityY = deltaX * 0.007;
      rotVelocityX = deltaY * 0.007;
      prevMouseX = clientX;
      prevMouseY = clientY;
    } else {
      const rect = container.getBoundingClientRect();
      const normX = ((clientX - rect.left) / rect.width) * 2 - 1;
      const normY = -(((clientY - rect.top) / rect.height) * 2 - 1);
      targetTiltX = normY * 0.35;
      targetTiltY = normX * 0.45;
    }
  }

  function onPointerUp() {
    if (isDragging) {
      isDragging = false;
    }
  }

  canvas.addEventListener('mousedown', onPointerDown);
  window.addEventListener('mousemove', onPointerMove);
  window.addEventListener('mouseup', onPointerUp);

  canvas.addEventListener('touchstart', onPointerDown, { passive: true });
  window.addEventListener('touchmove', onPointerMove, { passive: true });
  window.addEventListener('touchend', onPointerUp);

  canvas.addEventListener('click', triggerShockwave);

  // Resize handler
  function onResize() {
    if (!container || !renderer) return;
    const w = container.clientWidth;
    const h = container.clientHeight;
    camera.aspect = w / h;
    camera.updateProjectionMatrix();
    renderer.setSize(w, h);
  }
  window.addEventListener('resize', onResize);

  // Visibility tracking for performance
  let isVisible = true;
  if ('IntersectionObserver' in window) {
    const obs = new IntersectionObserver(([entry]) => {
      isVisible = entry.isIntersecting;
    }, { threshold: 0.05 });
    obs.observe(container);
  }

  // Animation Loop
  let clock = new THREE.Clock();

  function animate() {
    requestAnimationFrame(animate);
    if (!isVisible) return;

    const delta = clock.getDelta();
    const time = clock.getElapsedTime();

    // Rotation + inertia damping
    coreGroup.rotation.y += rotVelocityY;
    coreGroup.rotation.x += rotVelocityX;
    rotVelocityY *= 0.96;
    rotVelocityX *= 0.96;
    if (Math.abs(rotVelocityY) < 0.001) rotVelocityY = 0.003;
    if (Math.abs(rotVelocityX) < 0.001) rotVelocityX = 0.001;

    // Smooth tilt toward pointer
    coreGroup.rotation.x += (targetTiltX - coreGroup.rotation.x) * 0.04;
    coreGroup.rotation.y += (targetTiltY - coreGroup.rotation.y) * 0.04;

    // Breathing inner core
    const pulse = 1 + Math.sin(time * 3) * 0.08 + pulseEnergy * 0.25;
    innerMesh.scale.set(pulse, pulse, pulse);
    icoMesh.rotation.y = time * 0.15;
    icoMesh.rotation.z = time * 0.1;
    wireMesh.rotation.y = -time * 0.12;

    // Animate Gimbal Rings & Satellite Beads
    [ring1, ring2, ring3].forEach((ring, idx) => {
      const speed = (idx + 1) * 0.015;
      ring.group.rotation.z += speed;
      ring.beads.forEach(bead => {
        bead.angle += speed * 2;
        bead.mesh.position.x = Math.cos(bead.angle) * ring.radius;
        bead.mesh.position.y = Math.sin(bead.angle) * ring.radius;
      });
    });

    // Swirling Particle Vortex
    const positions = particleGeo.attributes.position.array;
    for (let i = 0; i < particleCount; i++) {
      const p = particleSpeeds[i];
      p.angle += p.speed * (1 + pulseEnergy * 2);
      positions[i * 3] = Math.cos(p.angle) * p.distance;
      positions[i * 3 + 1] = p.height + Math.sin(time * 2 + p.yOffset) * 0.35;
      positions[i * 3 + 2] = Math.sin(p.angle) * p.distance;
    }
    particleGeo.attributes.position.needsUpdate = true;

    // Shockwave Expansion
    if (shockwave.active) {
      shockwave.radius += delta * 12;
      shockwave.opacity -= delta * 1.8;
      shockMesh.scale.set(shockwave.radius, shockwave.radius, shockwave.radius);
      shockMat.opacity = Math.max(0, shockwave.opacity);
      if (shockwave.opacity <= 0) {
        shockwave.active = false;
      }
    }

    if (pulseEnergy > 0) {
      pulseEnergy -= delta * 1.8;
      if (pulseEnergy < 0) pulseEnergy = 0;
    }

    renderer.render(scene, camera);
  }

  animate();
}


/* ==========================================================================
   MODULE 3: 3D Background WebGL Particle & Neural Constellation Canvas
   ========================================================================== */
function initBackground3D() {
  const canvas = $('#bg-canvas');
  if (!canvas || typeof THREE === 'undefined') return;

  const glowTexture = createGlowTexture();
  const scene = new THREE.Scene();
  const camera = new THREE.PerspectiveCamera(60, window.innerWidth / window.innerHeight, 0.1, 1000);
  camera.position.z = 80;

  const renderer = new THREE.WebGLRenderer({
    canvas,
    alpha: true,
    antialias: false,
    powerPreference: 'high-performance'
  });
  renderer.setSize(window.innerWidth, window.innerHeight);
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 1.5));

  // Particles Cloud
  const count = window.innerWidth < 768 ? 200 : 380;
  const geometry = new THREE.BufferGeometry();
  const positions = new Float32Array(count * 3);
  const colors = new Float32Array(count * 3);
  const velocities = [];

  const baseColor = new THREE.Color(0xa48aff);
  const accentColor = new THREE.Color(0x79e8ed);

  for (let i = 0; i < count; i++) {
    positions[i * 3] = (Math.random() - 0.5) * 160;
    positions[i * 3 + 1] = (Math.random() - 0.5) * 160;
    positions[i * 3 + 2] = (Math.random() - 0.5) * 100;

    const mixed = baseColor.clone().lerp(accentColor, Math.random());
    colors[i * 3] = mixed.r;
    colors[i * 3 + 1] = mixed.g;
    colors[i * 3 + 2] = mixed.b;

    velocities.push({
      x: (Math.random() - 0.5) * 0.04,
      y: (Math.random() - 0.5) * 0.04,
      z: (Math.random() - 0.5) * 0.02
    });
  }

  geometry.setAttribute('position', new THREE.BufferAttribute(positions, 3));
  geometry.setAttribute('color', new THREE.BufferAttribute(colors, 3));

  // Circular Glowing Particle Material
  const material = new THREE.PointsMaterial({
    size: 2.2,
    map: glowTexture,
    vertexColors: true,
    transparent: true,
    opacity: 0.55,
    depthWrite: false,
    blending: THREE.AdditiveBlending
  });

  const points = new THREE.Points(geometry, material);
  scene.add(points);

  // Dynamic Neural Filament Lines connecting adjacent nodes
  const maxLineConnections = 100;
  const linePositions = new Float32Array(maxLineConnections * 6);
  const lineGeo = new THREE.BufferGeometry();
  lineGeo.setAttribute('position', new THREE.BufferAttribute(linePositions, 3));

  const lineMat = new THREE.LineBasicMaterial({
    color: 0x9885ff,
    transparent: true,
    opacity: 0.12,
    blending: THREE.AdditiveBlending
  });
  const lines = new THREE.LineSegments(lineGeo, lineMat);
  scene.add(lines);

  // Mouse Parallax
  let mouseX = 0;
  let mouseY = 0;
  let targetCamX = 0;
  let targetCamY = 0;

  window.addEventListener('mousemove', e => {
    mouseX = (e.clientX / window.innerWidth) * 2 - 1;
    mouseY = -(e.clientY / window.innerHeight) * 2 + 1;
    targetCamX = mouseX * 8;
    targetCamY = mouseY * 6;
  });

  // Scroll parallax
  let scrollY = 0;
  window.addEventListener('scroll', () => {
    scrollY = window.scrollY;
  }, { passive: true });

  window.addEventListener('resize', () => {
    camera.aspect = window.innerWidth / window.innerHeight;
    camera.updateProjectionMatrix();
    renderer.setSize(window.innerWidth, window.innerHeight);
  });

  function animate() {
    requestAnimationFrame(animate);

    // Smooth camera lerp
    camera.position.x += (targetCamX - camera.position.x) * 0.03;
    camera.position.y += (targetCamY - camera.position.y - (scrollY * 0.02)) * 0.03;
    camera.lookAt(scene.position);

    // Update particles
    const posArr = geometry.attributes.position.array;
    for (let i = 0; i < count; i++) {
      posArr[i * 3] += velocities[i].x;
      posArr[i * 3 + 1] += velocities[i].y;
      posArr[i * 3 + 2] += velocities[i].z;

      if (posArr[i * 3] < -80) posArr[i * 3] = 80;
      if (posArr[i * 3] > 80) posArr[i * 3] = -80;
      if (posArr[i * 3 + 1] < -80) posArr[i * 3 + 1] = 80;
      if (posArr[i * 3 + 1] > 80) posArr[i * 3 + 1] = -80;
    }
    geometry.attributes.position.needsUpdate = true;

    // Update lines
    let lineIdx = 0;
    for (let i = 0; i < Math.min(count, 40); i++) {
      for (let j = i + 1; j < Math.min(count, 40); j++) {
        if (lineIdx >= maxLineConnections) break;
        const dx = posArr[i * 3] - posArr[j * 3];
        const dy = posArr[i * 3 + 1] - posArr[j * 3 + 1];
        const dz = posArr[i * 3 + 2] - posArr[j * 3 + 2];
        const distSq = dx * dx + dy * dy + dz * dz;

        if (distSq < 300) {
          const lPos = linePositions;
          lPos[lineIdx * 6] = posArr[i * 3];
          lPos[lineIdx * 6 + 1] = posArr[i * 3 + 1];
          lPos[lineIdx * 6 + 2] = posArr[i * 3 + 2];
          lPos[lineIdx * 6 + 3] = posArr[j * 3];
          lPos[lineIdx * 6 + 4] = posArr[j * 3 + 1];
          lPos[lineIdx * 6 + 5] = posArr[j * 3 + 2];
          lineIdx++;
        }
      }
    }
    lineGeo.setDrawRange(0, lineIdx * 2);
    lineGeo.attributes.position.needsUpdate = true;

    renderer.render(scene, camera);
  }

  animate();
}


/* ==========================================================================
   MODULE 4: 3D Miniature Core inside Phone Screen
   ========================================================================== */
function initPhoneCore3D() {
  const canvas = $('#phone-mini-canvas');
  const orb = $('#phoneOrb');
  if (!canvas || !orb || typeof THREE === 'undefined') return;

  const scene = new THREE.Scene();
  const camera = new THREE.PerspectiveCamera(45, 1, 0.1, 50);
  camera.position.z = 6;

  const renderer = new THREE.WebGLRenderer({
    canvas,
    alpha: true,
    antialias: true
  });
  renderer.setSize(128, 128);
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));

  // Lights
  const light = new THREE.PointLight(0xa48aff, 2.8, 20);
  light.position.set(3, 3, 5);
  scene.add(light);
  scene.add(new THREE.AmbientLight(0x31245e, 1.5));

  // Crystal Core
  const group = new THREE.Group();
  scene.add(group);

  const geo = new THREE.IcosahedronGeometry(1.25, 0);
  const mat = new THREE.MeshStandardMaterial({
    color: 0xb59bff,
    emissive: 0x4f268a,
    emissiveIntensity: 0.6,
    roughness: 0.2,
    metalness: 0.4
  });
  const mesh = new THREE.Mesh(geo, mat);
  group.add(mesh);

  const wireMat = new THREE.MeshBasicMaterial({
    color: 0x8ef5e2,
    wireframe: true,
    transparent: true,
    opacity: 0.6
  });
  const wireMesh = new THREE.Mesh(new THREE.IcosahedronGeometry(1.27, 0), wireMat);
  group.add(wireMesh);

  // Mini Orbit Ring
  const ringGeo = new THREE.TorusGeometry(1.8, 0.025, 16, 60);
  const ringMat = new THREE.MeshBasicMaterial({ color: 0x79e8ed, transparent: true, opacity: 0.8 });
  const ring = new THREE.Mesh(ringGeo, ringMat);
  ring.rotation.x = 1.1;
  group.add(ring);

  let isVisible = false;
  if ('IntersectionObserver' in window) {
    const obs = new IntersectionObserver(([entry]) => {
      isVisible = entry.isIntersecting;
    }, { threshold: 0.1 });
    obs.observe(orb);
  }

  function animate() {
    requestAnimationFrame(animate);
    if (!isVisible) return;
    group.rotation.y += 0.015;
    group.rotation.x += 0.008;
    const pulse = 1 + Math.sin(Date.now() * 0.004) * 0.06;
    mesh.scale.set(pulse, pulse, pulse);
    renderer.render(scene, camera);
  }

  animate();
}


/* ==========================================================================
   MODULE 5: 3D Physics Card Tilt & Specular Light Engine
   ========================================================================== */
function initCard3DTilt() {
  const cards = $$('.feature-card, .price-card, .float-card');

  cards.forEach(card => {
    let bounds;
    let isHovered = false;

    function onPointerEnter() {
      isHovered = true;
      bounds = card.getBoundingClientRect();
      soundFX.playHover();
    }

    function onPointerMove(e) {
      if (!isHovered || !bounds) return;
      const x = e.clientX - bounds.left;
      const y = e.clientY - bounds.top;

      const normX = (x / bounds.width) * 2 - 1;
      const normY = (y / bounds.height) * 2 - 1;

      const rotateX = -normY * 9;
      const rotateY = normX * 9;

      card.style.transform = `perspective(1000px) rotateX(${rotateX.toFixed(2)}deg) rotateY(${rotateY.toFixed(2)}deg) translateZ(12px)`;
      card.style.setProperty('--mouse-x', `${x}px`);
      card.style.setProperty('--mouse-y', `${y}px`);
    }

    function onPointerLeave() {
      isHovered = false;
      card.style.transform = '';
      card.style.removeProperty('--mouse-x');
      card.style.removeProperty('--mouse-y');
    }

    card.addEventListener('pointerenter', onPointerEnter);
    card.addEventListener('pointermove', onPointerMove);
    card.addEventListener('pointerleave', onPointerLeave);
  });

  // Hero Art Floating Badges 3D Parallax
  const heroArt = $('#heroArt');
  if (heroArt) {
    heroArt.addEventListener('pointermove', e => {
      const rect = heroArt.getBoundingClientRect();
      const normX = ((e.clientX - rect.left) / rect.width) * 2 - 1;
      const normY = ((e.clientY - rect.top) / rect.height) * 2 - 1;

      $$('.float-card', heroArt).forEach(card => {
        const depth = parseFloat(card.dataset.depth || '30');
        const moveX = normX * depth * 0.45;
        const moveY = normY * depth * 0.45;
        card.style.transform = `translate3d(${moveX}px, ${moveY}px, ${depth}px)`;
      });
    });

    heroArt.addEventListener('pointerleave', () => {
      $$('.float-card', heroArt).forEach(card => {
        const depth = parseFloat(card.dataset.depth || '30');
        card.style.transform = `translate3d(0, 0, ${depth}px)`;
      });
    });
  }

  // Feature 2: Interactive 3D Tumbling Studio Tiles
  const createVisual = $('.create-visual');
  if (createVisual) {
    createVisual.addEventListener('pointermove', e => {
      const rect = createVisual.getBoundingClientRect();
      const normX = ((e.clientX - rect.left) / rect.width) * 2 - 1;
      const normY = ((e.clientY - rect.top) / rect.height) * 2 - 1;

      $$('.create-tile', createVisual).forEach((tile, idx) => {
        const mult = (idx % 2 === 0 ? 1 : -1) * (14 + idx * 4);
        tile.style.transform = `translateZ(${30 + idx * 10}px) rotateY(${normX * mult}deg) rotateX(${-normY * mult}deg)`;
      });
    });

    createVisual.addEventListener('pointerleave', () => {
      $$('.create-tile', createVisual).forEach(tile => {
        tile.style.transform = '';
      });
    });
  }
}


/* ==========================================================================
   MODULE 6: 3D Interactive Phone Stage
   ========================================================================== */
function initPhone3DStage() {
  const stage = $('#phoneStage');
  const phone = $('#phone3d');
  const shadow = $('#phoneShadow');
  if (!stage || !phone) return;

  stage.addEventListener('pointermove', e => {
    const rect = stage.getBoundingClientRect();
    const x = e.clientX - rect.left;
    const y = e.clientY - rect.top;

    const normX = (x / rect.width) * 2 - 1;
    const normY = (y / rect.height) * 2 - 1;

    const rotY = -14 + normX * 16;
    const rotX = 10 - normY * 12;

    phone.style.transform = `perspective(1200px) rotateY(${rotY.toFixed(2)}deg) rotateX(${rotX.toFixed(2)}deg) translateZ(20px)`;
    if (shadow) {
      shadow.style.transform = `rotateX(60deg) skewX(${(-normX * 14).toFixed(1)}deg) scale(${1 + normY * 0.15})`;
    }
  });

  stage.addEventListener('pointerleave', () => {
    phone.style.transform = 'perspective(1200px) rotateY(-14deg) rotateX(10deg)';
    if (shadow) shadow.style.transform = 'rotateX(60deg)';
  });
}


/* ==========================================================================
   MODULE 7: 3D Cursor Aura Follower
   ========================================================================== */
function initCursorGlow() {
  const glow = $('#cursorGlow');
  if (!glow || window.matchMedia('(pointer: coarse)').matches) return;

  let currentX = window.innerWidth / 2;
  let currentY = window.innerHeight / 2;
  let targetX = currentX;
  let targetY = currentY;

  window.addEventListener('mousemove', e => {
    targetX = e.clientX;
    targetY = e.clientY;
    glow.style.opacity = '1';
  });

  window.addEventListener('mouseleave', () => {
    glow.style.opacity = '0';
  });

  function loop() {
    currentX += (targetX - currentX) * 0.12;
    currentY += (targetY - currentY) * 0.12;
    glow.style.left = `${currentX}px`;
    glow.style.top = `${currentY}px`;
    requestAnimationFrame(loop);
  }
  loop();

  $$('.button, .feature-card, .price-card, .brand, .sound-toggle').forEach(el => {
    el.addEventListener('pointerenter', () => {
      glow.style.width = '420px';
      glow.style.height = '420px';
    });
    el.addEventListener('pointerleave', () => {
      glow.style.width = '320px';
      glow.style.height = '320px';
    });
  });
}


/* ==========================================================================
   MODULE 8: Initialize All 3D Systems on DOM Ready
   ========================================================================== */
function startAll3D() {
  initBackground3D();
  initHeroCore3D();
  initPhoneCore3D();
  initCard3DTilt();
  initPhone3DStage();
  initCursorGlow();
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', startAll3D);
} else {
  startAll3D();
}
