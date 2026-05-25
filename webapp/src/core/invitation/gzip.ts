import { gzip as pakoGzip, ungzip as pakoUngzip } from "pako";

/**
 * Thin wrapper around pako so callers can stay agnostic of the choice
 * of gzip implementation. Pako uses zlib defaults (level 6) which
 * differs from JDK's `GZIPOutputStream` defaults, so the bytes
 * produced here won't be byte-equal to Android — that's fine because:
 *
 *   1. The invitation HMAC is computed over the base64url string of
 *      the *gzip output we just produced*, not over a reference
 *      Kotlin output, so HMAC verification still passes on the same
 *      device that generated the invitation.
 *   2. Cross-platform decoding works regardless of compression level
 *      because the gunzip side reads the gzip header to know how to
 *      decompress.
 */
export function gzip(bytes: Uint8Array): Uint8Array {
  return pakoGzip(bytes);
}

export function ungzip(bytes: Uint8Array): Uint8Array {
  return pakoUngzip(bytes);
}
