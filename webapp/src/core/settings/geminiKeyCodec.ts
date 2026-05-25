/**
 * Encodes / decodes a Gemini API key + model into a QR-friendly URL.
 *
 * Wire format (byte-compatible with the Android
 * `com.fairshare.data.settings.GeminiKeyCodec`):
 *
 *   fairshare://gemini?key=<urlencoded-key>&model=<urlencoded-model>
 *
 * `model` is optional. Plaintext on purpose — the key is a secret the
 * user already trusts on the source device, the QR is displayed
 * momentarily, and no Cloud Worker ever sees it. Use only with the
 * in-app scanner; no `https://` variant is offered so external camera
 * apps don't accidentally redirect through a browser.
 */

const SCHEME = "fairshare://gemini?";

export interface GeminiKeyExport {
  key: string;
  model: string | null;
}

export function encodeGeminiKey(key: string, model: string | null): string {
  const trimmedKey = key.trim();
  if (trimmedKey.length === 0) {
    throw new Error("key must not be blank");
  }
  let q = `key=${encodeURIComponent(trimmedKey)}`;
  const trimmedModel = model?.trim();
  if (trimmedModel) {
    q += `&model=${encodeURIComponent(trimmedModel)}`;
  }
  return SCHEME + q;
}

export function decodeGeminiKey(url: string): GeminiKeyExport {
  if (!url.startsWith(SCHEME)) {
    throw new Error("not a fairshare gemini URL");
  }
  const query = url.substring(SCHEME.length);
  let key: string | null = null;
  let model: string | null = null;
  for (const pair of query.split("&")) {
    const eq = pair.indexOf("=");
    if (eq <= 0 || eq === pair.length - 1) continue;
    const name = pair.substring(0, eq);
    const value = decodeURIComponent(pair.substring(eq + 1));
    if (name === "key") key = value;
    else if (name === "model") model = value;
  }
  if (key == null || key.length === 0) {
    throw new Error("missing key field");
  }
  return { key, model: model && model.length > 0 ? model : null };
}

export function isGeminiKeyUrl(url: string): boolean {
  return url.startsWith(SCHEME);
}
