import type {
  Category,
  Event,
  Expense,
  Participant,
} from "@/core/domain/models";
import { randomAesKey } from "@/core/crypto/aesgcm";
import { OP_TYPE } from "@/core/sync/opPayload";
import { emit, rememberEventSecret } from "@/sync/coordinator";
import { getDb } from "./db";
import { DEFAULT_CATEGORIES } from "@/core/domain/defaultCategories";

/**
 * Repository layer — thin wrappers that turn UI intents (create a new
 * event, add a participant, edit an expense) into op-log emits + DB
 * reads. Direct DB reads bypass the coordinator because the projection
 * tables are kept in sync by the coordinator's `rematerialise` call.
 *
 * Dexie's reactive `useLiveQuery` hook is used at the React layer so
 * UI components subscribe directly to these queries and re-render on
 * any change — including changes pushed in by the pull loop.
 */

// ----------------------- Events -----------------------

export async function createEvent(name: string, description?: string): Promise<Event> {
  const id = crypto.randomUUID();
  const eventKey = randomAesKey();
  // The event secret must exist before the first emit, because emit
  // needs it to encrypt the op for the outbox.
  await rememberEventSecret(id, eventKey);
  await emit(id, {
    type: OP_TYPE.EventUpsert,
    event: {
      id,
      name,
      description: description ?? null,
      currency: "EUR",
      createdAt: Date.now(),
      archived: false,
    },
  });
  const e = await getDb().events.get(id);
  if (!e) throw new Error("createEvent: projection missing post-emit");
  return e;
}

export async function updateEvent(event: Event): Promise<void> {
  await emit(event.id, {
    type: OP_TYPE.EventUpsert,
    event: {
      id: event.id,
      name: event.name,
      description: event.description,
      currency: event.currency,
      createdAt: event.createdAt,
      archived: event.archived,
    },
  });
}

/**
 * Toggle the archive flag and propagate it through the op log (LWW),
 * so other paired devices see the same archived state once they pull.
 * No-op if the requested state already matches.
 */
export async function setEventArchived(
  eventId: string,
  archived: boolean,
): Promise<void> {
  const current = await getDb().events.get(eventId);
  if (!current) return;
  if (current.archived === archived) return;
  await updateEvent({ ...current, archived });
}

/**
 * Local-only delete: wipes the materialised rows + op log + cursor +
 * secret on this device, without emitting an EventDelete op. Same
 * rationale as the Android `EventRepositoryImpl.delete`: emitting a
 * tombstone would mean re-importing the event from a peer would
 * silently lose to the local delete via LWW. A peer can still re-share
 * the event afterwards and the bootstrap pulls everything back.
 */
export async function deleteEventLocally(eventId: string): Promise<void> {
  const db = getDb();
  await db.transaction(
    "rw",
    [
      db.events,
      db.participants,
      db.expenses,
      db.categories,
      db.opLog,
      db.opCursor,
      db.eventSecrets,
    ],
    async () => {
      await db.participants.where("eventId").equals(eventId).delete();
      await db.expenses.where("eventId").equals(eventId).delete();
      await db.categories.where("eventId").equals(eventId).delete();
      await db.opLog.where("eventId").equals(eventId).delete();
      await db.opCursor.where("eventId").equals(eventId).delete();
      await db.eventSecrets.where("eventId").equals(eventId).delete();
      await db.events.delete(eventId);
    },
  );
}

export async function listEvents(): Promise<Event[]> {
  return getDb().events.orderBy("createdAt").reverse().toArray();
}

export async function getEvent(eventId: string): Promise<Event | undefined> {
  return getDb().events.get(eventId);
}

// ----------------------- Participants -----------------------

export async function addParticipant(
  eventId: string,
  name: string,
): Promise<Participant> {
  const id = crypto.randomUUID();
  await emit(eventId, {
    type: OP_TYPE.ParticipantUpsert,
    participant: { id, eventId, name },
  });
  const p = await getDb().participants.get(id);
  if (!p) throw new Error("addParticipant: projection missing post-emit");
  return p;
}

export async function renameParticipant(
  participant: Participant,
  newName: string,
): Promise<void> {
  await emit(participant.eventId, {
    type: OP_TYPE.ParticipantUpsert,
    participant: { ...participant, name: newName },
  });
}

export async function deleteParticipant(participant: Participant): Promise<void> {
  await emit(participant.eventId, {
    type: OP_TYPE.ParticipantDelete,
    participantId: participant.id,
  });
}

export async function listParticipants(eventId: string): Promise<Participant[]> {
  return getDb().participants.where("eventId").equals(eventId).sortBy("name");
}

// ----------------------- Expenses -----------------------

export async function upsertExpense(expense: Expense): Promise<Expense> {
  const id = expense.id || crypto.randomUUID();
  await emit(expense.eventId, {
    type: OP_TYPE.ExpenseUpsert,
    expense: {
      id,
      eventId: expense.eventId,
      title: expense.title,
      amountCents: expense.amountCents,
      payerId: expense.payerId,
      date: expense.date,
      shares: expense.shares.map((s, i) => ({
        id: `${id}-share-${i}`,
        participantId: s.participantId,
        amountCents: s.amountCents,
      })),
      items: expense.items.map((it, i) => ({
        id: it.id || `${id}-item-${i}`,
        label: it.label,
        priceCents: it.priceCents,
        quantity: it.quantity,
        assignedTo: it.assignedTo,
      })),
      isSettlement: expense.isSettlement,
      categoryId: expense.categoryId,
    },
  });
  const saved = await getDb().expenses.get(id);
  if (!saved) throw new Error("upsertExpense: projection missing post-emit");
  return saved;
}

export async function deleteExpense(expense: Expense): Promise<void> {
  await emit(expense.eventId, {
    type: OP_TYPE.ExpenseDelete,
    expenseId: expense.id,
  });
}

/**
 * Materialises a suggested settlement as a real expense tagged
 * `isSettlement = true`. Mirrors Android's
 * EventDetailViewModel.recordSettlement: payerId is the debtor, the
 * sole share goes to the creditor, so once it lands the balance
 * between them collapses to zero. Tagged so the balances + category
 * stats use cases filter it out — settlements aren't shared costs.
 */
export async function recordSettlement(
  eventId: string,
  fromId: string,
  fromName: string,
  toId: string,
  toName: string,
  amountCents: number,
): Promise<Expense> {
  return upsertExpense({
    id: "",
    eventId,
    title: `Remboursement ${fromName} → ${toName}`,
    amountCents,
    payerId: fromId,
    date: Date.now(),
    shares: [{ participantId: toId, amountCents }],
    items: [],
    isSettlement: true,
    categoryId: null,
  });
}

export async function listExpenses(eventId: string): Promise<Expense[]> {
  const xs = await getDb().expenses.where("eventId").equals(eventId).toArray();
  // Most recent first — matches Android timeline order.
  return xs.sort((a, b) => b.date - a.date);
}

// ----------------------- Categories -----------------------

/**
 * Returns the merged catalogue of default + custom categories for the
 * given event. Defaults are always first so the picker is consistent
 * across events.
 */
export async function listCategories(eventId: string): Promise<Category[]> {
  const custom = await getDb()
    .categories.where("eventId")
    .equals(eventId)
    .sortBy("name");
  return [...DEFAULT_CATEGORIES, ...custom];
}

export async function upsertCustomCategory(
  eventId: string,
  category: Omit<Category, "id" | "eventId" | "isDefault"> & {
    id?: string;
  },
): Promise<Category> {
  const id = category.id || crypto.randomUUID();
  await emit(eventId, {
    type: OP_TYPE.CategoryUpsert,
    category: {
      id,
      eventId,
      name: category.name,
      emoji: category.emoji,
      color: category.color,
    },
  });
  const c = await getDb().categories.get(id);
  if (!c) throw new Error("upsertCustomCategory: projection missing post-emit");
  return c;
}

export async function deleteCustomCategory(category: Category): Promise<void> {
  if (category.isDefault) {
    throw new Error("cannot delete a default category");
  }
  await emit(category.eventId, {
    type: OP_TYPE.CategoryDelete,
    categoryId: category.id,
  });
}
