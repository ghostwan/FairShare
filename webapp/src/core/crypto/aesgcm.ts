/**
 * AES-256-GCM wrappers. The wire layout is `ciphertext || tag`
 * (16-byte tag appended), which is exactly what JDK's
 * `Cipher.doFinal()` produces and what Web Crypto's `subtle.encrypt`
 * returns — so no manual layout massaging is required for parity
 * with Android.
 */

export const GCM_NONCE_LEN = 12;
export const GCM_TAG_BITS = 128;

async function importAesKey(keyBytes: Uint8Array): Promise<CryptoKey> {
  if (keyBytes.length !== 32) {
    throw new Error(`AES-256 key must be 32 bytes, got ${keyBytes.length}`);
  }
  return crypto.subtle.importKey(
    "raw",
    keyBytes as BufferSource,
    { name: "AES-GCM" },
    false,
    ["encrypt", "decrypt"],
  );
}

function checkNonce(nonce: Uint8Array): void {
  if (nonce.length !== GCM_NONCE_LEN) {
    throw new Error(`GCM nonce must be ${GCM_NONCE_LEN} bytes`);
  }
}

/** AES-256-GCM encrypt. Returns `ciphertext || 16-byte tag`. */
export async function aesGcmEncrypt(
  key: Uint8Array,
  nonce: Uint8Array,
  plaintext: Uint8Array,
): Promise<Uint8Array> {
  checkNonce(nonce);
  const cryptoKey = await importAesKey(key);
  const buf = await crypto.subtle.encrypt(
    { name: "AES-GCM", iv: nonce as BufferSource, tagLength: GCM_TAG_BITS },
    cryptoKey,
    plaintext as BufferSource,
  );
  return new Uint8Array(buf);
}

/**
 * AES-256-GCM decrypt. Throws if the tag fails verification (wrong
 * key, tampered ciphertext, or wrong nonce). The thrown error is a
 * generic `Error` because browser implementations vary in the exact
 * subclass they use — callers should treat any rejection as "skip this
 * op + log a warning".
 */
export async function aesGcmDecrypt(
  key: Uint8Array,
  nonce: Uint8Array,
  ciphertext: Uint8Array,
): Promise<Uint8Array> {
  checkNonce(nonce);
  const cryptoKey = await importAesKey(key);
  const buf = await crypto.subtle.decrypt(
    { name: "AES-GCM", iv: nonce as BufferSource, tagLength: GCM_TAG_BITS },
    cryptoKey,
    ciphertext as BufferSource,
  );
  return new Uint8Array(buf);
}

/** Returns a fresh cryptographically random 12-byte GCM nonce. */
export function randomNonce(): Uint8Array {
  const n = new Uint8Array(GCM_NONCE_LEN);
  crypto.getRandomValues(n);
  return n;
}

/** Returns a fresh cryptographically random 32-byte AES key. */
export function randomAesKey(): Uint8Array {
  const k = new Uint8Array(32);
  crypto.getRandomValues(k);
  return k;
}
