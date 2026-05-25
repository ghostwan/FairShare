import type {
  CategorySnapshot,
  EventSnapshot,
  ExpenseSnapshot,
  ParticipantSnapshot,
} from "./snapshots";

/**
 * `OpPayload` from the Kotlin side. The `type` discriminator value is
 * the *fully-qualified Kotlin class name* of the variant — that's how
 * `kotlinx.serialization`'s `classDiscriminator` defaults work for
 * sealed types. These constants must not drift from Kotlin or Android
 * peers will fail to decode our ops.
 */

export const OP_TYPE = {
  EventUpsert: "com.fairshare.domain.model.sync.OpPayload.EventUpsert",
  EventDelete: "com.fairshare.domain.model.sync.OpPayload.EventDelete",
  ParticipantUpsert:
    "com.fairshare.domain.model.sync.OpPayload.ParticipantUpsert",
  ParticipantDelete:
    "com.fairshare.domain.model.sync.OpPayload.ParticipantDelete",
  ExpenseUpsert: "com.fairshare.domain.model.sync.OpPayload.ExpenseUpsert",
  ExpenseDelete: "com.fairshare.domain.model.sync.OpPayload.ExpenseDelete",
  CategoryUpsert: "com.fairshare.domain.model.sync.OpPayload.CategoryUpsert",
  CategoryDelete: "com.fairshare.domain.model.sync.OpPayload.CategoryDelete",
} as const;

export type OpType = (typeof OP_TYPE)[keyof typeof OP_TYPE];

export type OpPayload =
  | { type: typeof OP_TYPE.EventUpsert; event: EventSnapshot }
  | { type: typeof OP_TYPE.EventDelete; eventId: string }
  | { type: typeof OP_TYPE.ParticipantUpsert; participant: ParticipantSnapshot }
  | { type: typeof OP_TYPE.ParticipantDelete; participantId: string }
  | { type: typeof OP_TYPE.ExpenseUpsert; expense: ExpenseSnapshot }
  | { type: typeof OP_TYPE.ExpenseDelete; expenseId: string }
  | { type: typeof OP_TYPE.CategoryUpsert; category: CategorySnapshot }
  | { type: typeof OP_TYPE.CategoryDelete; categoryId: string };

export type EntityKind = "EVENT" | "PARTICIPANT" | "CATEGORY" | "EXPENSE";

export function entityIdOf(p: OpPayload): string {
  switch (p.type) {
    case OP_TYPE.EventUpsert:
      return p.event.id;
    case OP_TYPE.EventDelete:
      return p.eventId;
    case OP_TYPE.ParticipantUpsert:
      return p.participant.id;
    case OP_TYPE.ParticipantDelete:
      return p.participantId;
    case OP_TYPE.ExpenseUpsert:
      return p.expense.id;
    case OP_TYPE.ExpenseDelete:
      return p.expenseId;
    case OP_TYPE.CategoryUpsert:
      return p.category.id;
    case OP_TYPE.CategoryDelete:
      return p.categoryId;
  }
}

export function entityKindOf(p: OpPayload): EntityKind {
  switch (p.type) {
    case OP_TYPE.EventUpsert:
    case OP_TYPE.EventDelete:
      return "EVENT";
    case OP_TYPE.ParticipantUpsert:
    case OP_TYPE.ParticipantDelete:
      return "PARTICIPANT";
    case OP_TYPE.CategoryUpsert:
    case OP_TYPE.CategoryDelete:
      return "CATEGORY";
    case OP_TYPE.ExpenseUpsert:
    case OP_TYPE.ExpenseDelete:
      return "EXPENSE";
  }
}
