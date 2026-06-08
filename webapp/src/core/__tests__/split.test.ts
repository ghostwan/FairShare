import { describe, expect, it } from "vitest";
import { splitEqually } from "../domain/split";
import { computeBalances } from "../domain/balances";
import type { Expense, Participant } from "../domain/models";

describe("splitEqually", () => {
  it("evenly divides total when no remainder", () => {
    expect(splitEqually(300, ["a", "b", "c"])).toEqual([
      { participantId: "a", amountCents: 100 },
      { participantId: "b", amountCents: 100 },
      { participantId: "c", amountCents: 100 },
    ]);
  });

  it("distributes remainder cents to the first ids", () => {
    expect(splitEqually(301, ["a", "b", "c"])).toEqual([
      { participantId: "a", amountCents: 101 },
      { participantId: "b", amountCents: 100 },
      { participantId: "c", amountCents: 100 },
    ]);
  });

  it("returns empty for empty included and empty gifted", () => {
    expect(splitEqually(300, [])).toEqual([]);
  });

  it("emits zero-cent shares when total is 0", () => {
    expect(splitEqually(0, ["a", "b"])).toEqual([
      { participantId: "a", amountCents: 0 },
      { participantId: "b", amountCents: 0 },
    ]);
  });

  it("annotates gifted ids with coveredBy", () => {
    const shares = splitEqually(300, ["a", "b"], ["c"]);
    expect(shares).toEqual([
      { participantId: "a", amountCents: 150 },
      { participantId: "b", amountCents: 150 },
      { participantId: "c", amountCents: 0, coveredBy: ["a", "b"] },
    ]);
  });

  it("supports multiple gifted participants sharing the same coveredBy", () => {
    const shares = splitEqually(400, ["a", "b"], ["c", "d"]);
    expect(shares).toEqual([
      { participantId: "a", amountCents: 200 },
      { participantId: "b", amountCents: 200 },
      { participantId: "c", amountCents: 0, coveredBy: ["a", "b"] },
      { participantId: "d", amountCents: 0, coveredBy: ["a", "b"] },
    ]);
  });

  it("keeps the gift-zero shares neutral in computeBalances", () => {
    // Alice and Bob each pay half for a 30€ gift to Carol; Carol
    // owes nothing, Alice and Bob each owe 15 to themselves => the
    // payer ends up creditor for the part they didn't cover.
    const participants: Participant[] = [
      { id: "a", eventId: "e", name: "Alice" },
      { id: "b", eventId: "e", name: "Bob" },
      { id: "c", eventId: "e", name: "Carol" },
    ];
    const shares = splitEqually(3000, ["a", "b"], ["c"]);
    const exp: Expense = {
      id: "x",
      eventId: "e",
      title: "Gâteau anniv Carol",
      amountCents: 3000,
      payerId: "a",
      date: 0,
      shares,
      items: [],
      isSettlement: false,
      categoryId: null,
    };
    const balances = computeBalances(participants, [exp]);
    // Alice paid 30, owes herself 15 => net +15.
    expect(balances.find((b) => b.participantId === "a")?.netCents).toBe(1500);
    // Bob paid 0, owes 15 => net -15.
    expect(balances.find((b) => b.participantId === "b")?.netCents).toBe(-1500);
    // Carol paid 0, owes 0 (gifted) => net 0.
    expect(balances.find((b) => b.participantId === "c")?.netCents).toBe(0);
  });
});
