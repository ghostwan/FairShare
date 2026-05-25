import { hkdfSha256, hmacSha256 } from "./hkdf";
import { toHex, utf8Encode } from "./base64";

/**
 * Sub-key derivation and the per-event Worker bearer.
 *
 * The single 32-byte event key is split via HKDF into three domain-
 * separated sub-keys so that a leak of one (eg. an invitation HMAC
 * token caught on the wire) cannot be repurposed against another
 * surface. The info strings must match `SyncCrypto.kt` verbatim.
 */

const INFO_MAC = utf8Encode("fairshare-invitation-mac");
const INFO_WORKER_AUTH = utf8Encode("fairshare-worker-auth");
const INFO_CLOUD_CIPHER = utf8Encode("fairshare-cloud-cipher");

/** 32-byte HMAC key for invitation bundle integrity. */
export function deriveInvitationMacKey(eventKey: Uint8Array): Promise<Uint8Array> {
  return hkdfSha256(eventKey, INFO_MAC);
}

/** 32-byte HMAC key from which the Worker bearer is computed. */
export function deriveWorkerAuthKey(eventKey: Uint8Array): Promise<Uint8Array> {
  return hkdfSha256(eventKey, INFO_WORKER_AUTH);
}

/** 32-byte AES-256-GCM key for cloud op encryption. */
export function deriveCloudCipherKey(eventKey: Uint8Array): Promise<Uint8Array> {
  return hkdfSha256(eventKey, INFO_CLOUD_CIPHER);
}

/**
 * Per-event Worker bearer: lowercase hex of
 * `HMAC-SHA256(deriveWorkerAuthKey(eventKey), UTF8(eventId))`. Always
 * 64 chars. The Worker stores `SHA-256(bearer)` as its verifier.
 */
export async function computeWorkerBearer(
  eventKey: Uint8Array,
  eventId: string,
): Promise<string> {
  const authKey = await deriveWorkerAuthKey(eventKey);
  const mac = await hmacSha256(authKey, utf8Encode(eventId));
  return toHex(mac);
}
