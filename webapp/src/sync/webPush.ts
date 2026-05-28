/**
 * Browser-side helpers for the optional Web Push notifications.
 *
 * Web Push is opt-in **globally** for the webapp (one switch in
 * Settings). When enabled we:
 *
 *   - Request browser permission once.
 *   - Subscribe with `pushManager.subscribe` once. The endpoint is a
 *     browser × origin × SW registration singleton — subsequent calls
 *     hand back the same one.
 *   - Register the same subscription against every paired event on
 *     the Worker (`PUT /events/:id/devices/:did/web-push`). The
 *     Worker's fan-out is keyed by `(event_id, device_id)`; we just
 *     write one row per event so it knows whom to notify.
 *   - Auto-register when a new event is joined (hook called from
 *     `coordinator.rememberEventSecret`).
 *
 * When disabled we DELETE every Worker-side registration for this
 * device, then `unsubscribe()` the browser. Permission stays granted
 * (browsers don't let us revoke it from JS); turning the switch back
 * on will re-subscribe silently if the user hasn't blocked it in the
 * meantime.
 *
 * The previous per-event `webPushPrefs` table is no longer read or
 * written, but the v2 Dexie store stays around for any user who had
 * opted-in under the old model — its rows simply become inert.
 *
 * Everything in this module is best-effort. A missing VAPID key on
 * the Worker, a denied permission prompt, or an unsupported browser
 * just resolves to `{ enabled: false }` without throwing — the
 * existing focus-driven catch-up sync remains the safety net.
 */

import { getDb } from "@/data/db";
import { Settings } from "@/data/settings";
import { getOrCreateDeviceId } from "@/data/identityStore";
import {
  WorkerCloudTransport,
  WorkerTransportError,
} from "@/core/sync/transport";

export type EnableReason =
  | "unsupported"
  | "no_vapid_key"
  | "permission_denied"
  | "missing_keys"
  | "transport_error";

let transportCache: WorkerCloudTransport | null = null;
let transportBaseUrl = "";

async function getTransport(): Promise<WorkerCloudTransport> {
  const baseUrl = await Settings.getCloudBaseUrl();
  if (transportCache && transportBaseUrl === baseUrl) return transportCache;
  transportCache = new WorkerCloudTransport({ baseUrl });
  transportBaseUrl = baseUrl;
  return transportCache;
}

export function isWebPushSupported(): boolean {
  return (
    typeof navigator !== "undefined" &&
    "serviceWorker" in navigator &&
    typeof window !== "undefined" &&
    "PushManager" in window &&
    "Notification" in window
  );
}

/**
 * Subscribes the browser (if not already), then registers the
 * subscription with the Worker for every event currently paired on
 * this device. Persists the global opt-in flag in Settings. Returns
 * `false` (with a `reason`) if the flow can't complete.
 */
export async function enableWebPushGlobally(): Promise<{
  enabled: boolean;
  reason?: EnableReason;
}> {
  if (!isWebPushSupported()) return { enabled: false, reason: "unsupported" };

  const transport = await getTransport();
  const vapidPublicKey = await transport.getVapidPublicKey();
  if (!vapidPublicKey) return { enabled: false, reason: "no_vapid_key" };

  // Must be called from a user gesture on most browsers.
  const permission = await Notification.requestPermission();
  if (permission !== "granted") {
    return { enabled: false, reason: "permission_denied" };
  }

  const reg = await navigator.serviceWorker.ready;
  let sub = await reg.pushManager.getSubscription();
  if (!sub) {
    sub = await reg.pushManager.subscribe({
      userVisibleOnly: true,
      applicationServerKey: base64UrlToBytes(vapidPublicKey)
        .buffer as ArrayBuffer,
    });
  }

  const serialized = serializeSubscription(sub);
  if (!serialized) {
    await sub.unsubscribe().catch(() => undefined);
    return { enabled: false, reason: "missing_keys" };
  }

  // Persist the opt-in *before* the fan-out so a partial registration
  // failure still leaves us in a state where future events get
  // auto-registered by `registerEventIfPushEnabled`.
  await Settings.setPushNotificationsEnabled(true);

  await registerSubscriptionForAllEvents(serialized);
  return { enabled: true };
}

/**
 * Clears the opt-in, drops every Worker-side registration for this
 * device, and tears down the browser subscription. We unsubscribe the
 * browser because — unlike the per-event model — there is no longer a
 * reason to keep the subscription alive.
 */
export async function disableWebPushGlobally(): Promise<void> {
  await Settings.setPushNotificationsEnabled(false);
  if (!isWebPushSupported()) return;

  const transport = await getTransport();
  const deviceId = await getOrCreateDeviceId();
  const secrets = await getDb().eventSecrets.toArray();
  for (const row of secrets) {
    try {
      await transport.deleteWebPushSubscription(
        row.eventId,
        deviceId,
        row.bearer,
      );
    } catch (e) {
      // Best-effort: a stale bearer or 404 must not abort the loop.
      if (!(e instanceof WorkerTransportError)) throw e;
    }
  }

  try {
    const reg = await navigator.serviceWorker.ready;
    const sub = await reg.pushManager.getSubscription();
    if (sub) await sub.unsubscribe();
  } catch {
    // Browser-side unsubscribe is best-effort; the Settings flag is
    // what gates re-registration on the next event join.
  }
}

/**
 * Hook called from `coordinator.rememberEventSecret` whenever an
 * event secret is persisted (initial join or re-import). If global
 * push is enabled and a subscription exists, idempotently PUTs it on
 * the Worker for that event. No-op otherwise.
 */
export async function registerEventIfPushEnabled(
  eventId: string,
  bearer: string,
): Promise<void> {
  if (!isWebPushSupported()) return;
  if (!(await Settings.getPushNotificationsEnabled())) return;

  const reg = await navigator.serviceWorker.ready;
  const sub = await reg.pushManager.getSubscription();
  if (!sub) return;
  const serialized = serializeSubscription(sub);
  if (!serialized) return;

  const transport = await getTransport();
  const deviceId = await getOrCreateDeviceId();
  try {
    await transport.putWebPushSubscription(
      eventId,
      deviceId,
      bearer,
      serialized,
    );
  } catch (e) {
    if (!(e instanceof WorkerTransportError)) throw e;
  }
}

/**
 * Re-PUTs the (possibly rotated) subscription for every paired event.
 * Called by the SW-bridge listener when the browser fires
 * `pushsubscriptionchange`, and reused internally by
 * `enableWebPushGlobally` to fan out the first registration.
 */
export async function reRegisterAllIfEnabled(): Promise<void> {
  if (!isWebPushSupported()) return;
  if (!(await Settings.getPushNotificationsEnabled())) return;

  const reg = await navigator.serviceWorker.ready;
  const sub = await reg.pushManager.getSubscription();
  if (!sub) return;
  const serialized = serializeSubscription(sub);
  if (!serialized) return;

  await registerSubscriptionForAllEvents(serialized);
}

async function registerSubscriptionForAllEvents(serialized: {
  endpoint: string;
  p256dh: string;
  auth: string;
}): Promise<void> {
  const transport = await getTransport();
  const deviceId = await getOrCreateDeviceId();
  const secrets = await getDb().eventSecrets.toArray();
  for (const row of secrets) {
    try {
      await transport.putWebPushSubscription(
        row.eventId,
        deviceId,
        row.bearer,
        serialized,
      );
    } catch {
      // Swallow per-event errors so a single bad bearer doesn't abort
      // the loop. The next focus-driven re-register will retry.
    }
  }
}

function serializeSubscription(
  sub: PushSubscription,
): { endpoint: string; p256dh: string; auth: string } | null {
  const json = sub.toJSON();
  const keys = json.keys;
  if (!keys || typeof keys.p256dh !== "string" || typeof keys.auth !== "string") {
    return null;
  }
  return {
    endpoint: sub.endpoint,
    p256dh: keys.p256dh,
    auth: keys.auth,
  };
}

function base64UrlToBytes(s: string): Uint8Array {
  const normalized = s.replace(/-/g, "+").replace(/_/g, "/");
  const padded = normalized + "=".repeat((4 - (normalized.length % 4)) % 4);
  const bin = atob(padded);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}
