import { describe, expect, it } from "vitest";
import { computeCategoryStats } from "../domain/categoryStats";
import type { Category, Expense } from "../domain/models";

function e(amount: number, categoryId: string | null, opts: Partial<Expense> = {}): Expense {
  return {
    id: crypto.randomUUID(),
    eventId: "e",
    title: "t",
    amountCents: amount,
    payerId: "a",
    date: 0,
    shares: [{ participantId: "a", amountCents: amount }],
    items: [],
    isSettlement: false,
    categoryId,
    ...opts,
  };
}

describe("computeCategoryStats", () => {
  it("returns an empty list when there are no relevant expenses", () => {
    expect(computeCategoryStats([], [])).toEqual([]);
    // Only settlements → still empty.
    expect(
      computeCategoryStats(
        [e(1000, "default.food", { isSettlement: true })],
        [],
      ),
    ).toEqual([]);
  });

  it("buckets by category, sorts desc, and computes fractions", () => {
    const stats = computeCategoryStats(
      [
        e(3000, "default.food"),
        e(1000, "default.food"),
        e(2000, "default.transport"),
      ],
      [],
    );
    expect(stats.map((s) => [s.category?.id, s.totalCents, s.count])).toEqual([
      ["default.food", 4000, 2],
      ["default.transport", 2000, 1],
    ]);
    expect(stats[0]!.fraction).toBeCloseTo(4000 / 6000);
    expect(stats[1]!.fraction).toBeCloseTo(2000 / 6000);
  });

  it("groups null and unknown category ids into the uncategorized bucket", () => {
    const stats = computeCategoryStats(
      [e(500, null), e(700, "deleted-custom-id")],
      [],
    );
    expect(stats.length).toBe(1);
    expect(stats[0]!.category).toBeNull();
    expect(stats[0]!.totalCents).toBe(1200);
    expect(stats[0]!.count).toBe(2);
  });

  it("resolves custom categories passed in", () => {
    const custom: Category = {
      id: "c1",
      eventId: "e",
      name: "Snacks",
      emoji: "🍿",
      color: 0xff123456,
      isDefault: false,
    };
    const stats = computeCategoryStats([e(900, "c1")], [custom]);
    expect(stats[0]!.category?.name).toBe("Snacks");
  });

  it("excludes settlements from the grand total", () => {
    const stats = computeCategoryStats(
      [
        e(1000, "default.food"),
        e(99999, "default.transport", { isSettlement: true }),
      ],
      [],
    );
    expect(stats.length).toBe(1);
    expect(stats[0]!.fraction).toBe(1);
  });
});
