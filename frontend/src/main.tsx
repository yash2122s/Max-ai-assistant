import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import App from "./App";
import { startAgentConnection, stopAgentConnection } from "@/lib/bootstrap";
import "./index.css";

const rootEl = document.getElementById("root");
if (!rootEl) throw new Error("Root element #root not found");

// Starts the single SSE connection + HTTP snapshot + polling. Cleanup happens
// on beforeunload so no dangling connections/listeners survive navigation.
startAgentConnection();
window.addEventListener("beforeunload", stopAgentConnection);

createRoot(rootEl).render(
  <StrictMode>
    <App />
  </StrictMode>,
);