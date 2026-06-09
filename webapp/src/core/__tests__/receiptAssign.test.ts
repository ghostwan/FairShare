import { describe, expect, test } from "vitest";
import { assignReceiptItems } from "@/core/domain/receiptAssign";
import type { ExpenseItem } from "@/core/domain/models";

const mkItem = (
  priceCents: number,
  assignedTo: string[],
  label = "x",
): ExpenseItem => ({
  id: crypto.randomUUID(),
  label,
  priceCents,
  quantity: 1,
  assignedTo,
});

describe("assignReceiptItems", () => {
  test("splits an item between its assignees", () => {
    const shares = assignReceiptItems([mkItem(1000, ["a", "b"])], ["a", "b", "c"]);
    expect(shares).toEqual([
      { participantId: "a", amountCents: 500 },
      { participantId: "b", amountCents: 500 },
    ]);
  });

  test("falls back to all participants when an item is unassigned", () => {
    const shares = assignReceiptItems([mkItem(900, [])], ["a", "b", "c"]);
    expect(shares).toEqual([
      { participantId: "a", amountCents: 300 },
      { participantId: "b", amountCents: 300 },
      { participantId: "c", amountCents: 300 },
    ]);
  });

  test("distributes the cent remainder deterministically to first assignees", () => {
    // 10 cents split between 3 people -> 4, 3, 3
    const shares = assignReceiptItems([mkItem(10, ["a", "b", "c"])], ["a", "b", "c"]);
    expect(shares).toEqual([
      { participantId: "a", amountCents: 4 },
      { participantId: "b", amountCents: 3 },
      { participantId: "c", amountCents: 3 },
    ]);
  });

  test("aggregates across multiple items", () => {
    const shares = assignReceiptItems(
      [mkItem(1200, ["a"]), mkItem(800, ["a", "b"])],
      ["a", "b"],
    );
    // a: 1200 + 400 = 1600 ; b: 400
    expect(new Map(shares.map((s) => [s.participantId, s.amountCents]))).toEqual(
      new Map([
        ["a", 1600],
        ["b", 400],
      ]),
    );
  });

  test("sum of shares equals sum of item prices", () => {
    const items = [mkItem(333, ["a", "b", "c"]), mkItem(777, ["a"])];
    const shares = assignReceiptItems(items, ["a", "b", "c"]);
    const total = shares.reduce((s, x) => s + x.amountCents, 0);
    expect(total).toBe(333 + 777);
  });

  test("gifted share is redistributed across all eligibles, not just co-tickers", () => {
    // The reference example: A,B,C participants, A is gifted.
    // Article 1 = 60c ticked by A,B  -> raw a=30, b=30
    // Article 2 = 30c ticked by B,C  -> raw b=15, c=15
    // Combined raw: a=30, b=45, c=15.
    // A's 30c is redistributed equally across [b,c] (+15 each):
    // final b=60, c=30. (Not b=75, c=15 which would be "only co-tickers
    // of article 1 absorb A's share".)
    const shares = assignReceiptItems(
      [mkItem(60, ["a", "b"]), mkItem(30, ["b", "c"])],
      ["a", "b", "c"],
      ["a"],
    );
    const map = new Map(shares.map((s) => [s.participantId, s]));
    expect(map.get("b")?.amountCents).toBe(60);
    expect(map.get("c")?.amountCents).toBe(30);
    expect(map.get("a")?.amountCents).toBe(0);
    expect(map.get("a")?.coveredBy).toEqual(["b", "c"]);
  });

  test("unticking a gifted means their non-membership is honoured", () => {
    // A gifted but A didn't tick the item. Nothing to redistribute,
    // B and C just split it normally.
    const shares = assignReceiptItems(
      [mkItem(60, ["b", "c"])],
      ["a", "b", "c"],
      ["a"],
    );
    const map = new Map(shares.map((s) => [s.participantId, s]));
    expect(map.get("b")?.amountCents).toBe(30);
    expect(map.get("c")?.amountCents).toBe(30);
    expect(map.get("a")?.amountCents).toBe(0);
    expect(map.get("a")?.coveredBy).toEqual(["b", "c"]);
  });

  test("unassigned items raw-split across all then redistribute", () => {
    // 1000c on [a,b,c] raw -> a=334, b=333, c=333.
    // c gifted, 333c redistributed on [a,b] -> +167, +166.
    // final a=501, b=499.
    const shares = assignReceiptItems(
      [mkItem(1000, [])],
      ["a", "b", "c"],
      ["c"],
    );
    const map = new Map(shares.map((s) => [s.participantId, s.amountCents]));
    expect(map.get("a")).toBe(501);
    expect(map.get("b")).toBe(499);
    expect(map.get("c")).toBe(0);
  });

  test("emits one zero-cent gift placeholder per gifted with coveredBy = eligibleAll", () => {
    const shares = assignReceiptItems(
      [mkItem(600, ["a", "b"])],
      ["a", "b", "c", "d"],
      ["c", "d"],
    );
    const gifts = shares.filter((s) => s.amountCents === 0);
    expect(gifts.map((g) => g.participantId).sort()).toEqual(["c", "d"]);
    for (const g of gifts) {
      expect(g.coveredBy).toEqual(["a", "b"]);
    }
  });

  test("sum of paying shares equals receipt total when some are gifted", () => {
    const items = [mkItem(333, ["a", "b", "c"]), mkItem(777, ["a"])];
    const shares = assignReceiptItems(items, ["a", "b", "c"], ["c"]);
    const paying = shares
      .filter((s) => s.amountCents > 0)
      .reduce((s, x) => s + x.amountCents, 0);
    expect(paying).toBe(333 + 777);
  });

  test("only-gifted item with empty eligibleAll produces no paying share but keeps gift placeholder", () => {
    // Degenerate: a single participant exists and they're gifted.
    // Nothing to redistribute (eligibleAll=[]), but the gift
    // annotation still surfaces.
    const shares = assignReceiptItems(
      [mkItem(500, ["a"])],
      ["a"],
      ["a"],
    );
    expect(shares).toEqual([
      { participantId: "a", amountCents: 0, coveredBy: [] },
    ]);
  });
});
