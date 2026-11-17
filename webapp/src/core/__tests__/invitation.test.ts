import { describe, expect, it } from "vitest";
import {
  decodeInvitation,
  encodeInvitation,
  InvitationDecodeException,
} from "../invitation/codec";
import { decryptOp, encryptOp } from "../sync/envelope";
import { deriveCloudCipherKey } from "../crypto/bearer";
import { OP_TYPE } from "../sync/opPayload";
import type { Operation } from "../sync/operation";

const EVENT_ID = "11111111-2222-4333-8444-555555555555";

function eventKey(): Uint8Array {
  // Deterministic test key; never use a non-random key in prod.
  return new Uint8Array(32).map((_, i) => (i * 13 + 7) & 0xff);
}

function makeOps(): Operation[] {
  return [
    {
      opId: "op-1",
      eventId: EVENT_ID,
      deviceId: "dev-a",
      lamport: 1,
      wallClockMs: 1700000000000,
      payload: {
        type: OP_TYPE.EventUpsert,
        event: {
          id: EVENT_ID,
          name: "Trip à Berlin",
          description: null,
          currency: "EUR",
          createdAt: 1700000000000,
          archived: false,
          giftModeEnabled: true,
        },
      },
    },
  ];
}

describe("invitation.codec", () => {
  it("round-trips https form", () => {
    const url = encodeInvitation(EVENT_ID, eventKey(), "https");
    expect(url.startsWith("https://fairshare-web-bdg.pages.dev/join?")).toBe(true);
    const back = decodeInvitation(url);
    expect(back.eventId).toBe(EVENT_ID);
    expect(Array.from(back.eventKey)).toEqual(Array.from(eventKey()));
  });

  it("round-trips legacy fairshare:// form", () => {
    const url = encodeInvitation(EVENT_ID, eventKey(), "custom");
    expect(url.startsWith("fairshare://join?")).toBe(true);
    const back = decodeInvitation(url);
    expect(back.eventId).toBe(EVENT_ID);
  });

  it("omits gemini params when no key is shared", () => {
    const url = encodeInvitation(EVENT_ID, eventKey(), "https", {
      key: "  ",
      model: "m",
    });
    expect(url).not.toContain("gk=");
    expect(url).not.toContain("gm=");
    const back = decodeInvitation(url);
    expect(back.geminiKey).toBeNull();
    expect(back.geminiModel).toBeNull();
  });

  it("round-trips gemini key + model", () => {
    const url = encodeInvitation(EVENT_ID, eventKey(), "https", {
      key: " AIzaSy-abc_DEF/+= ",
      model: "gemini-2.5-flash",
    });
    expect(url.split("#")[1]).toBe("gk=AIzaSy-abc_DEF%2F%2B%3D&gm=gemini-2.5-flash");
    expect(url.split("#")[0]).not.toContain("gk=");
    const back = decodeInvitation(url);
    expect(back.eventId).toBe(EVENT_ID);
    expect(Array.from(back.eventKey)).toEqual(Array.from(eventKey()));
    expect(back.geminiKey).toBe("AIzaSy-abc_DEF/+=");
    expect(back.geminiModel).toBe("gemini-2.5-flash");
  });

  it("ignores a gemini model without key", () => {
    const base = encodeInvitation(EVENT_ID, eventKey(), "custom");
    const back = decodeInvitation(`${base}#gm=gemini-2.5-pro`);
    expect(back.geminiKey).toBeNull();
    expect(back.geminiModel).toBeNull();
  });

  it("decodes the Android gemini wire format", () => {
    // Same vector as InvitationCodecTest.kt (URLEncoder + %20).
    const base = encodeInvitation(EVENT_ID, eventKey(), "https");
    const back = decodeInvitation(`${base}#gk=AIza%20x%2Fy&gm=gemini-2.5-pro`);
    expect(back.geminiKey).toBe("AIza x/y");
    expect(back.geminiModel).toBe("gemini-2.5-pro");
  });

  it("ignores gemini params in the query", () => {
    const base = encodeInvitation(EVENT_ID, eventKey(), "https");
    const back = decodeInvitation(`${base}&gk=AIzaX`);
    expect(Array.from(back.eventKey)).toEqual(Array.from(eventKey()));
    expect(back.geminiKey).toBeNull();
  });

  it("stays compact (constant-size URL)", () => {
    const url = encodeInvitation(EVENT_ID, eventKey());
    // Well under the QR byte-mode capacity at L (~2953 bytes).
    expect(url.length).toBeLessThan(200);
  });

  it("rejects a missing field", () => {
    expect(() =>
      decodeInvitation("https://fairshare-web-bdg.pages.dev/join?event=x"),
    ).toThrow(InvitationDecodeException);
    try {
      decodeInvitation("https://fairshare-web-bdg.pages.dev/join?event=x");
    } catch (e) {
      expect((e as InvitationDecodeException).error.kind).toBe("MissingFields");
    }
  });

  it("rejects a malformed key length", () => {
    // 16-byte key, base64url without padding = "AAAAAAAAAAAAAAAAAAAAAA"
    const shortKey = "AAAAAAAAAAAAAAAAAAAAAA";
    expect(() =>
      decodeInvitation(
        `fairshare://join?event=${EVENT_ID}&key=${shortKey}`,
      ),
    ).toThrow(InvitationDecodeException);
  });

  it("rejects a malformed URL", () => {
    expect(() => decodeInvitation("notaurl")).toThrow(InvitationDecodeException);
  });
});

describe("envelope.encryptOp", () => {
  it("round-trips an op through AES-GCM", async () => {
    const ek = eventKey();
    const ck = await deriveCloudCipherKey(ek);
    const original = makeOps()[0]!;
    const enc = await encryptOp(original, ck);
    expect(enc.opId).toBe(original.opId);
    expect(enc.lamport).toBe(original.lamport);
    expect(enc.deviceId).toBe(original.deviceId);
    expect(enc.nonce.length).toBe(12);
    expect(enc.ciphertext.length).toBeGreaterThan(16); // payload + tag
    const back = await decryptOp(enc, EVENT_ID, ck);
    expect(back).toEqual(original);
  });

  it("decryption fails with a wrong key", async () => {
    const ek = eventKey();
    const ck = await deriveCloudCipherKey(ek);
    const original = makeOps()[0]!;
    const enc = await encryptOp(original, ck);
    const wrongCk = new Uint8Array(32).fill(0);
    await expect(decryptOp(enc, EVENT_ID, wrongCk)).rejects.toBeDefined();
  });
});
