/**
 * Wire-format snapshots. Field names and default values must match
 * `com.fairshare.domain.model.sync.Snapshots.kt` byte-for-byte because
 * they cross the encrypted boundary and are read by Android peers.
 *
 * These are deliberately distinct from the in-app domain models (see
 * `core/domain/models.ts`) so the on-wire shape stays stable even as
 * the UI-facing model evolves.
 *
 * `kotlinx.serialization` with `encodeDefaults=true` emits every field
 * including nulls and defaults, in declaration order. The TS codec in
 * `codec.ts` reproduces that order on serialize and tolerates any
 * order on parse (`ignoreUnknownKeys=true` is symmetric).
 */

export interface EventSnapshot {
  id: string;
  name: string;
  description: string | null;
  currency: string;
  createdAt: number;
  archived: boolean;
}

export interface ParticipantSnapshot {
  id: string;
  eventId: string;
  name: string;
}

export interface ExpenseShareSnapshot {
  id: string;
  participantId: string;
  amountCents: number;
}

export interface ExpenseItemSnapshot {
  id: string;
  label: string;
  priceCents: number;
  quantity: number;
  /** Participant ids — kotlinx serialises `Set<String>` as a JSON array. */
  assignedTo: string[];
}

export interface ExpenseSnapshot {
  id: string;
  eventId: string;
  title: string;
  amountCents: number;
  payerId: string;
  date: number;
  shares: ExpenseShareSnapshot[];
  items: ExpenseItemSnapshot[];
  isSettlement: boolean;
  categoryId: string | null;
}

export interface CategorySnapshot {
  id: string;
  eventId: string;
  name: string;
  emoji: string;
  color: number; // ARGB
}
