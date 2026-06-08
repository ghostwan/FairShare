import { beforeEach, describe, expect, it } from "vitest";
import { __setDbForTest, getDb } from "@/data/db";
import {
  addParticipant,
  createEvent,
  listEvents,
  listExpenses,
  listParticipants,
  setEventGiftModeEnabled,
  upsertExpense,
} from "@/data/repositories";

beforeEach(async () => {
  // Each test gets a fresh DB instance so coordinator state doesn't
  // leak (op log, identity, secrets).
  const name = `fairshare-test-${crypto.randomUUID()}`;
  await __setDbForTest(name).open();
});

describe("repositories — basic CRUD through the sync coordinator", () => {
  it("create event + add participants + add expense round-trip", async () => {
    const evt = await createEvent("Berlin", "Trip with friends");
    expect(evt.name).toBe("Berlin");
    expect(evt.description).toBe("Trip with friends");
    // Default to true so freshly-created events ship with the gift
    // chip enabled — opt-out only.
    expect(evt.giftModeEnabled).toBe(true);

    const alice = await addParticipant(evt.id, "Alice");
    const bob = await addParticipant(evt.id, "Bob");
    const ps = await listParticipants(evt.id);
    expect(ps.map((p) => p.name).sort()).toEqual(["Alice", "Bob"]);

    const expense = await upsertExpense({
      id: "",
      eventId: evt.id,
      title: "Pizza",
      amountCents: 2000,
      payerId: alice.id,
      date: Date.now(),
      shares: [
        { participantId: alice.id, amountCents: 1000 },
        { participantId: bob.id, amountCents: 1000 },
      ],
      items: [],
      isSettlement: false,
      categoryId: "default.restaurant",
    });
    expect(expense.amountCents).toBe(2000);
    expect(expense.shares.length).toBe(2);
    expect(expense.categoryId).toBe("default.restaurant");

    const xs = await listExpenses(evt.id);
    expect(xs.length).toBe(1);
    expect(xs[0]!.id).toBe(expense.id);
  });

  it("op log is appended for each mutation", async () => {
    const evt = await createEvent("Tokyo");
    await addParticipant(evt.id, "Alice");
    const ops = await getDb().opLog.where("eventId").equals(evt.id).toArray();
    // 1 EventUpsert + 1 ParticipantUpsert
    expect(ops.length).toBe(2);
    for (const op of ops) {
      expect(op.pendingPush).toBe(1);
      expect(op.deviceId.length).toBeGreaterThan(0);
    }
  });

  it("listEvents returns most recent first", async () => {
    const a = await createEvent("A");
    await new Promise((r) => setTimeout(r, 5));
    const b = await createEvent("B");
    const list = await listEvents();
    expect(list[0]!.id).toBe(b.id);
    expect(list[1]!.id).toBe(a.id);
  });

  it("createEvent honours an explicit giftModeEnabled = false", async () => {
    const evt = await createEvent("Loyer", undefined, false);
    expect(evt.giftModeEnabled).toBe(false);
  });

  it("setEventGiftModeEnabled toggles + emits a new op", async () => {
    const evt = await createEvent("Brunch");
    expect(evt.giftModeEnabled).toBe(true);
    const opsBefore = await getDb().opLog
      .where("eventId")
      .equals(evt.id)
      .count();

    await setEventGiftModeEnabled(evt.id, false);
    const updated = await getDb().events.get(evt.id);
    expect(updated!.giftModeEnabled).toBe(false);

    const opsAfter = await getDb().opLog
      .where("eventId")
      .equals(evt.id)
      .count();
    // One additional EventUpsert op was appended.
    expect(opsAfter).toBe(opsBefore + 1);

    // No-op when the state already matches: must not append.
    await setEventGiftModeEnabled(evt.id, false);
    const opsAgain = await getDb().opLog
      .where("eventId")
      .equals(evt.id)
      .count();
    expect(opsAgain).toBe(opsAfter);
  });
});
