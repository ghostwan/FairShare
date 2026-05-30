/**
 * Base64 helpers. Two flavours coexist in FairShare:
 *
 *   - **Standard with padding**: used on the cloud wire (nonce,
 *     ciphertext). Matches what `okio.ByteString.base64()` produces on
 *     Android, and what the Worker emits on pull.
 *   - **URL-safe without padding**: used in `fairshare://join` and
 *     `https://…/join` invitation URLs (key, seed, sig). Matches
 *     `java.util.Base64.getUrlEncoder().withoutPadding()`.
 *
 * Implementation: hand-rolled to avoid Buffer/atob/btoa portability
 * footguns. Both encoders run over `Uint8Array` and produce ASCII
 * strings; decoders are tolerant of trailing whitespace (the URL may
 * have been line-wrapped on its way to the user).
 */

const STD_ALPHABET =
  "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
const URL_ALPHABET =
  "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

function buildDecodeTable(alphabet: string): Int8Array {
  const t = new Int8Array(128).fill(-1);
  for (let i = 0; i < alphabet.length; i++) {
    t[alphabet.charCodeAt(i)] = i;
  }
  return t;
}

const STD_DECODE = buildDecodeTable(STD_ALPHABET);
const URL_DECODE = buildDecodeTable(URL_ALPHABET);

function encode(bytes: Uint8Array, alphabet: string, pad: boolean): string {
  let out = "";
  let i = 0;
  for (; i + 3 <= bytes.length; i += 3) {
    const a = bytes[i]!;
    const b = bytes[i + 1]!;
    const c = bytes[i + 2]!;
    out += alphabet[a >> 2];
    out += alphabet[((a & 0x03) << 4) | (b >> 4)];
    out += alphabet[((b & 0x0f) << 2) | (c >> 6)];
    out += alphabet[c & 0x3f];
  }
  const rem = bytes.length - i;
  if (rem === 1) {
    const a = bytes[i]!;
    out += alphabet[a >> 2];
    out += alphabet[(a & 0x03) << 4];
    if (pad) out += "==";
  } else if (rem === 2) {
    const a = bytes[i]!;
    const b = bytes[i + 1]!;
    out += alphabet[a >> 2];
    out += alphabet[((a & 0x03) << 4) | (b >> 4)];
    out += alphabet[(b & 0x0f) << 2];
    if (pad) out += "=";
  }
  return out;
}

function decode(input: string, table: Int8Array): Uint8Array {
  // Strip padding + whitespace; tolerate URL-safe input even on the
  // standard decoder (Android's WorkerCloudTransport happens to use
  // standard, but third-party tooling may feed URL-safe).
  let s = input.replace(/[\s=]+$/g, "");
  s = s.replace(/\s+/g, "");
  const len = s.length;
  const fullGroups = Math.floor(len / 4);
  const remainder = len - fullGroups * 4;
  let outLen = fullGroups * 3;
  if (remainder === 2) outLen += 1;
  else if (remainder === 3) outLen += 2;
  else if (remainder !== 0) {
    throw new Error(`base64: invalid length ${len} (remainder ${remainder})`);
  }
  const out = new Uint8Array(outLen);
  let oi = 0;
  let buf = 0;
  let bits = 0;
  for (let i = 0; i < len; i++) {
    const code = s.charCodeAt(i);
    const v = code < 128 ? table[code]! : -1;
    if (v < 0) {
      // Fall back to the other alphabet's digit if the symbol is
      // valid there — handles URL-safe input fed to a standard decoder
      // (Android Worker historically tolerated this).
      const alt =
        table === STD_DECODE
          ? URL_DECODE[code]
          : STD_DECODE[code];
      if (alt == null || alt < 0) {
        throw new Error(`base64: invalid char '${s[i]}' at index ${i}`);
      }
      buf = (buf << 6) | alt;
    } else {
      buf = (buf << 6) | v;
    }
    bits += 6;
    if (bits >= 8) {
      bits -= 8;
      out[oi++] = (buf >> bits) & 0xff;
    }
  }
  return out;
}

export function base64StdEncode(bytes: Uint8Array): string {
  return encode(bytes, STD_ALPHABET, true);
}

export function base64StdDecode(input: string): Uint8Array {
  return decode(input, STD_DECODE);
}

export function base64UrlEncode(bytes: Uint8Array): string {
  return encode(bytes, URL_ALPHABET, false);
}

export function base64UrlDecode(input: string): Uint8Array {
  return decode(input, URL_DECODE);
}

/** Lowercase hex of a byte buffer. */
export function toHex(bytes: Uint8Array): string {
  let out = "";
  for (let i = 0; i < bytes.length; i++) {
    out += bytes[i]!.toString(16).padStart(2, "0");
  }
  return out;
}

/** UTF-8 encode helper, since `TextEncoder` is verbose at the call site. */
export function utf8Encode(s: string): Uint8Array {
  return new TextEncoder().encode(s);
}

/** UTF-8 decode helper. */
export function utf8Decode(bytes: Uint8Array): string {
  return new TextDecoder().decode(bytes);
}

/** US-ASCII encode (rejects non-ASCII). Used for HMAC over the b64url seed. */
export function asciiEncode(s: string): Uint8Array {
  const out = new Uint8Array(s.length);
  for (let i = 0; i < s.length; i++) {
    const c = s.charCodeAt(i);
    if (c > 0x7f) throw new Error(`asciiEncode: non-ASCII char at ${i}`);
    out[i] = c;
  }
  return out;
}
