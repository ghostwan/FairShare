import { beforeEach, describe, expect, it } from "vitest";
import { computeBalances, computeSettlements, totalsPaidBy } from "../domain/balances";
import type { Expense, Participant } from "../domain/models";

function p(id: string, name: string): Participant {
  return { id, eventId: "e", name };
}

function e(payerId: string, amount: number, shares: { id: string; amt: number }[], opts: Partial<Expense> = {}): Expense {
  return {
    id: crypto.randomUUID(),
    eventId: "e",
    title: "t",
    amountCents: amount,
    payerId,
    date: 0,
    shares: shares.map((s) => ({ participantId: s.id, amountCents: s.amt })),
    items: [],
    isSettlement: false,
    categoryId: null,
    ...opts,
  };
}

describe("balances", () => {
  let participants: Participant[];
  beforeEach(() => {
    participants = [p("a", "Alice"), p("b", "Bob"), p("c", "Carol")];
  });

  it("nets payments minus shares", () => {
    // Alice pays 30 for everyone (10 each).
    const expenses = [
      e("a", 3000, [
        { id: "a", amt: 1000 },
        { id: "b", amt: 1000 },
        { id: "c", amt: 1000 },
      ]),
    ];
    const balances = computeBalances(participants, expenses);
    expect(balances.find((b) => b.participantId === "a")?.netCents).toBe(2000);
    expect(balances.find((b) => b.participantId === "b")?.netCents).toBe(-1000);
    expect(balances.find((b) => b.participantId === "c")?.netCents).toBe(-1000);
  });

  it("greedy settlement is minimal-transaction", () => {
    // A is owed 20, B owes 10, C owes 10 → exactly 2 transfers.
    const balances = [
      { participantId: "a", participantName: "Alice", netCents: 2000 },
      { participantId: "b", participantName: "Bob", netCents: -1000 },
      { participantId: "c", participantName: "Carol", netCents: -1000 },
    ];
    const s = computeSettlements(balances);
    expect(s.length).toBe(2);
    expect(s.every((t) => t.toId === "a")).toBe(true);
    expect(s.reduce((acc, t) => acc + t.amountCents, 0)).toBe(2000);
  });

  it("totalsPaidBy excludes settlements", () => {
    const expenses = [
      e("a", 1000, [{ id: "a", amt: 1000 }]),
      e("a", 500, [{ id: "b", amt: 500 }], { isSettlement: true }),
    ];
    const t = totalsPaidBy(expenses);
    expect(t.get("a")).toBe(1000);
  });
});
