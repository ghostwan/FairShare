import type { ExpenseShare } from "@/core/domain/models";

/**
 * Splits `total` equally between `includedIds` (the participants who
 * actually pay), then attaches zero-cent placeholder shares for each
 * `giftedIds` annotated with `coveredBy = includedIds`. The wire
 * format treats those gifted shares as a pure annotation: their
 * `amountCents` is 0, so `computeBalances` ignores them automatically,
 * but the UI can still surface "🎁 X (offert par Y, Z)" because the
 * coveredBy list survives the round trip.
 *
 * Cents are distributed with the standard "first N people pay an
 * extra cent" rule so the sum is exactly `total`. Order in
 * `includedIds` therefore matters for the remainder cents — callers
 * are expected to pass a stable list (typically participants sorted
 * by name).
 *
 * Invariants:
 * - `includedIds` and `giftedIds` are disjoint (caller-enforced).
 * - If `includedIds` is empty, returns only the gifted-zero shares
 *   with empty `coveredBy`. That's a degenerate case the UI should
 *   prevent (validation blocks "everyone is gifted").
 */
export function splitEqually(
  total: number,
  includedIds: string[],
  giftedIds: string[] = [],
): ExpenseShare[] {
  const out: ExpenseShare[] = [];
  if (includedIds.length > 0 && total > 0) {
    const base = Math.floor(total / includedIds.length);
    let remainder = total - base * includedIds.length;
    for (const id of includedIds) {
      let amount = base;
      if (remainder > 0) {
        amount += 1;
        remainder -= 1;
      }
      out.push({ participantId: id, amountCents: amount });
    }
  } else if (includedIds.length > 0) {
    // total === 0: still emit zero-cent shares so the participants
    // list is preserved (matches legacy behaviour of splitEquallyCents
    // with total=0).
    for (const id of includedIds) {
      out.push({ participantId: id, amountCents: 0 });
    }
  }
  if (giftedIds.length > 0) {
    const coveredBy = [...includedIds];
    for (const id of giftedIds) {
      out.push({
        participantId: id,
        amountCents: 0,
        coveredBy,
      });
    }
  }
  return out;
}
