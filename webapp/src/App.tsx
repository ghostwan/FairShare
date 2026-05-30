import { Route, Routes } from "react-router-dom";
import { AppShell } from "./presentation/AppShell";
import { EventsListScreen } from "./presentation/screens/EventsListScreen";
import { EventDetailScreen } from "./presentation/screens/EventDetailScreen";
import { EventSettingsScreen } from "./presentation/screens/EventSettingsScreen";
import { AddExpenseScreen } from "./presentation/screens/AddExpenseScreen";
import { InviteScreen } from "./presentation/screens/InviteScreen";
import { JoinScreen } from "./presentation/screens/JoinScreen";
import { ReceiptScanScreen } from "./presentation/screens/ReceiptScanScreen";
import { SettingsScreen } from "./presentation/screens/SettingsScreen";
import { StatsScreen } from "./presentation/screens/StatsScreen";

/**
 * Top-level routing. The shell stays mounted across navigations so the
 * AppBar / back button / refresh action remain anchored — only the
 * route body swaps in.
 *
 * Note on the `/join` route: invitation URLs are emitted as
 * `https://<host>/join?event=…&key=…&seed=…&sig=…`. We mount the
 * scan/paste UI at exactly that path, so a user landing here from an
 * external QR scan can immediately bootstrap via the pasted full URL
 * (or the SPA can later read `location.search` and auto-import; today
 * we keep the explicit paste flow for clarity).
 */
export function App() {
  return (
    <AppShell>
      <Routes>
        <Route path="/" element={<EventsListScreen />} />
        <Route path="/event/:eventId" element={<EventDetailScreen />} />
        <Route
          path="/event/:eventId/expense/new"
          element={<AddExpenseScreen />}
        />
        <Route
          path="/event/:eventId/expense/:expenseId"
          element={<AddExpenseScreen />}
        />
        <Route path="/event/:eventId/invite" element={<InviteScreen />} />
        <Route
          path="/event/:eventId/settings"
          element={<EventSettingsScreen />}
        />
        <Route path="/event/:eventId/stats" element={<StatsScreen />} />
        <Route
          path="/event/:eventId/receipt"
          element={<ReceiptScanScreen />}
        />
        <Route path="/join" element={<JoinScreen />} />
        <Route path="/settings" element={<SettingsScreen />} />
      </Routes>
    </AppShell>
  );
}
