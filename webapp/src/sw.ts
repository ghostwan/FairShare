/// <reference lib="webworker" />
/**
 * FairShare service worker — `injectManifest` build.
 *
 * Responsibilities:
 *   1. Precache the static app shell (CSS / JS / icons) so the PWA
 *      works offline. Workbox's `precacheAndRoute` consumes the
 *      manifest injected at build time via `self.__WB_MANIFEST`.
 *   2. SPA navigation fallback: any same-origin GET that wants HTML
 *      gets `index.html` served from the precache (matches the
 *      previous `generateSW` `navigateFallback`).
 *   3. Handle `push` events: the Worker fans out a tiny
 *      `{ eventId }` payload whenever someone else writes to an event
 *      we're subscribed to. The SW broadcasts that to every visible
 *      client (so the live UI triggers `syncNow(eventId)` immediately)
 *      and, when no window is open, shows a short notification so the
 *      user knows the data moved.
 *   4. `pushsubscriptionchange`: re-subscribe with the same VAPID key
 *      so a rotated endpoint doesn't silently break notifications.
 *      The new subscription is broadcast to clients (when any is
 *      open) and PUT to the Worker.
 */

import { precacheAndRoute, createHandlerBoundToURL } from "workbox-precaching";
import { NavigationRoute, registerRoute } from "workbox-routing";

declare const self: ServiceWorkerGlobalScope;

precacheAndRoute(self.__WB_MANIFEST);

// SPA navigation fallback. Mirrors `navigateFallback: "/index.html"`
// from the old `generateSW` config and the denylist that kept
// API-style paths off the precache.
const navigationRoute = new NavigationRoute(
  createHandlerBoundToURL("/index.html"),
  {
    denylist: [/^\/api\//, /^\/health$/],
  },
);
registerRoute(navigationRoute);

self.addEventListener("install", () => {
  // Activate the new SW immediately so users get the push handler on
  // their next visit without having to close every tab.
  void self.skipWaiting();
});

self.addEventListener("activate", (event) => {
  event.waitUntil(self.clients.claim());
});

// ---------- Push handling ----------

interface WakePayload {
  eventId?: string;
}

self.addEventListener("push", (event) => {
  event.waitUntil(handlePush(event));
});

async function handlePush(event: PushEvent): Promise<void> {
  let payload: WakePayload = {};
  if (event.data) {
    try {
      payload = event.data.json() as WakePayload;
    } catch {
      // Some push services strip the payload (or send empty bodies
      // for VAPID-only "wake" pushes). We fall through with an empty
      // payload — clients will refresh the current event anyway.
      payload = {};
    }
  }

  // Notify every visible client. Each tab listens via
  // `navigator.serviceWorker.addEventListener("message", …)` and
  // re-runs `syncNow(eventId)`.
  const clients = await self.clients.matchAll({
    type: "window",
    includeUncontrolled: true,
  });
  for (const client of clients) {
    client.postMessage({ type: "fairshare/push", payload });
  }

  // If at least one client is focused, skip the visible notification.
  // Chrome / Firefox enforce a "must show notification" budget on
  // silent pushes; we trade a small toast for the budget when nothing
  // is focused so the OS doesn't revoke the permission.
  const hasFocused = clients.some((c) => c.visibilityState === "visible");
  if (hasFocused) return;

  await self.registration.showNotification("FairShare", {
    body: "Mise à jour disponible",
    tag: payload.eventId ?? "fairshare-update",
    silent: true,
    data: payload,
    icon: "/icons/icon-192.png",
    badge: "/icons/icon-192.png",
  });
}

self.addEventListener("notificationclick", (event) => {
  event.notification.close();
  const payload = (event.notification.data ?? {}) as WakePayload;
  const targetUrl = payload.eventId
    ? `/event/${encodeURIComponent(payload.eventId)}`
    : "/";
  event.waitUntil(focusOrOpen(targetUrl));
});

async function focusOrOpen(targetUrl: string): Promise<void> {
  const clients = await self.clients.matchAll({
    type: "window",
    includeUncontrolled: true,
  });
  for (const client of clients) {
    // Same-origin SPA: a focus is enough — the client picks up the
    // push message and navigates internally. We still try to focus
    // the matching URL first when possible.
    try {
      const url = new URL(client.url);
      if (url.pathname === targetUrl) {
        await client.focus();
        return;
      }
    } catch {
      /* ignore */
    }
  }
  if (clients.length > 0) {
    await clients[0].focus();
    // Hand off the deep link via postMessage; the main thread will
    // call react-router's `navigate(targetUrl)`.
    clients[0].postMessage({ type: "fairshare/navigate", url: targetUrl });
    return;
  }
  await self.clients.openWindow(targetUrl);
}

// ---------- Subscription rotation ----------

self.addEventListener("pushsubscriptionchange", (event) => {
  const e = event as PushSubscriptionChangeEvent;
  event.waitUntil(handleRotation(e));
});

async function handleRotation(
  event: PushSubscriptionChangeEvent,
): Promise<void> {
  const oldSub = event.oldSubscription;
  const options = oldSub?.options;
  if (!options?.applicationServerKey) return;

  const newSub = await self.registration.pushManager.subscribe({
    userVisibleOnly: true,
    applicationServerKey: options.applicationServerKey,
  });
  // Broadcast: the main thread re-PUTs the new subscription to the
  // Worker (we don't have the per-event bearer here in the SW, only
  // the page does).
  const clients = await self.clients.matchAll({
    type: "window",
    includeUncontrolled: true,
  });
  for (const client of clients) {
    client.postMessage({
      type: "fairshare/subscription-changed",
      subscription: serialize(newSub),
    });
  }
}

interface PushSubscriptionChangeEvent extends ExtendableEvent {
  readonly oldSubscription: PushSubscription | null;
  readonly newSubscription: PushSubscription | null;
}

function serialize(sub: PushSubscription) {
  const json = sub.toJSON();
  return {
    endpoint: sub.endpoint,
    keys: json.keys ?? {},
  };
}
