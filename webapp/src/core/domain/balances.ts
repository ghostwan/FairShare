import type { Category, Expense, Participant } from "@/core/domain/models";

/**
 * Per-participant signed balance and the minimal greedy settlement plan.
 * Ported from `ComputeBalancesUseCase` on Android — pure function, no
 * IO. Money is in integer cents throughout.
 */

export interface Balance {
  participantId: string;
  participantName: string;
  netCents: number; // positive = creditor, negative = debtor
}

export interface Settlement {
  fromId: string;
  fromName: string;
  toId: string;
  toName: string;
  amountCents: number;
}

export function computeBalances(
  participants: Participant[],
  expenses: Expense[],
): Balance[] {
  const net = new Map<string, number>();
  const add = (id: string, delta: number): void => {
    net.set(id, (net.get(id) ?? 0) + delta);
  };
  for (const e of expenses) {
    add(e.payerId, e.amountCents);
    for (const s of e.shares) {
      add(s.participantId, -s.amountCents);
    }
  }
  return participants.map((p) => ({
    participantId: p.id,
    participantName: p.name,
    netCents: net.get(p.id) ?? 0,
  }));
}

/**
 * Sum of amounts a participant paid as the expense payer, keyed by
 * participant id. Settlements are excluded — they're internal
 * transfers, not expenses, and would otherwise inflate the "total
 * spent" displayed to the user.
 */
export function totalsPaidBy(expenses: Expense[]): Map<string, number> {
  const totals = new Map<string, number>();
  for (const e of expenses) {
    if (e.isSettlement) continue;
    totals.set(e.payerId, (totals.get(e.payerId) ?? 0) + e.amountCents);
  }
  return totals;
}

/** Greedy minimal-transactions settlement. */
export function computeSettlements(balances: Balance[]): Settlement[] {
  const work = balances.map((b) => ({ ...b }));
  const out: Settlement[] = [];
  while (true) {
    let creditor = work[0];
    let debtor = work[0];
    for (const b of work) {
      if (creditor == null || b.netCents > creditor.netCents) creditor = b;
      if (debtor == null || b.netCents < debtor.netCents) debtor = b;
    }
    if (creditor == null || debtor == null) break;
    if (creditor.netCents <= 0 || debtor.netCents >= 0) break;
    const transfer = Math.min(creditor.netCents, -debtor.netCents);
    if (transfer <= 0) break;
    out.push({
      fromId: debtor.participantId,
      fromName: debtor.participantName,
      toId: creditor.participantId,
      toName: creditor.participantName,
      amountCents: transfer,
    });
    creditor.netCents -= transfer;
    debtor.netCents += transfer;
  }
  return out;
}

/** Sum of all non-settlement expenses in an event. */
export function totalSpent(expenses: Expense[]): number {
  let sum = 0;
  for (const e of expenses) {
    if (!e.isSettlement) sum += e.amountCents;
  }
  return sum;
}

/** Look up a category by id, falling back to defaults. */
export function findCategory(
  id: string | null | undefined,
  custom: Category[],
  defaults: Category[],
): Category | undefined {
  if (id == null) return undefined;
  return custom.find((c) => c.id === id) ?? defaults.find((c) => c.id === id);
}
