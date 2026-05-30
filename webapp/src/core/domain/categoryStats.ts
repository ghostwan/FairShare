import type { Category, Expense } from "./models";
import { DEFAULT_CATEGORIES_BY_ID } from "./defaultCategories";

/**
 * One row of the Stats screen.
 *
 * Mirrors the Android `CategoryStat` data class returned by
 * `ComputeCategoryStatsUseCase` so both clients render the same
 * shape (sorted bar list with per-category totals + share of the
 * grand total).
 *
 * `category` is `null` for the synthetic "Sans catégorie" bucket,
 * which also absorbs expenses whose `categoryId` points at a
 * deleted custom category.
 */
export interface CategoryStat {
  category: Category | null;
  totalCents: number;
  count: number;
  /** Share of the grand total, in [0, 1]. */
  fraction: number;
}

/**
 * Aggregate expenses by category. Settlements are excluded — they net
 * to zero across participants and would skew the picture (a 30€
 * reimbursement is not 30€ of "spending"). The grand total fed into
 * the fraction is therefore the sum of the kept rows only.
 */
export function computeCategoryStats(
  expenses: Expense[],
  customCategories: Category[],
): CategoryStat[] {
  const relevant = expenses.filter((e) => !e.isSettlement);
  if (relevant.length === 0) return [];

  const customById = new Map(customCategories.map((c) => [c.id, c]));
  const resolve = (id: string | null): Category | null => {
    if (id == null) return null;
    return DEFAULT_CATEGORIES_BY_ID.get(id) ?? customById.get(id) ?? null;
  };

  const grand = Math.max(
    1,
    relevant.reduce((s, e) => s + e.amountCents, 0),
  );

  const buckets = new Map<string, { category: Category | null; sum: number; count: number }>();
  for (const e of relevant) {
    const cat = resolve(e.categoryId);
    const key = cat?.id ?? "__uncategorized__";
    const bucket = buckets.get(key) ?? { category: cat, sum: 0, count: 0 };
    bucket.sum += e.amountCents;
    bucket.count += 1;
    buckets.set(key, bucket);
  }

  return [...buckets.values()]
    .map((b) => ({
      category: b.category,
      totalCents: b.sum,
      count: b.count,
      fraction: b.sum / grand,
    }))
    .sort((a, b) => b.totalCents - a.totalCents);
}
