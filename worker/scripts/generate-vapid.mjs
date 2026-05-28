/**
 * One-shot VAPID keypair generator. Prints:
 *   - VAPID_PRIVATE_KEY: base64url-encoded JWK JSON (paste into
 *     `wrangler secret put VAPID_PRIVATE_KEY`)
 *   - VAPID_PUBLIC_KEY:  base64url uncompressed P-256 point
 *     (set as a `[vars]` entry in wrangler.toml so it survives
 *     redeploys without re-pasting the secret)
 *
 * Run: `node worker/scripts/generate-vapid.mjs`
 */
import { webcrypto as crypto } from "node:crypto";

function base64UrlEncode(bytes) {
  let bin = "";
  for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
  return Buffer.from(bin, "binary")
    .toString("base64")
    .replace(/=+$/, "")
    .replace(/\+/g, "-")
    .replace(/\//g, "_");
}

function base64UrlEncodeStr(s) {
  return base64UrlEncode(new TextEncoder().encode(s));
}

const pair = await crypto.subtle.generateKey(
  { name: "ECDSA", namedCurve: "P-256" },
  true,
  ["sign", "verify"],
);

// JWK has d (private scalar), x, y (public coords) — exactly what
// `web-push.ts:tryParseJwk` expects.
const jwk = await crypto.subtle.exportKey("jwk", pair.privateKey);
const privateKeyB64 = base64UrlEncodeStr(JSON.stringify(jwk));

// Raw 65-byte uncompressed point for the `applicationServerKey` the
// browser hands to `pushManager.subscribe`.
const raw = new Uint8Array(await crypto.subtle.exportKey("raw", pair.publicKey));
const publicKeyB64 = base64UrlEncode(raw);

console.log("VAPID_PUBLIC_KEY (set as wrangler.toml [vars]):");
console.log(publicKeyB64);
console.log("");
console.log("VAPID_PRIVATE_KEY (paste into `wrangler secret put VAPID_PRIVATE_KEY`):");
console.log(privateKeyB64);
console.log("");
console.log("VAPID_SUBJECT (set as wrangler.toml [vars]):");
console.log("mailto:ghostwan@gmail.com");
