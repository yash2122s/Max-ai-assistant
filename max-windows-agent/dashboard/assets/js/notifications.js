class NotificationCenter {
    constructor() {
        this.containerId = "notifications-container";
    }

    getContainer() {
        let container = document.getElementById(this.containerId);
        if (!container) {
            container = document.createElement("div");
            container.id = this.containerId;
            document.body.appendChild(container);
        }
        return container;
    }

    show(type, title, message) {
        const container = this.getContainer();
        
        const toast = document.createElement("div");
        toast.className = `toast ${type}`;
        
        toast.innerHTML = `
            <div class="toast-body">
                <div class="toast-title">${title}</div>
                <div class="toast-desc">${message}</div>
            </div>
            <button class="toast-close" aria-label="Close alert">&times;</button>
        `;
        
        container.appendChild(toast);
        
        const closeBtn = toast.querySelector(".toast-close");
        const dismissToast = () => {
            if (toast.parentNode === container) {
                toast.classList.add("fade-out");
                toast.addEventListener("animationend", () => {
                    if (toast.parentNode === container) {
                        container.removeChild(toast);
                    }
                });
            }
        };
        
        closeBtn.onclick = dismissToast;
        
        // Auto-dismiss after 4.5 seconds
        setTimeout(dismissToast, 4500);
    }

    success(title, message) {
        this.show("success", title, message);
    }

    error(title, message) {
        this.show("error", title, message);
    }

    warning(title, message) {
        this.show("warning", title, message);
    }

    info(title, message) {
        this.show("info", title, message);
    }
}

const notifications = new NotificationCenter();
export default notifications;
export { notifications as NotificationCenter };
