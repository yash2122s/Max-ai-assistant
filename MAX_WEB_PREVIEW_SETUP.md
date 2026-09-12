# MAX — COMPOSE UI WEB BROWSER PREVIEW SETUP

## 1. Overview
The MAX Web Preview is a **development-only** visual preview environment designed for rapid iteration on MAX Jetpack Compose UI components, design tokens, color harmonies, and animations inside the browser.

### Strict Invariants & Security Guarantees:
- **Zero Production Coupling**: Does NOT alter or replace native Android Compose code.
- **Zero Secret Access**: Uses mock data and never accesses Gemini API keys, Room database, Android Keystore, or telephony services.
- **Pure Dev Tooling**: Designed for frontend design refinement and visual QA before device deployment.

---

## 2. Launching the Browser Preview

### Prerequisites:
- Node.js (v18+)
- npm

### Commands:
```bash
# Navigate to frontend directory
cd frontend

# Install dependencies (first time only)
npm install

# Start development preview server
npm run dev
```

### Access:
Open `http://localhost:5173` in your browser. Select the **`COMPOSE UI PREVIEW (DEV)`** tab in the top navigation bar.

---

## 3. Preview Components Available

1. **Jarvis Neural Orb Simulator (`JarvisNeuralOrb`)**:
   - Live interactive state switcher (`Ready`, `Listening`, `MAX Speaking`, `Processing / Thinking`, `MAX OFF`).
   - Dynamic Audio RMS slider (simulates real-time microphone voice reaction).
   - Canvas-based orbital particle simulation with mathematical parity to Android Compose `DrawScope`.
   - Dormant static core rendering when `MAX OFF` is selected.

2. **Design Tokens & Atoms (`MaxButton`, `MaxStatusBadge`, `MaxTextField`)**:
   - Button variants: `Primary`, `Secondary`, `Outline`, `Danger`.
   - Status badges: `CONNECTED` (emerald), `CONNECTING...` (amber), `DISCONNECTED` (rose), `OPTIMAL` (indigo).
   - Input fields: Single-line input and Multiline JSON / routine step editor.

3. **Cards & Widgets (`QuickActionCard`, `MemoryItemCard`)**:
   - Quick action shortcuts (Flashlight, Wi-Fi, Bluetooth).
   - Memory Wallet card with Topic, Category badge, Multiline content, FlowRow tag wrapping, and Pinned state.

4. **Home Layout Mockup**:
   - Phone frame layout simulating TopBar, status indicator, runtime toggle switch, chat bubbles (AI & User), and bottom voice composer.
