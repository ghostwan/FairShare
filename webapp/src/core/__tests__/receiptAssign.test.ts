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

  test("gifted participant is stripped from explicit assignees", () => {
    // c is gifted: even if the per-item chip still lists them, they
    // don't pay. The remaining assignee (a) covers the full price.
    const shares = assignReceiptItems(
      [mkItem(900, ["a", "c"])],
      ["a", "b", "c"],
      ["c"],
    );
    const map = new Map(shares.map((s) => [s.participantId, s]));
    expect(map.get("a")?.amountCents).toBe(900);
    expect(map.get("b")).toBeUndefined();
    expect(map.get("c")?.amountCents).toBe(0);
    expect(map.get("c")?.coveredBy).toEqual(["a", "b"]);
  });

  test("unassigned items fall back to eligible participants only", () => {
    // No-one ticked: split across (all − gifted) = a, b.
    const shares = assignReceiptItems(
      [mkItem(1000, [])],
      ["a", "b", "c"],
      ["c"],
    );
    const map = new Map(shares.map((s) => [s.participantId, s.amountCents]));
    expect(map.get("a")).toBe(500);
    expect(map.get("b")).toBe(500);
    expect(map.get("c")).toBe(0);
  });

  test("emits one zero-cent gift placeholder per gifted with coveredBy = eligible", () => {
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
    // Nothing to split (assignees=[] after filter, eligibleAll=[]),
    // but the gift annotation still surfaces.
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
