import AppState from "./state.js";
import { connect } from "./event_stream.js";
import { initRouter } from "./router.js";

// DOM Elements
const devModeToggle = document.getElementById("dev-mode-toggle");
const themeSelector = document.getElementById("theme-selector");
const themeToggleBtn = document.getElementById("theme-toggle-btn");
const themeIconSun = document.getElementById("theme-icon-sun");
const themeIconMoon = document.getElementById("theme-icon-moon");
const themeBtnLabel = document.getElementById("theme-btn-label");
const statusBadge = document.getElementById("connection-status-badge");
const themeLink = document.getElementById("theme-link");

// 1. Subscribe to AppState updates
AppState.subscribe(state => {
    // Sync WebSocket connection indicators
    if (statusBadge) {
        statusBadge.textContent = state.connectionStatus.charAt(0).toUpperCase() + state.connectionStatus.slice(1);
        statusBadge.className = `status-badge ${state.connectionStatus}`;
    }

    // Sync Developer Mode checkbox
    if (devModeToggle && devModeToggle.checked !== state.developerMode) {
        devModeToggle.checked = state.developerMode;
    }

    // Sync theme value dropdown and active link stylesheet
    if (themeSelector && themeSelector.value !== state.theme) {
        themeSelector.value = state.theme;
    }
    if (themeLink) {
        const targetHref = `/assets/css/themes/${state.theme}.css`;
        if (themeLink.getAttribute("href") !== targetHref) {
            themeLink.setAttribute("href", targetHref);
        }
    }
    if (themeBtnLabel && themeIconSun && themeIconMoon) {
        if (state.theme === "light") {
            themeBtnLabel.textContent = "Light Mode";
            themeIconSun.style.display = "inline";
            themeIconMoon.style.display = "none";
        } else {
            themeBtnLabel.textContent = "Dark Mode";
            themeIconSun.style.display = "none";
            themeIconMoon.style.display = "inline";
        }
    }
});

// 2. Bind change listeners to interactive options
if (devModeToggle) {
    devModeToggle.addEventListener("change", (e) => {
        const checked = e.target.checked;
        localStorage.setItem("dev-mode", checked);
        AppState.set({ developerMode: checked });
    });
}

if (themeSelector) {
    themeSelector.addEventListener("change", (e) => {
        const themeVal = e.target.value;
        localStorage.setItem("theme", themeVal);
        AppState.set({ theme: themeVal });
    });
}

if (themeToggleBtn) {
    themeToggleBtn.addEventListener("click", () => {
        const currentTheme = AppState.get().theme || "dark";
        const newTheme = currentTheme === "dark" ? "light" : "dark";
        localStorage.setItem("theme", newTheme);
        AppState.set({ theme: newTheme });
    });
}

// 3. Bootstrap pipeline connection and router transitions
connect();
initRouter();
export default {};
