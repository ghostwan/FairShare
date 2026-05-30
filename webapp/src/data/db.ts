import Dexie, { type EntityTable } from "dexie";
import type {
  Category,
  Event,
  Expense,
  Participant,
} from "@/core/domain/models";

/**
 * Local IndexedDB schema for the webapp, mirroring the surfaces the
 * Android Room database exposes to the rest of the app. We don't try
 * to replicate Room's normalised relational layout — IndexedDB is a
 * document store, so Expense embeds its shares + items as JSON, which
 * also keeps the per-Expense LWW semantics of the sync layer obvious.
 *
 * Three "system" stores back the sync engine:
 *
 *   - `identity` — singleton row holding this device's UUID + the
 *     monotonic Lamport clock.
 *   - `eventSecrets` — per-event encryption key + Worker bearer.
 *   - `opLog` — full ciphertext + plaintext op log, used both as the
 *     replay source for snapshots and as the push outbox.
 *   - `opCursor` — per-event `(nextSince, nextSinceOp)` for paginated
 *     pulls from the Worker.
 *   - `settings` — k/v for cloud base URL, Gemini key, etc.
 */

export interface IdentityRow {
  id: "singleton";
  deviceId: string;
  lamport: number;
}

export interface EventSecretRow {
  eventId: string;
  /** Stored as base64url so IndexedDB tooling can inspect it. */
  eventKeyB64: string;
  bearer: string;
}

export interface OpLogRow {
  opId: string;
  eventId: string;
  deviceId: string;
  lamport: number;
  wallClockMs: number;
  /** Full operation JSON (plaintext side). */
  payloadJson: string;
  /** Raw encrypted bytes (b64-encoded) for push retry without re-encrypting. */
  nonceB64: string;
  ciphertextB64: string;
  /** Local op — not yet acknowledged by the Worker push. */
  pendingPush: 0 | 1;
}

export interface OpCursorRow {
  eventId: string;
  nextSince: number;
  nextSinceOp: string;
}

export interface SettingsRow {
  key: string;
  value: string;
}

export interface WebPushPrefRow {
  eventId: string;
  /** 1 when the user has opted-in to receive web push notifications. */
  enabled: 0 | 1;
}

export class FairShareDb extends Dexie {
  identity!: EntityTable<IdentityRow, "id">;
  eventSecrets!: EntityTable<EventSecretRow, "eventId">;
  events!: EntityTable<Event, "id">;
  participants!: EntityTable<Participant, "id">;
  expenses!: EntityTable<Expense, "id">;
  categories!: EntityTable<Category, "id">;
  opLog!: EntityTable<OpLogRow, "opId">;
  opCursor!: EntityTable<OpCursorRow, "eventId">;
  settings!: EntityTable<SettingsRow, "key">;
  webPushPrefs!: EntityTable<WebPushPrefRow, "eventId">;

  constructor(name = "fairshare") {
    super(name);
    this.version(1).stores({
      identity: "id",
      eventSecrets: "eventId",
      events: "id, createdAt, archived",
      participants: "id, eventId",
      expenses: "id, eventId, date",
      categories: "id, eventId",
      // pendingPush is indexed so the outbox query is a single
      // `where("pendingPush").equals(1)` scan instead of a full table walk.
      opLog: "opId, eventId, lamport, pendingPush, [eventId+lamport]",
      opCursor: "eventId",
      settings: "key",
    });
    // v2: per-event web-push opt-in. Dexie reuses the existing data
    // and just creates the new object store on upgrade.
    this.version(2).stores({
      webPushPrefs: "eventId, enabled",
    });
  }
}

let dbInstance: FairShareDb | null = null;

export function getDb(): FairShareDb {
  if (dbInstance == null) dbInstance = new FairShareDb();
  return dbInstance;
}

/** Test-only helper: swap in a uniquely-named DB for isolation. */
export function __setDbForTest(name: string): FairShareDb {
  dbInstance = new FairShareDb(name);
  return dbInstance;
}
