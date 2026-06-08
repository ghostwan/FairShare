import { describe, expect, it } from "vitest";
import { merge, tickLocal } from "../sync/lamport";
import {
  compareLww,
  type Operation,
} from "../sync/operation";
import { OP_TYPE, type OpPayload } from "../sync/opPayload";
import {
  decodeOperation,
  decodeOperationsJson,
  encodeOperation,
  encodeOperationsJson,
  encodeEnvelopeJson,
  decodeEnvelopeJson,
  ENVELOPE_VERSION,
} from "../sync/codec";
import { resolveAll, resolveEntity } from "../sync/materializer";

function op(
  opId: string,
  lamport: number,
  deviceId: string,
  payload: OpPayload,
  eventId = "evt",
): Operation {
  return { opId, eventId, deviceId, lamport, wallClockMs: 0, payload };
}

describe("lamport", () => {
  it("tickLocal increments by 1", () => {
    expect(tickLocal(0)).toBe(1);
    expect(tickLocal(41)).toBe(42);
  });
  it("merge picks the max", () => {
    expect(merge(3, 7)).toBe(7);
    expect(merge(10, 4)).toBe(10);
    expect(merge(5, 5)).toBe(5);
  });
  it("emit-after-receive = max + 1", () => {
    expect(tickLocal(merge(3, 7))).toBe(8);
  });
});

describe("operation.compareLww", () => {
  it("orders by lamport then deviceId lex", () => {
    const a = op("o", 1, "aaa", {
      type: OP_TYPE.EventDelete,
      eventId: "x",
    });
    const b = op("o", 2, "aaa", {
      type: OP_TYPE.EventDelete,
      eventId: "x",
    });
    const c = op("o", 2, "bbb", {
      type: OP_TYPE.EventDelete,
      eventId: "x",
    });
    expect(compareLww(a, b)).toBeLessThan(0);
    expect(compareLww(b, c)).toBeLessThan(0);
    expect(compareLww(c, b)).toBeGreaterThan(0);
    expect(compareLww(b, b)).toBe(0);
  });
});

describe("codec.encodeOperation", () => {
  it("emits the Kotlin FQCN discriminator", () => {
    const o = op("op-1", 1, "dev", {
      type: OP_TYPE.EventUpsert,
      event: {
        id: "evt",
        name: "Trip",
        description: null,
        currency: "EUR",
        createdAt: 1700000000000,
        archived: false,
        giftModeEnabled: true,
      },
    });
    const enc = encodeOperation(o) as Record<string, unknown>;
    const payload = enc.payload as Record<string, unknown>;
    expect(payload.type).toBe(
      "com.fairshare.domain.model.sync.OpPayload.EventUpsert",
    );
    // Field order in JSON should follow Kotlin declaration order.
    const json = JSON.stringify(enc);
    expect(json.indexOf('"opId"')).toBeLessThan(json.indexOf('"eventId"'));
    expect(json.indexOf('"eventId"')).toBeLessThan(json.indexOf('"deviceId"'));
    expect(json.indexOf('"deviceId"')).toBeLessThan(json.indexOf('"lamport"'));
    expect(json.indexOf('"lamport"')).toBeLessThan(json.indexOf('"wallClockMs"'));
    expect(json.indexOf('"wallClockMs"')).toBeLessThan(json.indexOf('"payload"'));
    // Envelope-side: discriminator first.
    expect(json.indexOf('"type"')).toBeLessThan(json.indexOf('"event"'));
    // Snapshot defaults are emitted (encodeDefaults = true).
    expect(json).toContain('"description":null');
    expect(json).toContain('"archived":false');
    expect(json).toContain('"currency":"EUR"');
    // giftModeEnabled is webapp-only but still always emitted so the
    // declaration order stays stable.
    expect(json).toContain('"giftModeEnabled":true');
    // And it sits after archived per declaration order.
    expect(json.indexOf('"archived"')).toBeLessThan(
      json.indexOf('"giftModeEnabled"'),
    );
  });

  it("expense snapshot defaults all emitted", () => {
    const o = op("op-1", 1, "dev", {
      type: OP_TYPE.ExpenseUpsert,
      expense: {
        id: "exp",
        eventId: "evt",
        title: "Pizza",
        amountCents: 1500,
        payerId: "p1",
        date: 1700000000000,
        shares: [],
        items: [],
        isSettlement: false,
        categoryId: null,
      },
    });
    const json = JSON.stringify(encodeOperation(o));
    expect(json).toContain('"shares":[]');
    expect(json).toContain('"items":[]');
    expect(json).toContain('"isSettlement":false');
    expect(json).toContain('"categoryId":null');
  });

  it("round-trip preserves all variants", () => {
    const variants: OpPayload[] = [
      {
        type: OP_TYPE.EventUpsert,
        event: {
          id: "e",
          name: "n",
          description: "d",
          currency: "USD",
          createdAt: 1,
          archived: true,
          giftModeEnabled: false,
        },
      },
      { type: OP_TYPE.EventDelete, eventId: "e" },
      {
        type: OP_TYPE.ParticipantUpsert,
        participant: { id: "p", eventId: "e", name: "Bob" },
      },
      { type: OP_TYPE.ParticipantDelete, participantId: "p" },
      {
        type: OP_TYPE.ExpenseUpsert,
        expense: {
          id: "x",
          eventId: "e",
          title: "t",
          amountCents: 99,
          payerId: "p",
          date: 5,
          shares: [
            { id: "s", participantId: "p", amountCents: 99, coveredBy: null },
          ],
          items: [
            {
              id: "i",
              label: "Pizza",
              priceCents: 500,
              quantity: 2,
              assignedTo: ["p", "q"],
            },
          ],
          isSettlement: false,
          categoryId: "default.food",
        },
      },
      { type: OP_TYPE.ExpenseDelete, expenseId: "x" },
      {
        type: OP_TYPE.CategoryUpsert,
        category: {
          id: "c",
          eventId: "e",
          name: "Custom",
          emoji: "🦊",
          color: 0xffaabbcc,
        },
      },
      { type: OP_TYPE.CategoryDelete, categoryId: "c" },
    ];
    for (const payload of variants) {
      const o = op("op", 1, "dev", payload, "e");
      const json = JSON.stringify(encodeOperation(o));
      const back = decodeOperation(JSON.parse(json));
      expect(back).toEqual(o);
    }
  });

  it("decodeOperationsJson tolerates a missing payload default", () => {
    // Simulate a peer that omitted defaults (encodeDefaults=false-like).
    const wire = JSON.stringify([
      {
        opId: "o",
        eventId: "e",
        deviceId: "d",
        lamport: 1,
        wallClockMs: 0,
        payload: {
          type: "com.fairshare.domain.model.sync.OpPayload.ExpenseUpsert",
          expense: {
            id: "x",
            eventId: "e",
            title: "t",
            amountCents: 1,
            payerId: "p",
            date: 0,
          },
        },
      },
    ]);
    const [back] = decodeOperationsJson(wire);
    expect(back!.payload.type).toBe(OP_TYPE.ExpenseUpsert);
    const expensePayload = back!.payload as Extract<
      OpPayload,
      { type: typeof OP_TYPE.ExpenseUpsert }
    >;
    expect(expensePayload.expense.shares).toEqual([]);
    expect(expensePayload.expense.items).toEqual([]);
    expect(expensePayload.expense.categoryId).toBeNull();
    expect(expensePayload.expense.isSettlement).toBe(false);
  });

  it("decodes an EventUpsert without giftModeEnabled and defaults it to true", () => {
    // Mirrors what an Android peer (or a webapp release < 0.2.0)
    // emits: the snapshot lacks the giftModeEnabled field entirely.
    // The decoder must default to `true` so the feature stays on for
    // events relayed by peers that don't know about the flag.
    const wire = JSON.stringify([
      {
        opId: "o",
        eventId: "e",
        deviceId: "d",
        lamport: 1,
        wallClockMs: 0,
        payload: {
          type: "com.fairshare.domain.model.sync.OpPayload.EventUpsert",
          event: {
            id: "e",
            name: "Trip",
            description: null,
            currency: "EUR",
            createdAt: 1,
            archived: false,
          },
        },
      },
    ]);
    const [back] = decodeOperationsJson(wire);
    const payload = back!.payload as Extract<
      OpPayload,
      { type: typeof OP_TYPE.EventUpsert }
    >;
    expect(payload.event.giftModeEnabled).toBe(true);
  });

  it("envelope JSON declaration order", () => {
    const json = encodeEnvelopeJson({
      version: ENVELOPE_VERSION,
      wallClockMs: 42,
      payload: { type: OP_TYPE.EventDelete, eventId: "x" },
    });
    expect(json.indexOf('"version"')).toBeLessThan(
      json.indexOf('"wallClockMs"'),
    );
    expect(json.indexOf('"wallClockMs"')).toBeLessThan(
      json.indexOf('"payload"'),
    );
    const back = decodeEnvelopeJson(json);
    expect(back.version).toBe(1);
    expect(back.wallClockMs).toBe(42);
  });

  it("encodes a multi-op list", () => {
    const ops: Operation[] = [
      op("o1", 1, "d", { type: OP_TYPE.EventDelete, eventId: "e" }),
      op("o2", 2, "d", { type: OP_TYPE.EventDelete, eventId: "e" }),
    ];
    const json = encodeOperationsJson(ops);
    expect(json.startsWith("[")).toBe(true);
    expect(decodeOperationsJson(json)).toEqual(ops);
  });
});

describe("materializer.resolveAll", () => {
  it("picks the highest (lamport, deviceId) per entity", () => {
    const ops: Operation[] = [
      op(
        "1",
        1,
        "dev-a",
        {
          type: OP_TYPE.ExpenseUpsert,
          expense: {
            id: "x",
            eventId: "evt",
            title: "first",
            amountCents: 100,
            payerId: "p",
            date: 0,
            shares: [],
            items: [],
            isSettlement: false,
            categoryId: null,
          },
        },
      ),
      op(
        "2",
        2,
        "dev-a",
        {
          type: OP_TYPE.ExpenseUpsert,
          expense: {
            id: "x",
            eventId: "evt",
            title: "second",
            amountCents: 200,
            payerId: "p",
            date: 0,
            shares: [],
            items: [],
            isSettlement: false,
            categoryId: null,
          },
        },
      ),
      // Same lamport — deviceId tiebreaker wins for "dev-b" > "dev-a".
      op(
        "3",
        2,
        "dev-b",
        {
          type: OP_TYPE.ExpenseUpsert,
          expense: {
            id: "x",
            eventId: "evt",
            title: "third",
            amountCents: 300,
            payerId: "p",
            date: 0,
            shares: [],
            items: [],
            isSettlement: false,
            categoryId: null,
          },
        },
      ),
    ];
    const state = resolveAll(ops);
    expect(state.expenses.get("x")?.title).toBe("third");
    expect(state.expenses.get("x")?.amountCents).toBe(300);
  });

  it("suppresses entity when latest is a delete", () => {
    const ops: Operation[] = [
      op("1", 1, "d", {
        type: OP_TYPE.ParticipantUpsert,
        participant: { id: "p", eventId: "e", name: "Alice" },
      }),
      op("2", 2, "d", {
        type: OP_TYPE.ParticipantDelete,
        participantId: "p",
      }),
    ];
    expect(resolveAll(ops).participants.size).toBe(0);
  });

  it("revives a deleted entity when a later upsert wins", () => {
    const ops: Operation[] = [
      op("1", 1, "d", {
        type: OP_TYPE.ParticipantUpsert,
        participant: { id: "p", eventId: "e", name: "Alice" },
      }),
      op("2", 2, "d", {
        type: OP_TYPE.ParticipantDelete,
        participantId: "p",
      }),
      op("3", 3, "d", {
        type: OP_TYPE.ParticipantUpsert,
        participant: { id: "p", eventId: "e", name: "Alice2" },
      }),
    ];
    expect(resolveAll(ops).participants.get("p")?.name).toBe("Alice2");
  });

  it("ignores EventDelete tombstones", () => {
    const ops: Operation[] = [
      op("1", 1, "d", {
        type: OP_TYPE.EventUpsert,
        event: {
          id: "e",
          name: "Trip",
          description: null,
          currency: "EUR",
          createdAt: 0,
          archived: false,
          giftModeEnabled: true,
        },
      }),
      // Higher lamport, but EventDelete is local-only and must not
      // wipe the event.
      op("2", 99, "d", { type: OP_TYPE.EventDelete, eventId: "e" }),
    ];
    expect(resolveAll(ops).events.get("e")?.name).toBe("Trip");
  });

  it("resolveEntity returns null for tombstoned entities", () => {
    const ops: Operation[] = [
      op("1", 1, "d", {
        type: OP_TYPE.CategoryUpsert,
        category: {
          id: "c",
          eventId: "e",
          name: "Custom",
          emoji: "🦊",
          color: 0,
        },
      }),
      op("2", 2, "d", { type: OP_TYPE.CategoryDelete, categoryId: "c" }),
    ];
    expect(resolveEntity("CATEGORY", "c", ops)).toBeNull();
  });

  it("order-independent — shuffled input converges", () => {
    const ops: Operation[] = [];
    for (let i = 0; i < 50; i++) {
      ops.push(
        op(`o${i}`, i, "d", {
          type: OP_TYPE.ExpenseUpsert,
          expense: {
            id: "x",
            eventId: "evt",
            title: `t${i}`,
            amountCents: i,
            payerId: "p",
            date: 0,
            shares: [],
            items: [],
            isSettlement: false,
            categoryId: null,
          },
        }),
      );
    }
    const reference = resolveAll(ops).expenses.get("x")!;
    const shuffled = [...ops].sort(() => Math.random() - 0.5);
    expect(resolveAll(shuffled).expenses.get("x")).toEqual(reference);
  });
});
