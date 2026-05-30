import { base64StdDecode, base64StdEncode } from "../crypto/base64";
import type { EncryptedOp } from "./envelope";

/**
 * HTTP client for the FairShare sync Worker. Mirrors
 * `com.fairshare.data.sync.WorkerCloudTransport` in shape and wire
 * format. All errors surface as thrown `WorkerTransportError`; the
 * sync coordinator decides whether to retry or surface to the UI.
 *
 * Base64: standard alphabet *with* padding, to match Android's
 * `okio.ByteString.base64()`. The Worker tolerates URL-safe input too
 * but always emits standard on pull.
 */

export interface PushResult {
  inserted: number;
}

export interface PullResult {
  ops: EncryptedOp[];
  nextSince: number;
  nextSinceOp: string;
  hasMore: boolean;
}

export class WorkerTransportError extends Error {
  constructor(
    message: string,
    public readonly status?: number,
  ) {
    super(message);
  }
}

export interface WorkerTransportOptions {
  baseUrl: string; // e.g. https://fairshare-sync.ghostwan.workers.dev
  fetchImpl?: typeof fetch; // injectable for tests
}

export class WorkerCloudTransport {
  private readonly baseUrl: string;
  private readonly fetchImpl: typeof fetch;

  constructor(opts: WorkerTransportOptions) {
    this.baseUrl = opts.baseUrl.replace(/\/+$/, "");
    this.fetchImpl = opts.fetchImpl ?? globalThis.fetch.bind(globalThis);
    if (this.baseUrl.length === 0) {
      throw new Error("WorkerCloudTransport: baseUrl is required");
    }
  }

  async push(
    eventId: string,
    bearer: string,
    ops: EncryptedOp[],
  ): Promise<PushResult> {
    const body = JSON.stringify({
      ops: ops.map((op) => ({
        opId: op.opId,
        lamport: op.lamport,
        deviceId: op.deviceId,
        nonce: base64StdEncode(op.nonce),
        ciphertext: base64StdEncode(op.ciphertext),
      })),
    });
    const text = await this.exec(
      `${this.baseUrl}/events/${eventId}/ops`,
      {
        method: "POST",
        headers: {
          authorization: `Bearer ${bearer}`,
          "content-type": "application/json; charset=utf-8",
        },
        body,
      },
    );
    const parsed = safeParse(text, "push");
    const inserted =
      typeof parsed.inserted === "number" ? parsed.inserted : 0;
    return { inserted };
  }

  async pull(
    eventId: string,
    bearer: string,
    since: number,
    sinceOp: string,
  ): Promise<PullResult> {
    let url = `${this.baseUrl}/events/${eventId}/ops?since=${since}`;
    if (sinceOp.length > 0) {
      url += `&since_op=${sinceOp}`;
    }
    const text = await this.exec(url, {
      method: "GET",
      headers: { authorization: `Bearer ${bearer}` },
    });
    const parsed = safeParse(text, "pull");
    const rawOps = Array.isArray(parsed.ops) ? parsed.ops : [];
    const ops = rawOps.map((o: Record<string, unknown>): EncryptedOp => ({
      opId: String(o.opId ?? ""),
      lamport: typeof o.lamport === "number" ? o.lamport : 0,
      deviceId: String(o.deviceId ?? ""),
      nonce: o.nonce ? base64StdDecode(String(o.nonce)) : new Uint8Array(0),
      ciphertext: o.ciphertext
        ? base64StdDecode(String(o.ciphertext))
        : new Uint8Array(0),
    }));
    return {
      ops,
      nextSince: typeof parsed.nextSince === "number" ? parsed.nextSince : 0,
      nextSinceOp:
        typeof parsed.nextSinceOp === "string" ? parsed.nextSinceOp : "",
      hasMore: parsed.hasMore === true,
    };
  }

  /**
   * Registers (or refreshes) a push subscription endpoint for this
   * event + device. The Worker treats the endpoint string as opaque.
   * Used by the future WebPush integration; the current build calls
   * it as a no-op placeholder so the API surface is in place.
   */
  async putDeviceToken(
    eventId: string,
    deviceId: string,
    bearer: string,
    token: string,
  ): Promise<void> {
    await this.exec(
      `${this.baseUrl}/events/${eventId}/devices/${deviceId}/token`,
      {
        method: "PUT",
        headers: {
          authorization: `Bearer ${bearer}`,
          "content-type": "application/json; charset=utf-8",
        },
        body: JSON.stringify({ fcmToken: token }),
      },
    );
  }

  async deleteDeviceToken(
    eventId: string,
    deviceId: string,
    bearer: string,
  ): Promise<void> {
    await this.exec(
      `${this.baseUrl}/events/${eventId}/devices/${deviceId}/token`,
      {
        method: "DELETE",
        headers: { authorization: `Bearer ${bearer}` },
      },
    );
  }

  /**
   * Registers a Web Push subscription so the Worker can wake this
   * browser via VAPID + aes128gcm whenever someone else writes to the
   * event. We send the three pieces verbatim — endpoint URL, P-256
   * user-agent public key, and 16-byte auth secret — all base64url
   * encoded, exactly as `PushSubscription.toJSON()` returns them.
   */
  async putWebPushSubscription(
    eventId: string,
    deviceId: string,
    bearer: string,
    subscription: { endpoint: string; p256dh: string; auth: string },
  ): Promise<void> {
    await this.exec(
      `${this.baseUrl}/events/${eventId}/devices/${deviceId}/web-push`,
      {
        method: "PUT",
        headers: {
          authorization: `Bearer ${bearer}`,
          "content-type": "application/json; charset=utf-8",
        },
        body: JSON.stringify(subscription),
      },
    );
  }

  async deleteWebPushSubscription(
    eventId: string,
    deviceId: string,
    bearer: string,
  ): Promise<void> {
    await this.exec(
      `${this.baseUrl}/events/${eventId}/devices/${deviceId}/web-push`,
      {
        method: "DELETE",
        headers: { authorization: `Bearer ${bearer}` },
      },
    );
  }

  /**
   * Fetches the Worker's VAPID public key (base64url, 65-byte
   * uncompressed P-256 point). The webapp passes this to
   * `pushManager.subscribe({ applicationServerKey })`. The endpoint
   * is unauthenticated by design: VAPID public keys are public.
   * Returns `null` when the Worker is not configured for Web Push
   * (HTTP 404 web_push_not_configured) so callers can degrade
   * gracefully instead of throwing.
   */
  async getVapidPublicKey(): Promise<string | null> {
    let response: Response;
    try {
      response = await this.fetchImpl(`${this.baseUrl}/web-push/key`, {
        method: "GET",
      });
    } catch (e) {
      throw new WorkerTransportError(
        `network error fetching VAPID key: ${(e as Error).message}`,
      );
    }
    if (response.status === 404) return null;
    const text = await response.text();
    if (!response.ok) {
      throw new WorkerTransportError(
        `HTTP ${response.status} for VAPID key: ${text.slice(0, 200)}`,
        response.status,
      );
    }
    const parsed = safeParse(text, "vapid");
    const key = typeof parsed.publicKey === "string" ? parsed.publicKey : null;
    return key;
  }

  private async exec(url: string, init: RequestInit): Promise<string> {
    let response: Response;
    try {
      response = await this.fetchImpl(url, init);
    } catch (e) {
      throw new WorkerTransportError(
        `network error for ${url}: ${(e as Error).message}`,
      );
    }
    const text = await response.text();
    if (!response.ok) {
      throw new WorkerTransportError(
        `HTTP ${response.status} for ${url}: ${text.slice(0, 200)}`,
        response.status,
      );
    }
    return text;
  }
}

function safeParse(text: string, label: string): Record<string, unknown> {
  if (text.length === 0) return {};
  try {
    const v = JSON.parse(text);
    if (v && typeof v === "object") return v as Record<string, unknown>;
    return {};
  } catch (e) {
    throw new WorkerTransportError(
      `Malformed ${label} response: ${text.slice(0, 200)} (${(e as Error).message})`,
    );
  }
}
