import { base64UrlDecode, base64UrlEncode } from "../crypto/base64";

/**
 * Invitation link codec, byte-compatible with
 * `com.fairshare.data.invitation.InvitationCodec`.
 *
 * Wire format (legacy custom scheme):
 *
 *   fairshare://join?event=<eventId>&key=<b64url(32-byte eventKey)>
 *
 * Wire format (https variant, default):
 *
 *   https://fairshare-web-bdg.pages.dev/join?event=…&key=…
 *
 * The op log is no longer embedded — the joining device pulls it from
 * the Cloudflare Worker on first sync. This keeps the URL small and
 * constant-size so the QR code stays scannable regardless of how big
 * the event got.
 *
 * Anyone with the URL can read and write the event. Tampering with
 * the key produces a value that fails AES-GCM authentication on the
 * first pull; the sync layer surfaces it then.
 *
 * Values are *not* percent-encoded: the parser splits on `&` then on
 * the first `=`. base64url without padding never produces `&` or `=`
 * and the alphabet is URL-safe, so parameters round-trip verbatim.
 */

const SCHEME_CUSTOM = "fairshare://join?";
const SCHEME_HTTPS_HOST_PATH = "fairshare-web-bdg.pages.dev/join?";

export interface DecodedInvitation {
  eventId: string;
  eventKey: Uint8Array;
}

export type InvitationDecodeError =
  | { kind: "MalformedUrl" }
  | { kind: "MissingFields" };

export class InvitationDecodeException extends Error {
  constructor(public readonly error: InvitationDecodeError) {
    super(`InvitationDecode: ${error.kind}`);
  }
}

/**
 * Encode an invitation. `host` selects which URL flavour to emit:
 *
 *   - `"https"` (default): produces the
 *     `https://fairshare-web-bdg.pages.dev/join?…` form so iOS Safari
 *     opens it directly.
 *   - `"custom"`: produces the legacy `fairshare://join?…` form,
 *     useful for tests that want to round-trip with the Android
 *     fixtures.
 */
export function encodeInvitation(
  eventId: string,
  eventKey: Uint8Array,
  host: "https" | "custom" = "https",
): string {
  if (eventKey.length !== 32) {
    throw new Error(`eventKey must be 32 bytes, got ${eventKey.length}`);
  }
  const key = base64UrlEncode(eventKey);
  const query = `event=${eventId}&key=${key}`;
  return host === "https"
    ? `https://${SCHEME_HTTPS_HOST_PATH}${query}`
    : `${SCHEME_CUSTOM}${query}`;
}

/** Parses the URL, returns the bundle. */
export function decodeInvitation(url: string): DecodedInvitation {
  const query = stripPrefix(url);
  if (query == null) {
    throw new InvitationDecodeException({ kind: "MalformedUrl" });
  }
  const params = parseQuery(query);
  if (params == null) {
    throw new InvitationDecodeException({ kind: "MalformedUrl" });
  }
  const eventId = params.get("event");
  const keyParam = params.get("key");
  if (eventId == null || keyParam == null) {
    throw new InvitationDecodeException({ kind: "MissingFields" });
  }

  let eventKey: Uint8Array;
  try {
    eventKey = base64UrlDecode(keyParam);
  } catch {
    throw new InvitationDecodeException({ kind: "MalformedUrl" });
  }
  if (eventKey.length !== 32) {
    throw new InvitationDecodeException({ kind: "MalformedUrl" });
  }

  return { eventId, eventKey };
}

function stripPrefix(url: string): string | null {
  if (url.startsWith(SCHEME_CUSTOM)) {
    return url.slice(SCHEME_CUSTOM.length);
  }
  // Accept any https host with /join? — keeps the codec usable on
  // staging deployments (e.g. `fairshare-web-preview.pages.dev`) or a
  // self-hosted mirror without changing the wire format.
  const httpsMatch = url.match(/^https?:\/\/[^/?#]+\/join\?(.*)$/);
  if (httpsMatch) return httpsMatch[1]!;
  return null;
}

function parseQuery(query: string): Map<string, string> | null {
  if (query.length === 0) return null;
  const map = new Map<string, string>();
  for (const pair of query.split("&")) {
    const eq = pair.indexOf("=");
    if (eq <= 0 || eq === pair.length - 1) continue;
    map.set(pair.substring(0, eq), pair.substring(eq + 1));
  }
  return map.size === 0 ? null : map;
}
