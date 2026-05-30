import { aesGcmDecrypt, aesGcmEncrypt, randomNonce } from "../crypto/aesgcm";
import { utf8Decode, utf8Encode } from "../crypto/base64";
import {
  ENVELOPE_VERSION,
  decodeEnvelopeJson,
  encodeEnvelopeJson,
} from "./codec";
import type { Operation } from "./operation";

/**
 * Encrypts the wallClockMs + payload portion of an [Operation] before
 * it leaves the device, and reverses the operation on inbound ops.
 * Mirrors `com.fairshare.data.sync.CloudOpCodec`.
 *
 * Visible to the Worker: `opId`, `lamport`, `deviceId` — needed for
 * dedup, ordering, and the LWW tiebreaker. Encrypted: everything else.
 *
 * No AAD: see Kotlin doc for the rationale (the materialiser tolerates
 * any reordering / dedup the Worker may perform).
 */

export interface EncryptedOp {
  opId: string;
  lamport: number;
  deviceId: string;
  nonce: Uint8Array;
  ciphertext: Uint8Array;
}

export async function encryptOp(
  op: Operation,
  cloudCipherKey: Uint8Array,
): Promise<EncryptedOp> {
  const json = encodeEnvelopeJson({
    version: ENVELOPE_VERSION,
    wallClockMs: op.wallClockMs,
    payload: op.payload,
  });
  const nonce = randomNonce();
  const ciphertext = await aesGcmEncrypt(cloudCipherKey, nonce, utf8Encode(json));
  return {
    opId: op.opId,
    lamport: op.lamport,
    deviceId: op.deviceId,
    nonce,
    ciphertext,
  };
}

export async function decryptOp(
  enc: EncryptedOp,
  eventId: string,
  cloudCipherKey: Uint8Array,
): Promise<Operation> {
  const plaintext = await aesGcmDecrypt(
    cloudCipherKey,
    enc.nonce,
    enc.ciphertext,
  );
  const env = decodeEnvelopeJson(utf8Decode(plaintext));
  return {
    opId: enc.opId,
    eventId,
    deviceId: enc.deviceId,
    lamport: enc.lamport,
    wallClockMs: env.wallClockMs,
    payload: env.payload,
  };
}
