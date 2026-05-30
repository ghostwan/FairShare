import React from "react";
import ReactDOM from "react-dom/client";
import { ThemeProvider, CssBaseline } from "@mui/material";
import { BrowserRouter } from "react-router-dom";
import { App } from "./App";
import { theme } from "./theme";
import { installServiceWorkerBridge } from "./sync/serviceWorkerBridge";

// Listen for push / rotate messages from the service worker as soon
// as the bundle boots. Idempotent and silently no-ops when the SW
// API isn't available (eg. test runners).
installServiceWorkerBridge();

ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <ThemeProvider theme={theme}>
      <CssBaseline />
      <BrowserRouter>
        <App />
      </BrowserRouter>
    </ThemeProvider>
  </React.StrictMode>,
);
