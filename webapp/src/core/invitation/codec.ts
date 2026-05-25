import {
  asciiEncode,
  base64UrlDecode,
  base64UrlEncode,
  utf8Decode,
  utf8Encode,
} from "../crypto/base64";
import { constantTimeEquals } from "../crypto/constantTime";
import { deriveInvitationMacKey } from "../crypto/bearer";
import { hmacSha256 } from "../crypto/hkdf";
import { decodeOperationsJson, encodeOperationsJson } from "../sync/codec";
import type { Operation } from "../sync/operation";
import { gzip, ungzip } from "./gzip";

/**
 * Invitation link codec, byte-compatible with
 * `com.fairshare.data.invitation.InvitationCodec`.
 *
 * Wire format (legacy custom scheme):
 *
 *   fairshare://join?event=<eventId>
 *                    &key=<b64url(32-byte eventKey)>
 *                    &seed=<b64url(gzip(json(ops)))>
 *                    &sig=<b64url(HMAC-SHA256(macKey, ASCII(seed_string)))>
 *
 * Wire format (https variant used by the webapp QR code):
 *
 *   https://fairshare-web-bdg.pages.dev/join?event=…&key=…&seed=…&sig=…
 *
 * Both forms carry the same four query parameters in any order and are
 * interchangeable: a device can accept either, and the webapp emits
 * the https form so iOS Safari handles the link natively. Android
 * keeps emitting the custom scheme too (so old printed QRs still work)
 * but its intent filter also catches the https URL.
 *
 * Values are *not* percent-encoded: the Kotlin parser splits on `&`
 * then on the first `=`. base64url without padding never produces `&`
 * or `=` (no padding) and the alphabet is URL-safe, so the parameters
 * round-trip verbatim through any HTTP layer.
 */

const SCHEME_CUSTOM = "fairshare://join?";
const SCHEME_HTTPS_HOST_PATH = "fairshare-web-bdg.pages.dev/join?";

export interface DecodedInvitation {
  eventId: string;
  eventKey: Uint8Array;
  ops: Operation[];
}

export type InvitationDecodeError =
  | { kind: "MalformedUrl" }
  | { kind: "MissingFields" }
  | { kind: "SignatureMismatch" }
  | { kind: "PayloadInvalid"; cause: unknown };

export class InvitationDecodeException extends Error {
  constructor(public readonly error: InvitationDecodeError) {
    super(`InvitationDecode: ${error.kind}`);
  }
}

/**
 * Encode an invitation. `host` selects which URL flavour to emit:
 *
 *   - `"https"` (default for the webapp): produces the
 *     `https://fairshare-web-bdg.pages.dev/join?…` form so iOS Safari
 *     opens it directly.
 *   - `"custom"`: produces the legacy `fairshare://join?…` form,
 *     useful for tests that want to round-trip with the Android
 *     fixtures.
 */
export async function encodeInvitation(
  eventId: string,
  ops: Operation[],
  eventKey: Uint8Array,
  host: "https" | "custom" = "https",
): Promise<string> {
  if (eventKey.length !== 32) {
    throw new Error(`eventKey must be 32 bytes, got ${eventKey.length}`);
  }
  for (const op of ops) {
    if (op.eventId !== eventId) {
      throw new Error(
        `encodeInvitation: all ops must share eventId=${eventId}`,
      );
    }
  }
  const seedBytes = utf8Encode(encodeOperationsJson(ops));
  const seed = base64UrlEncode(gzip(seedBytes));
  const key = base64UrlEncode(eventKey);
  const macKey = await deriveInvitationMacKey(eventKey);
  const sigBytes = await hmacSha256(macKey, asciiEncode(seed));
  const sig = base64UrlEncode(sigBytes);
  const query = `event=${eventId}&key=${key}&seed=${seed}&sig=${sig}`;
  return host === "https"
    ? `https://${SCHEME_HTTPS_HOST_PATH}${query}`
    : `${SCHEME_CUSTOM}${query}`;
}

/** Parses the URL, verifies its HMAC, returns the bundle. */
export async function decodeInvitation(
  url: string,
): Promise<DecodedInvitation> {
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
  const seed = params.get("seed");
  const sig = params.get("sig");
  if (eventId == null || keyParam == null || seed == null || sig == null) {
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

  const macKey = await deriveInvitationMacKey(eventKey);
  const expected = await hmacSha256(macKey, asciiEncode(seed));
  let provided: Uint8Array;
  try {
    provided = base64UrlDecode(sig);
  } catch {
    throw new InvitationDecodeException({ kind: "SignatureMismatch" });
  }
  if (!constantTimeEquals(expected, provided)) {
    throw new InvitationDecodeException({ kind: "SignatureMismatch" });
  }

  try {
    const seedJson = utf8Decode(ungzip(base64UrlDecode(seed)));
    const ops = decodeOperationsJson(seedJson);
    for (const op of ops) {
      if (op.eventId !== eventId) {
        throw new Error(
          "decoded seed ops carry an eventId different from the URL",
        );
      }
    }
    return { eventId, eventKey, ops };
  } catch (cause) {
    throw new InvitationDecodeException({ kind: "PayloadInvalid", cause });
  }
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
