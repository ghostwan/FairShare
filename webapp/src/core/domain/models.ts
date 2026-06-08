/**
 * Pure-logic domain models, decoupled from MUI / Dexie. These mirror
 * the Kotlin types in `app/src/main/java/com/fairshare/domain/model/`
 * one-for-one so that the shared mental model from the Android app
 * carries over.
 *
 * Money is stored as integer cents (`number` — safe up to 2^53,
 * well above any conceivable expense in cents). `Long` Kotlin fields
 * become `number` here; the sync layer keeps them as numbers on the
 * wire too because we never approach the 53-bit threshold (lamport
 * clocks tick once per local op, and amounts are bounded by sanity).
 */

export interface Event {
  id: string;
  name: string;
  description: string | null;
  currency: string; // ISO 4217, defaults to "EUR"
  createdAt: number; // epoch millis
  archived: boolean;
}

export interface Participant {
  id: string;
  eventId: string;
  name: string;
}

export interface ExpenseShare {
  participantId: string;
  amountCents: number;
  /**
   * When non-empty, this share's amount has been "offered" by the
   * listed participant ids — typical use case: someone's part is
   * covered by the rest of the group for a birthday. `amountCents`
   * is expected to be 0 in that case; the covering participants
   * carry the redistributed amount on their own shares.
   *
   * Wire-format note: optional, Kotlin peers tolerate the unknown
   * field thanks to `ignoreUnknownKeys = true`. Re-emitting an
   * expense from an Android peer will drop the gift annotation
   * until Android's `ExpenseShareSnapshot` is extended too.
   */
  coveredBy?: string[];
}

export interface ExpenseItem {
  id: string;
  label: string;
  priceCents: number;
  quantity: number;
  assignedTo: string[]; // participant ids
}

export interface Expense {
  id: string;
  eventId: string;
  title: string;
  amountCents: number;
  payerId: string;
  date: number; // epoch millis
  shares: ExpenseShare[];
  items: ExpenseItem[];
  isSettlement: boolean;
  categoryId: string | null;
}

export interface Category {
  id: string;
  eventId: string; // "" for default categories
  name: string;
  emoji: string;
  color: number; // ARGB
  isDefault: boolean;
}

export type SplitMode = "EQUAL" | "SHARES" | "EXACT";
