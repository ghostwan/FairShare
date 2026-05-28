/**
 * Bridge between the service worker (which receives Web Push events)
 * and the running app (which has the secrets + the React router and
 * can actually re-sync the right event).
 *
 * The SW posts three flavours of messages — see `webapp/src/sw.ts`:
 *
 *   - `fairshare/push`            → an op landed for `eventId`. We
 *                                    run `syncNow(eventId)` so the
 *                                    open tab catches up instantly.
 *   - `fairshare/navigate`        → the user tapped the notification.
 *                                    We deep-link into the requested
 *                                    URL via window.history.
 *   - `fairshare/subscription-changed` → the browser rotated the
 *                                        subscription. We re-PUT it
 *                                        for every event the user
 *                                        opted-in to.
 *
 * The bridge is installed once from `main.tsx`. It's idempotent and a
 * no-op when the SW APIs aren't available (SSR / test environments).
 */

import { syncNow } from "./coordinator";
import { reRegisterAllIfEnabled } from "./webPush";

interface PushMessage {
  type: "fairshare/push";
  payload: { eventId?: string };
}
interface NavigateMessage {
  type: "fairshare/navigate";
  url: string;
}
interface SubscriptionChangedMessage {
  type: "fairshare/subscription-changed";
  subscription: { endpoint: string; keys: Record<string, string> };
}
type IncomingMessage =
  | PushMessage
  | NavigateMessage
  | SubscriptionChangedMessage;

let installed = false;

export function installServiceWorkerBridge(): void {
  if (installed) return;
  if (typeof navigator === "undefined" || !("serviceWorker" in navigator)) {
    return;
  }
  installed = true;

  navigator.serviceWorker.addEventListener("message", (event) => {
    const msg = event.data as IncomingMessage | undefined;
    if (!msg || typeof msg !== "object" || typeof msg.type !== "string") return;
    void handleMessage(msg);
  });
}

async function handleMessage(msg: IncomingMessage): Promise<void> {
  switch (msg.type) {
    case "fairshare/push": {
      const eventId = msg.payload?.eventId;
      if (!eventId) return;
      try {
        await syncNow(eventId);
      } catch {
        // syncNow already swallows transport errors and returns them
        // in the result object; anything thrown here is unexpected
        // and surfaced via the next visible refresh.
      }
      return;
    }
    case "fairshare/navigate": {
      // We don't have a router ref at this layer; rely on a full
      // navigation via window.location. This is rare (only on
      // notification click while a window is open) and the SPA
      // bootstraps fast.
      if (typeof window !== "undefined" && typeof msg.url === "string") {
        window.location.assign(msg.url);
      }
      return;
    }
    case "fairshare/subscription-changed": {
      try {
        await reRegisterAllIfEnabled();
      } catch {
        // No-op: next user-initiated toggle will recover.
      }
      return;
    }
  }
}
