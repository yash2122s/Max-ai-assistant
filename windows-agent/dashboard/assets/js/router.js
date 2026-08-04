import AppState from "./state.js";

const ROUTES = {
    dashboard: () => import("./views/dashboard.js"),
    activity: () => import("./views/activity.js"),
    terminal: () => import("./views/terminal.js"),
    logs: () => import("./views/logs.js"),
    devices: () => import("./views/devices.js"),
    tools: () => import("./views/tools.js"),
    settings: () => import("./views/settings.js")
};

let activeViewModule = null;

export async function navigateTo(routeHash) {
    const cleanHash = (routeHash || "dashboard").replace(/^#/, "");
    const loader = ROUTES[cleanHash] || ROUTES["dashboard"];
    
    const contentFrame = document.getElementById("content-frame");
    if (!contentFrame) return;

    // 1. Terminate previously active view
    if (activeViewModule) {
        try {
            if (typeof activeViewModule.destroy === "function") {
                activeViewModule.destroy();
            } else if (typeof activeViewModule.hide === "function") {
                activeViewModule.hide();
            }
        } catch (e) {
            console.error("Error tearing down view:", e);
        }
    }

    // 2. Highlight matching sidebar element
    document.querySelectorAll(".menu-item").forEach(item => {
        if (item.getAttribute("data-page") === cleanHash) {
            item.classList.add("active");
        } else {
            item.classList.remove("active");
        }
    });

    // 3. Update top title bar text
    const headerTitle = document.getElementById("header-view-title");
    if (headerTitle) {
        headerTitle.textContent = cleanHash.charAt(0).toUpperCase() + cleanHash.slice(1);
    }

    // 4. Load the view dynamically
    try {
        const viewModule = await loader();
        activeViewModule = viewModule.default || viewModule;
        
        // Execute initialization and show hooks
        if (typeof activeViewModule.init === "function") {
            await activeViewModule.init(contentFrame);
        }
        if (typeof activeViewModule.show === "function") {
            await activeViewModule.show(contentFrame);
        }
    } catch (err) {
        console.error(`Failed to load view for route ${cleanHash}:`, err);
        contentFrame.innerHTML = `
            <div class="card" style="border-color: var(--error-color);">
                <div class="card-header">
                    <span class="card-title" style="color: var(--error-color);">Navigation Error</span>
                </div>
                <div>Failed to render route <strong>#${cleanHash}</strong>. Details: ${err.message}</div>
            </div>
        `;
    }
}

export function initRouter() {
    window.addEventListener("hashchange", () => {
        navigateTo(window.location.hash);
    });

    // Listen to direct click commands in the menu
    document.querySelectorAll(".menu-item").forEach(item => {
        item.addEventListener("click", () => {
            const page = item.getAttribute("data-page");
            window.location.hash = page;
        });
    });

    // Handle cold boot redirect to initial default hash
    navigateTo(window.location.hash || "dashboard");
}
