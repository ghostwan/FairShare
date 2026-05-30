/**
 * HMAC-SHA256 + HKDF-SHA256 (RFC 5869).
 *
 * Web Crypto's HKDF only exposes a derive-only path that requires the
 * salt up front, but the FairShare protocol always uses a fixed
 * 32-byte zero salt (matching `SyncCrypto.hkdfSha256(salt = ByteArray(HASH_LEN))`),
 * so the wrapper hides that detail. We could call `crypto.subtle.deriveBits`
 * directly but the manual extract+expand implementation is just as
 * fast at the sizes we use (≤ 64 bytes) and removes a class of
 * platform-specific bugs around algorithm parameter shape.
 */

const SHA = "SHA-256";
const HASH_LEN = 32;

async function importHmacKey(keyBytes: Uint8Array): Promise<CryptoKey> {
  // HMAC keys can be any length; subtle accepts whatever we hand it.
  return crypto.subtle.importKey(
    "raw",
    keyBytes as BufferSource,
    { name: "HMAC", hash: SHA },
    false,
    ["sign"],
  );
}

/** HMAC-SHA256(key, data). Returns a fresh 32-byte buffer. */
export async function hmacSha256(
  key: Uint8Array,
  data: Uint8Array,
): Promise<Uint8Array> {
  const cryptoKey = await importHmacKey(key);
  const sig = await crypto.subtle.sign("HMAC", cryptoKey, data as BufferSource);
  return new Uint8Array(sig);
}

/**
 * HKDF-SHA256 with a 32-byte zero salt by default. The default matches
 * `SyncCrypto.hkdfSha256` on Android; pass an explicit `salt` to
 * reproduce RFC 5869 test vectors.
 */
export async function hkdfSha256(
  inputKey: Uint8Array,
  info: Uint8Array,
  outputLength: number = HASH_LEN,
  salt: Uint8Array = new Uint8Array(HASH_LEN),
): Promise<Uint8Array> {
  if (outputLength < 1 || outputLength > 255 * HASH_LEN) {
    throw new RangeError(`HKDF outputLength out of range: ${outputLength}`);
  }

  // Extract: PRK = HMAC(salt, IKM)
  const prk = await hmacSha256(salt, inputKey);

  // Expand: T(i) = HMAC(PRK, T(i-1) || info || counter)
  const out = new Uint8Array(outputLength);
  let previous: Uint8Array = new Uint8Array(0);
  let written = 0;
  let counter = 1;
  while (written < outputLength) {
    const block = new Uint8Array(previous.length + info.length + 1);
    block.set(previous, 0);
    block.set(info, previous.length);
    block[previous.length + info.length] = counter & 0xff;
    previous = await hmacSha256(prk, block);
    const take = Math.min(HASH_LEN, outputLength - written);
    out.set(previous.subarray(0, take), written);
    written += take;
    counter++;
  }
  return out;
}
