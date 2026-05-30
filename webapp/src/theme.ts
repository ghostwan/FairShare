import { createTheme } from "@mui/material/styles";

// Mirror the Material 3 palette used by the Android app so that screenshots
// from either client look like the same product. The Android side uses the
// dynamic-color primary on API 31+ and falls back to a blue close to
// Material's #1976d2 elsewhere — we lock that fallback in on the web.
export const theme = createTheme({
  palette: {
    mode: "light",
    primary: { main: "#1976d2" },
    secondary: { main: "#9c27b0" },
    background: {
      default: "#fafafa",
      paper: "#ffffff",
    },
  },
  shape: { borderRadius: 12 },
  typography: {
    fontFamily:
      "system-ui, -apple-system, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif",
  },
});
