import type { ExpenseItem, ExpenseShare } from "@/core/domain/models";

/**
 * Ports Android's `AssignReceiptItemsUseCase` and grafts gift mode on
 * top. For each receipt item, splits its price equally between its
 * `assignedTo` participants (or across `allParticipantIds` minus the
 * gifted ones as a fallback when no-one is checked). The cent
 * remainder is distributed deterministically to the first assignees
 * so the per-person totals add up exactly to the receipt total.
 *
 * Gifted semantics mirror `splitEqually`:
 * - gifted participants are stripped from every `assignedTo` list
 *   before the split (so they never pay, even if the UI forgot to
 *   uncheck them on a per-item chip).
 * - one zero-cent placeholder share is appended per gifted, with
 *   `coveredBy = allParticipantIds − giftedIds`, so the gift survives
 *   the snapshot round-trip and the UI can render "🎁 X (offert par
 *   Y, Z)".
 *
 * `giftedIds` and the effective payer set are assumed disjoint (the
 * caller-side toggle enforces it: a chip is either gifted or
 * assignable).
 */
export function assignReceiptItems(
  items: ExpenseItem[],
  allParticipantIds: string[],
  giftedIds: string[] = [],
): ExpenseShare[] {
  const giftedSet = new Set(giftedIds);
  const eligibleAll = allParticipantIds.filter((id) => !giftedSet.has(id));
  const totals = new Map<string, number>();
  for (const item of items) {
    const explicit =
      item.assignedTo && item.assignedTo.length > 0
        ? item.assignedTo.filter((id) => !giftedSet.has(id))
        : [];
    const assignees = explicit.length > 0 ? explicit : eligibleAll;
    if (assignees.length === 0) continue;
    const base = Math.floor(item.priceCents / assignees.length);
    const remainder = item.priceCents - base * assignees.length;
    assignees.forEach((pid, i) => {
      const add = base + (i < remainder ? 1 : 0);
      totals.set(pid, (totals.get(pid) ?? 0) + add);
    });
  }
  const shares: ExpenseShare[] = Array.from(totals.entries()).map(
    ([participantId, amountCents]) => ({
      participantId,
      amountCents,
    }),
  );
  if (giftedIds.length > 0) {
    const coveredBy = [...eligibleAll];
    for (const id of giftedIds) {
      shares.push({
        participantId: id,
        amountCents: 0,
        coveredBy,
      });
    }
  }
  return shares;
}
