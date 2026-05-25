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
        },
      },
    },
    {
      opId: "op-2",
      eventId: EVENT_ID,
      deviceId: "dev-a",
      lamport: 2,
      wallClockMs: 1700000001000,
      payload: {
        type: OP_TYPE.ParticipantUpsert,
        participant: { id: "p1", eventId: EVENT_ID, name: "Alice" },
      },
    },
    {
      opId: "op-3",
      eventId: EVENT_ID,
      deviceId: "dev-a",
      lamport: 3,
      wallClockMs: 1700000002000,
      payload: {
        type: OP_TYPE.ExpenseUpsert,
        expense: {
          id: "exp-1",
          eventId: EVENT_ID,
          title: "Pizza",
          amountCents: 1500,
          payerId: "p1",
          date: 1700000002000,
          shares: [{ id: "sh-1", participantId: "p1", amountCents: 1500 }],
          items: [],
          isSettlement: false,
          categoryId: "default.restaurant",
        },
      },
    },
  ];
}

describe("invitation.codec", () => {
  it("round-trips https form", async () => {
    const url = await encodeInvitation(EVENT_ID, makeOps(), eventKey(), "https");
    expect(url.startsWith("https://fairshare-web-bdg.pages.dev/join?")).toBe(true);
    const back = await decodeInvitation(url);
    expect(back.eventId).toBe(EVENT_ID);
    expect(back.eventKey).toEqual(eventKey());
    expect(back.ops).toEqual(makeOps());
  });

  it("round-trips legacy fairshare:// form", async () => {
    const url = await encodeInvitation(EVENT_ID, makeOps(), eventKey(), "custom");
    expect(url.startsWith("fairshare://join?")).toBe(true);
    const back = await decodeInvitation(url);
    expect(back.ops).toEqual(makeOps());
  });

  it("rejects a tampered key (HMAC mismatch)", async () => {
    const url = await encodeInvitation(EVENT_ID, makeOps(), eventKey());
    // Flip the first char after key=.
    const tampered = url.replace(/key=([A-Za-z0-9_-]{1})/, (_, c) => {
      const flip = c === "A" ? "B" : "A";
      return `key=${flip}`;
    });
    await expect(decodeInvitation(tampered)).rejects.toBeInstanceOf(
      InvitationDecodeException,
    );
  });

  it("rejects a missing field", async () => {
    await expect(
      decodeInvitation("https://fairshare-web-bdg.pages.dev/join?event=x&key=y"),
    ).rejects.toMatchObject({ error: { kind: "MissingFields" } });
  });

  it("rejects malformed URL", async () => {
    await expect(decodeInvitation("notaurl")).rejects.toMatchObject({
      error: { kind: "MalformedUrl" },
    });
  });
});

describe("envelope.encryptOp", () => {
  it("round-trips an op through AES-GCM", async () => {
    const ek = eventKey();
    const ck = await deriveCloudCipherKey(ek);
    const original = makeOps()[2]!; // ExpenseUpsert
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
