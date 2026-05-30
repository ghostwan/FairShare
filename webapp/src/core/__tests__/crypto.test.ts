import { describe, expect, it } from "vitest";
import { aesGcmDecrypt, aesGcmEncrypt } from "../crypto/aesgcm";
import {
  computeWorkerBearer,
  deriveCloudCipherKey,
  deriveInvitationMacKey,
  deriveWorkerAuthKey,
} from "../crypto/bearer";
import { constantTimeEquals } from "../crypto/constantTime";
import { hkdfSha256, hmacSha256 } from "../crypto/hkdf";
import {
  base64StdDecode,
  base64StdEncode,
  base64UrlDecode,
  base64UrlEncode,
  toHex,
  utf8Encode,
} from "../crypto/base64";

function hex(s: string): Uint8Array {
  const clean = s.replace(/[\s\n]/g, "");
  const out = new Uint8Array(clean.length / 2);
  for (let i = 0; i < out.length; i++) {
    out[i] = parseInt(clean.substring(i * 2, i * 2 + 2), 16);
  }
  return out;
}

describe("crypto.hkdf", () => {
  // RFC 5869 §A.1: basic HKDF-SHA256 test case. Locks the implementation
  // to a well-known reference output so any regression fails loudly.
  it("matches RFC 5869 §A.1", async () => {
    const ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b");
    const salt = hex("000102030405060708090a0b0c");
    const info = hex("f0f1f2f3f4f5f6f7f8f9");
    const expected = hex(
      "3cb25f25faacd57a90434f64d0362f2a" +
        "2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
        "34007208d5b887185865",
    );
    const actual = await hkdfSha256(ikm, info, 42, salt);
    expect(actual).toEqual(expected);
  });

  it("matches RFC 5869 §A.2 (long inputs)", async () => {
    const ikm = hex(
      "000102030405060708090a0b0c0d0e0f" +
        "101112131415161718191a1b1c1d1e1f" +
        "202122232425262728292a2b2c2d2e2f" +
        "303132333435363738393a3b3c3d3e3f" +
        "404142434445464748494a4b4c4d4e4f",
    );
    const salt = hex(
      "606162636465666768696a6b6c6d6e6f" +
        "707172737475767778797a7b7c7d7e7f" +
        "808182838485868788898a8b8c8d8e8f" +
        "909192939495969798999a9b9c9d9e9f" +
        "a0a1a2a3a4a5a6a7a8a9aaabacadaeaf",
    );
    const info = hex(
      "b0b1b2b3b4b5b6b7b8b9babbbcbdbebf" +
        "c0c1c2c3c4c5c6c7c8c9cacbcccdcecf" +
        "d0d1d2d3d4d5d6d7d8d9dadbdcdddedf" +
        "e0e1e2e3e4e5e6e7e8e9eaebecedeeef" +
        "f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff",
    );
    const expected = hex(
      "b11e398dc80327a1c8e7f78c596a4934" +
        "4f012eda2d4efad8a050cc4c19afa97c" +
        "59045a99cac7827271cb41c65e590e09" +
        "da3275600c2f09b8367793a9aca3db71" +
        "cc30c58179ec3e87c14c01d5c1f3434f" +
        "1d87",
    );
    const actual = await hkdfSha256(ikm, info, 82, salt);
    expect(actual).toEqual(expected);
  });

  it("default 32-byte zero salt is deterministic", async () => {
    const key = new Uint8Array(32).map((_, i) => i);
    const a = await hkdfSha256(key, utf8Encode("info"));
    const b = await hkdfSha256(key, utf8Encode("info"));
    expect(a).toEqual(b);
    expect(a.length).toBe(32);
  });

  it("different info strings produce different keys", async () => {
    const key = new Uint8Array(32).fill(7);
    const a = await hkdfSha256(key, utf8Encode("fairshare-invitation-mac"));
    const b = await hkdfSha256(key, utf8Encode("fairshare-worker-auth"));
    expect(a).not.toEqual(b);
  });
});

describe("crypto.hmac", () => {
  it("is deterministic and changes with input", async () => {
    const key = utf8Encode("secret-key");
    const a = await hmacSha256(key, utf8Encode("hello-fairshare"));
    const b = await hmacSha256(key, utf8Encode("hello-fairshare"));
    expect(a).toEqual(b);
    expect(a.length).toBe(32);
    const tampered = await hmacSha256(key, utf8Encode("hello-fairshore"));
    expect(tampered).not.toEqual(a);
  });
});

describe("crypto.constantTimeEquals", () => {
  it("returns true only for identical buffers", () => {
    const a = new Uint8Array(32).map((_, i) => i);
    const b = a.slice();
    expect(constantTimeEquals(a, b)).toBe(true);
    b[15] = b[15]! ^ 1;
    expect(constantTimeEquals(a, b)).toBe(false);
    expect(constantTimeEquals(a, new Uint8Array(31))).toBe(false);
  });
});

describe("crypto.aesGcm", () => {
  it("round-trips and emits ciphertext||tag", async () => {
    const key = new Uint8Array(32).map((_, i) => (i * 7 + 3) & 0xff);
    const nonce = new Uint8Array(12).map((_, i) => (i * 5) & 0xff);
    const pt = utf8Encode("fairshare-op-payload");
    const ct = await aesGcmEncrypt(key, nonce, pt);
    expect(ct.length).toBe(pt.length + 16);
    const back = await aesGcmDecrypt(key, nonce, ct);
    expect(Array.from(back)).toEqual(Array.from(pt));
  });

  it("rejects tampered ciphertext", async () => {
    const key = new Uint8Array(32).fill(1);
    const nonce = new Uint8Array(12).fill(2);
    const ct = await aesGcmEncrypt(key, nonce, utf8Encode("hello"));
    ct[0] = ct[0]! ^ 1;
    await expect(aesGcmDecrypt(key, nonce, ct)).rejects.toBeDefined();
  });

  it("rejects wrong key", async () => {
    const key = new Uint8Array(32).fill(1);
    const nonce = new Uint8Array(12).fill(2);
    const ct = await aesGcmEncrypt(key, nonce, utf8Encode("hello"));
    const wrong = new Uint8Array(32).fill(99);
    await expect(aesGcmDecrypt(wrong, nonce, ct)).rejects.toBeDefined();
  });

  it("rejects invalid key or nonce sizes", async () => {
    await expect(
      aesGcmEncrypt(new Uint8Array(16), new Uint8Array(12), new Uint8Array(0)),
    ).rejects.toThrow(/32 bytes/);
    await expect(
      aesGcmEncrypt(new Uint8Array(32), new Uint8Array(8), new Uint8Array(0)),
    ).rejects.toThrow(/12 bytes/);
  });
});

describe("crypto.bearer", () => {
  it("derives three distinct 32-byte sub-keys", async () => {
    const k = new Uint8Array(32).map((_, i) => i);
    const mac = await deriveInvitationMacKey(k);
    const auth = await deriveWorkerAuthKey(k);
    const cipher = await deriveCloudCipherKey(k);
    expect(mac.length).toBe(32);
    expect(auth.length).toBe(32);
    expect(cipher.length).toBe(32);
    expect(mac).not.toEqual(auth);
    expect(mac).not.toEqual(cipher);
    expect(auth).not.toEqual(cipher);
  });

  it("computeWorkerBearer is 64 lowercase hex and deterministic", async () => {
    const k = new Uint8Array(32).map((_, i) => i + 1);
    const a = await computeWorkerBearer(k, "evt-1");
    const b = await computeWorkerBearer(k, "evt-1");
    expect(a).toBe(b);
    expect(a).toMatch(/^[0-9a-f]{64}$/);
  });

  it("bearer differs per event and per key", async () => {
    const k1 = new Uint8Array(32).fill(1);
    const k2 = new Uint8Array(32).fill(2);
    expect(await computeWorkerBearer(k1, "evt-1")).not.toBe(
      await computeWorkerBearer(k1, "evt-2"),
    );
    expect(await computeWorkerBearer(k1, "evt-1")).not.toBe(
      await computeWorkerBearer(k2, "evt-1"),
    );
  });
});

describe("crypto.base64", () => {
  it("standard round-trip with padding", () => {
    const cases: [string, string][] = [
      ["", ""],
      ["f", "Zg=="],
      ["fo", "Zm8="],
      ["foo", "Zm9v"],
      ["foob", "Zm9vYg=="],
      ["fooba", "Zm9vYmE="],
      ["foobar", "Zm9vYmFy"],
    ];
    for (const [plain, encoded] of cases) {
      expect(base64StdEncode(utf8Encode(plain))).toBe(encoded);
      expect(Array.from(base64StdDecode(encoded))).toEqual(
        Array.from(utf8Encode(plain)),
      );
    }
  });

  it("url-safe round-trip without padding", () => {
    const bytes = new Uint8Array([
      0xfb, 0xff, 0xbf, 0x00, 0x10, 0x83, 0x10, 0x51, 0x87, 0x20, 0x92, 0x8b,
      0x30, 0xd3, 0x8f,
    ]);
    const enc = base64UrlEncode(bytes);
    expect(enc).not.toMatch(/=/);
    expect(enc).not.toMatch(/[+/]/);
    expect(base64UrlDecode(enc)).toEqual(bytes);
  });

  it("toHex returns lowercase", () => {
    expect(toHex(new Uint8Array([0x00, 0x0f, 0xff]))).toBe("000fff");
  });
});
