import { useEffect, useState } from "react";
import {
  AppBar,
  Box,
  CircularProgress,
  Container,
  IconButton,
  Toolbar,
  Typography,
} from "@mui/material";
import { useLocation, useNavigate } from "react-router-dom";
import ArrowBackIcon from "@mui/icons-material/ArrowBack";
import SettingsIcon from "@mui/icons-material/Settings";
import RefreshIcon from "@mui/icons-material/Refresh";
import BarChartIcon from "@mui/icons-material/BarChart";
import { Settings } from "@/data/settings";
import { getDb } from "@/data/db";
import { syncNow } from "@/sync/coordinator";

/**
 * App-wide shell: top app bar with a contextual back button, a refresh
 * action when viewing an event, and a settings shortcut. Children fill
 * the rest of the viewport inside a Container.
 *
 * The refresh button doubles as the "Synchroniser maintenant" entry
 * point — same code path the visibility-change listener uses, so users
 * who don't trust the auto-refresh can still force it explicitly.
 */
export function AppShell({ children }: { children: React.ReactNode }) {
  const location = useLocation();
  const navigate = useNavigate();
  const [syncing, setSyncing] = useState(false);

  const onEvent = location.pathname.startsWith("/event/");
  const eventId = onEvent ? location.pathname.split("/")[2]! : null;
  const isRoot = location.pathname === "/" || location.pathname === "";
  const onEventSettings =
    eventId != null && location.pathname.endsWith("/settings");
  const onStats =
    eventId != null && location.pathname.endsWith("/stats");

  // Visibility-driven catch-up: every time the user comes back to the
  // tab (or the PWA), pull from the Worker so the event view stays
  // fresh. We avoid running it during the initial mount because the
  // pages already trigger their own load.
  useEffect(() => {
    if (!eventId) return;
    const onVisible = async () => {
      if (document.visibilityState !== "visible") return;
      if (!(await Settings.getAutoRefreshOnFocus())) return;
      try {
        await syncNow(eventId);
      } catch {
        // Surfaced inline via the refresh button; no toast spam.
      }
    };
    document.addEventListener("visibilitychange", onVisible);
    return () => document.removeEventListener("visibilitychange", onVisible);
  }, [eventId]);

  const handleRefresh = async () => {
    if (!eventId) return;
    setSyncing(true);
    try {
      await syncNow(eventId);
    } finally {
      setSyncing(false);
    }
  };

  // Back navigation: pop history when we have something to pop, but
  // fall back to the events list when the current entry was the very
  // first one in this tab (typical case: the user opened a
  // `fairshare://join?...` deep link or scanned a QR — `navigate(-1)`
  // would otherwise be a no-op or close the PWA). Mirrors Android's
  // navigation graph, which always keeps the events list as the
  // start destination underneath any deep-linked screen.
  const handleBack = () => {
    // React Router stamps an `idx` on history.state when it owns the
    // entry; 0 means "first one we ever pushed" → nothing to pop.
    const idx =
      typeof window !== "undefined"
        ? (window.history.state as { idx?: number } | null)?.idx ?? 0
        : 0;
    if (idx > 0) navigate(-1);
    else navigate("/", { replace: true });
  };

  return (
    <Box sx={{ display: "flex", flexDirection: "column", minHeight: "100vh" }}>
      <AppBar position="sticky" color="primary" enableColorOnDark>
        <Toolbar>
          {!isRoot && (
            <IconButton
              edge="start"
              color="inherit"
              aria-label="back"
              onClick={handleBack}
              sx={{ mr: 1 }}
            >
              <ArrowBackIcon />
            </IconButton>
          )}
          <Typography variant="h6" sx={{ flexGrow: 1 }}>
            <TitleForRoute eventId={eventId} />
          </Typography>
          {eventId && (
            <IconButton color="inherit" aria-label="sync" onClick={handleRefresh}>
              {syncing ? (
                <CircularProgress size={20} color="inherit" />
              ) : (
                <RefreshIcon />
              )}
            </IconButton>
          )}
          {eventId && !onStats && (
            <IconButton
              color="inherit"
              aria-label="stats"
              onClick={() => navigate(`/event/${eventId}/stats`)}
            >
              <BarChartIcon />
            </IconButton>
          )}
          {eventId && !onEventSettings && (
            <IconButton
              color="inherit"
              aria-label="event settings"
              onClick={() => navigate(`/event/${eventId}/settings`)}
            >
              <SettingsIcon />
            </IconButton>
          )}
          {isRoot && (
            <IconButton
              color="inherit"
              aria-label="settings"
              onClick={() => navigate("/settings")}
            >
              <SettingsIcon />
            </IconButton>
          )}
        </Toolbar>
      </AppBar>
      <Container
        maxWidth="sm"
        sx={{ py: 2, flex: 1, display: "flex", flexDirection: "column" }}
      >
        {children}
      </Container>
    </Box>
  );
}

function TitleForRoute({ eventId }: { eventId: string | null }) {
  const [name, setName] = useState<string>("FairShare");
  useEffect(() => {
    if (!eventId) {
      setName("FairShare");
      return;
    }
    let cancelled = false;
    void getDb()
      .events.get(eventId)
      .then((e) => {
        if (!cancelled) setName(e?.name ?? "FairShare");
      });
    return () => {
      cancelled = true;
    };
  }, [eventId]);
  return <>{name}</>;
}
