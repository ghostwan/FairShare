/**
 * Browser-side helpers for the optional Web Push notifications.
 *
 * The model is:
 *   - One `PushSubscription` per browser × origin × SW registration
 *     (the browser hands us back the same one on repeat
 *     `pushManager.subscribe` calls).
 *   - Opt-in is *per event* in the UI: the user might want
 *     notifications for the family trip but not for their D&D group.
 *     We store the boolean in Dexie (`webPushPrefs`) and PUT/DELETE
 *     the subscription to the Worker on a per-event basis.
 *   - The Worker fans out to all subscriptions of an event when ops
 *     come in (`web_push_subscriptions` row keyed by
 *     `(event_id, device_id)`).
 *   - On `pushsubscriptionchange` the SW broadcasts a message; the
 *     main thread re-PUTs the rotated subscription for every event
 *     that was opted in.
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

export async function getWebPushPref(eventId: string): Promise<boolean> {
  const row = await getDb().webPushPrefs.get(eventId);
  return row?.enabled === 1;
}

/**
 * Subscribes the browser (if not already), registers the subscription
 * with the Worker for `eventId`, and stores the opt-in. Returns
 * `false` when the flow can't complete (no Web Push support, no VAPID
 * key configured on the Worker, permission denied).
 *
 * `bearer` is the per-event Worker bearer that the caller already has
 * cached in `eventSecrets`. We don't fetch it here because this module
 * sits below the sync coordinator and we want to keep its deps thin.
 */
export async function enableWebPushForEvent(
  eventId: string,
  bearer: string,
): Promise<{ enabled: boolean; reason?: string }> {
  if (!isWebPushSupported()) return { enabled: false, reason: "unsupported" };

  const transport = await getTransport();
  const vapidPublicKey = await transport.getVapidPublicKey();
  if (!vapidPublicKey) {
    return { enabled: false, reason: "no_vapid_key" };
  }

  // Notification.requestPermission must be called from a user gesture.
  // We let it throw on Safari < 16.4 (it returns a promise on modern
  // browsers; the older callback-only form would have rejected the
  // chain earlier in the support check).
  const permission = await Notification.requestPermission();
  if (permission !== "granted") {
    return { enabled: false, reason: "permission_denied" };
  }

  const reg = await navigator.serviceWorker.ready;
  let sub = await reg.pushManager.getSubscription();
  if (!sub) {
    sub = await reg.pushManager.subscribe({
      userVisibleOnly: true,
      applicationServerKey: base64UrlToBytes(vapidPublicKey).buffer as ArrayBuffer,
    });
  }

  const serialized = serializeSubscription(sub);
  if (!serialized) {
    // Browser returned a subscription with no keys — extremely rare
    // and we can't push to it. Drop and report failure.
    await sub.unsubscribe().catch(() => undefined);
    return { enabled: false, reason: "missing_keys" };
  }

  const deviceId = await getOrCreateDeviceId();
  await transport.putWebPushSubscription(eventId, deviceId, bearer, serialized);

  await getDb().webPushPrefs.put({ eventId, enabled: 1 });
  return { enabled: true };
}

/**
 * Tells the Worker to stop pushing for `eventId` and clears the local
 * opt-in flag. We do NOT call `subscription.unsubscribe()` here
 * because the same browser-level subscription may still be active for
 * other events.
 */
export async function disableWebPushForEvent(
  eventId: string,
  bearer: string,
): Promise<void> {
  await getDb().webPushPrefs.put({ eventId, enabled: 0 });
  if (!isWebPushSupported()) return;
  const transport = await getTransport();
  const deviceId = await getOrCreateDeviceId();
  try {
    await transport.deleteWebPushSubscription(eventId, deviceId, bearer);
  } catch (e) {
    // Worker-side cleanup is best-effort; the local opt-out is what
    // matters most so the UI immediately reflects the new state.
    if (!(e instanceof WorkerTransportError)) throw e;
  }
}

/**
 * Re-PUTs the (possibly rotated) subscription for every event the
 * user has opted-in to. Called by the SW-bridge listener when the
 * browser fires `pushsubscriptionchange`.
 */
export async function reRegisterAllEnabledEvents(): Promise<void> {
  if (!isWebPushSupported()) return;
  const enabled = await getDb()
    .webPushPrefs.where("enabled")
    .equals(1)
    .toArray();
  if (enabled.length === 0) return;

  const reg = await navigator.serviceWorker.ready;
  const sub = await reg.pushManager.getSubscription();
  if (!sub) return;
  const serialized = serializeSubscription(sub);
  if (!serialized) return;

  const transport = await getTransport();
  const deviceId = await getOrCreateDeviceId();
  for (const pref of enabled) {
    const secret = await getDb().eventSecrets.get(pref.eventId);
    if (!secret) continue;
    try {
      await transport.putWebPushSubscription(
        pref.eventId,
        deviceId,
        secret.bearer,
        serialized,
      );
    } catch {
      // Swallow per-event errors so a single bad bearer doesn't abort
      // the loop. The next manual toggle will retry.
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
