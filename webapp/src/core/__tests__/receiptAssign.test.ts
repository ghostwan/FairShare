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
});
