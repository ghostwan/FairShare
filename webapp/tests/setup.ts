// Vitest setup. jsdom doesn't ship IndexedDB; fake-indexeddb installs a
// spec-compliant in-memory replacement on globalThis so Dexie just works
// in tests. Web Crypto is provided by Node 20+ via globalThis.crypto.
import "fake-indexeddb/auto";
