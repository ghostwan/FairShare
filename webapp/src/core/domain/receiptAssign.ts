import type { ExpenseItem, ExpenseShare } from "@/core/domain/models";

/**
 * Ports Android's `AssignReceiptItemsUseCase`. For each receipt item,
 * splits its price equally between its `assignedTo` participants (or
 * across `allParticipantIds` as a fallback when no-one is checked).
 * The cent remainder is distributed deterministically to the first
 * assignees so the per-person totals add up exactly to the receipt
 * total.
 */
export function assignReceiptItems(
  items: ExpenseItem[],
  allParticipantIds: string[],
): ExpenseShare[] {
  const totals = new Map<string, number>();
  for (const item of items) {
    const assignees =
      item.assignedTo && item.assignedTo.length > 0
        ? item.assignedTo
        : allParticipantIds;
    if (assignees.length === 0) continue;
    const base = Math.floor(item.priceCents / assignees.length);
    const remainder = item.priceCents - base * assignees.length;
    assignees.forEach((pid, i) => {
      const add = base + (i < remainder ? 1 : 0);
      totals.set(pid, (totals.get(pid) ?? 0) + add);
    });
  }
  return Array.from(totals.entries()).map(([participantId, amountCents]) => ({
    participantId,
    amountCents,
  }));
}
