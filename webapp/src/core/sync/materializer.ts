import { compareLww, type Operation } from "./operation";
import {
  OP_TYPE,
  type EntityKind,
  type OpPayload,
  entityIdOf,
  entityKindOf,
} from "./opPayload";
import type {
  CategorySnapshot,
  EventSnapshot,
  ExpenseSnapshot,
  ParticipantSnapshot,
} from "./snapshots";

/**
 * Pure CRDT materialisation. Mirrors
 * `com.fairshare.domain.model.sync.MaterializerLogic` one-for-one:
 *
 *   1. Drop `EventDelete` tombstones before grouping (deleting an event
 *      is a local-only action; a remote `EventDelete` in the log can
 *      only come from older builds and must not wipe state).
 *   2. Group the remaining ops by `(entityKind, entityId)`.
 *   3. Pick the op with the highest `(lamport, deviceId)` pair.
 *   4. Apply Upsert / suppress on Delete.
 *
 * Deterministic and order-independent: two devices fed the same set
 * of ops produce the same `MaterializedState` — that's the convergence
 * property the whole sync stack relies on.
 */

export interface MaterializedState {
  events: Map<string, EventSnapshot>;
  participants: Map<string, ParticipantSnapshot>;
  expenses: Map<string, ExpenseSnapshot>;
  categories: Map<string, CategorySnapshot>;
}

export function resolveAll(ops: Iterable<Operation>): MaterializedState {
  const events = new Map<string, EventSnapshot>();
  const participants = new Map<string, ParticipantSnapshot>();
  const expenses = new Map<string, ExpenseSnapshot>();
  const categories = new Map<string, CategorySnapshot>();

  const groups = new Map<string, Operation[]>();
  for (const op of ops) {
    if (op.payload.type === OP_TYPE.EventDelete) continue;
    const key = `${entityKindOf(op.payload)}|${entityIdOf(op.payload)}`;
    const bucket = groups.get(key);
    if (bucket) bucket.push(op);
    else groups.set(key, [op]);
  }

  for (const [, group] of groups) {
    let winner = group[0]!;
    for (let i = 1; i < group.length; i++) {
      if (compareLww(group[i]!, winner) > 0) winner = group[i]!;
    }
    applyWinner(winner.payload, events, participants, expenses, categories);
  }

  return { events, participants, expenses, categories };
}

function applyWinner(
  p: OpPayload,
  events: Map<string, EventSnapshot>,
  participants: Map<string, ParticipantSnapshot>,
  expenses: Map<string, ExpenseSnapshot>,
  categories: Map<string, CategorySnapshot>,
): void {
  switch (p.type) {
    case OP_TYPE.EventUpsert:
      events.set(p.event.id, p.event);
      break;
    case OP_TYPE.ParticipantUpsert:
      participants.set(p.participant.id, p.participant);
      break;
    case OP_TYPE.ExpenseUpsert:
      expenses.set(p.expense.id, p.expense);
      break;
    case OP_TYPE.CategoryUpsert:
      categories.set(p.category.id, p.category);
      break;
    // Deletes / EventDelete: nothing to write (tombstones already
    // suppress the entity by virtue of being the winner).
    case OP_TYPE.ParticipantDelete:
    case OP_TYPE.ExpenseDelete:
    case OP_TYPE.CategoryDelete:
    case OP_TYPE.EventDelete:
      break;
  }
}

/**
 * Single-entity variant. Returns the winning payload, or `null` if the
 * entity has no ops or its current winner is a tombstone. Used by the
 * persistent materialiser to update only the entities touched by an
 * incoming batch.
 */
export function resolveEntity(
  kind: EntityKind,
  entityId: string,
  ops: Iterable<Operation>,
): OpPayload | null {
  let winner: Operation | null = null;
  for (const op of ops) {
    if (op.payload.type === OP_TYPE.EventDelete) continue;
    if (entityKindOf(op.payload) !== kind) continue;
    if (entityIdOf(op.payload) !== entityId) continue;
    if (winner == null || compareLww(op, winner) > 0) winner = op;
  }
  if (winner == null) return null;
  switch (winner.payload.type) {
    case OP_TYPE.ParticipantDelete:
    case OP_TYPE.CategoryDelete:
    case OP_TYPE.ExpenseDelete:
      return null;
    default:
      return winner.payload;
  }
}
