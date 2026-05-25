/**
 * Base64 / Base64URL / PEM helpers shared by `fcm.ts` and `index.ts`.
 * Kept in a separate module so the FCM signer doesn't need to import
 * anything from the main worker file.
 */

export function base64UrlEncodeStr(s: string): string {
    return base64UrlEncodeBytes(new TextEncoder().encode(s));
}

export function base64UrlEncodeBytes(bytes: Uint8Array): string {
    let bin = "";
    for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
    return btoa(bin).replace(/=+$/, "").replace(/\+/g, "-").replace(/\//g, "_");
}

/**
 * Strips the `-----BEGIN PRIVATE KEY-----` / `-----END PRIVATE KEY-----`
 * armor and decodes the PKCS#8 body to raw DER bytes. Service account
 * keys from Google are always PKCS#8 PEM.
 */
export function pemToDer(pem: string): Uint8Array {
    const body = pem
        .replace(/-----BEGIN [^-]+-----/, "")
        .replace(/-----END [^-]+-----/, "")
        .replace(/\s+/g, "");
    const bin = atob(body);
    const out = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
}
