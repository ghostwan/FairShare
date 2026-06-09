import type { ExpenseItem, ExpenseShare } from "@/core/domain/models";

/**
 * Ports Android's `AssignReceiptItemsUseCase` and grafts gift mode on
 * top. For each receipt item, splits its price equally between its
 * `assignedTo` participants (or across `allParticipantIds` as a
 * fallback when no-one is checked) — gifted included, on purpose. The
 * cent remainder is distributed deterministically to the first
 * assignees so the per-person totals add up exactly to the receipt
 * total.
 *
 * Gift semantics — the rule the owner wants:
 * - whatever the gifted *would have paid* (their raw per-item share,
 *   based on who ticked what) is collected into a single bucket;
 * - that bucket is then redistributed equally across every eligible
 *   participant — NOT just the co-tickers of each individual item.
 *   So if A is gifted and only A+B ticked an item, A's share of that
 *   item gets spread across all eligibles (B, C, D, …), not dumped
 *   on B alone.
 * - gifted participants are emitted as zero-cent placeholder shares
 *   whose `coveredBy = eligibleAll` so the gift annotation survives
 *   the snapshot round-trip and the UI can render "🎁 X (offert par
 *   B, C, D)".
 *
 * Why ticking a gifted matters: their `assignedTo` membership still
 * decides how much of the receipt is "imputable" to them, and that
 * total is what gets redistributed. Unticking a gifted from an item
 * means that item's cost stays purely on its (eligible) assignees.
 *
 * `giftedIds` and the effective payer set are assumed disjoint (the
 * caller-side global picker enforces it: a participant is either
 * gifted or not, irrespective of what they ticked).
 */
export function assignReceiptItems(
  items: ExpenseItem[],
  allParticipantIds: string[],
  giftedIds: string[] = [],
): ExpenseShare[] {
  const giftedSet = new Set(giftedIds);
  const eligibleAll = allParticipantIds.filter((id) => !giftedSet.has(id));

  // Step 1: per-item raw split. Gifted participants are split in here
  // just like any other assignee — their share gets collected and
  // redistributed in step 3.
  const raw = new Map<string, number>();
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
      raw.set(pid, (raw.get(pid) ?? 0) + add);
    });
  }

  // Step 2: gather what the gifted would have paid.
  let giftedTotal = 0;
  for (const id of giftedIds) {
    giftedTotal += raw.get(id) ?? 0;
  }

  // Step 3: start every eligible at their raw share, then redistribute
  // the gifted bucket equally across them. Cent remainder follows the
  // same first-N-pay-extra rule as `splitEqually` for determinism.
  const totals = new Map<string, number>();
  for (const id of eligibleAll) {
    totals.set(id, raw.get(id) ?? 0);
  }
  if (eligibleAll.length > 0 && giftedTotal > 0) {
    const base = Math.floor(giftedTotal / eligibleAll.length);
    let remainder = giftedTotal - base * eligibleAll.length;
    for (const id of eligibleAll) {
      let add = base;
      if (remainder > 0) {
        add += 1;
        remainder -= 1;
      }
      totals.set(id, (totals.get(id) ?? 0) + add);
    }
  }

  // Step 4: emit paying shares (positive amounts only) in eligibleAll
  // order so the output is deterministic.
  const shares: ExpenseShare[] = [];
  for (const id of eligibleAll) {
    const amount = totals.get(id) ?? 0;
    if (amount > 0) shares.push({ participantId: id, amountCents: amount });
  }

  // Step 5: emit one zero-cent gift placeholder per gifted, annotated
  // with coveredBy = all eligibles (the global "absorbers", regardless
  // of which item they ticked).
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
