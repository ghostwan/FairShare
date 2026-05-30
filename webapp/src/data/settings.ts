import { getDb } from "./db";

/**
 * k/v settings store, mirroring the Android DataStore-backed settings
 * repository. Defaults live alongside the readers so callers don't
 * have to thread fallbacks through every screen.
 */

const KEYS = {
  cloudBaseUrl: "cloud.baseUrl",
  geminiApiKey: "gemini.apiKey",
  geminiModel: "gemini.model",
  autoRefreshOnFocus: "sync.autoRefreshOnFocus",
  pollIntervalMs: "sync.pollIntervalMs",
  pushNotificationsEnabled: "push.enabled",
  // Identity-style fields could grow here later (display name, etc.).
} as const;

export const DEFAULTS = {
  cloudBaseUrl: "https://fairshare-sync.ghostwan.workers.dev",
  geminiApiKey: "",
  geminiModel: "gemini-2.5-flash",
  autoRefreshOnFocus: "true",
  pollIntervalMs: "0", // 0 disables active polling; visibility-driven only.
  pushNotificationsEnabled: "false",
};

async function readKey(key: string, fallback: string): Promise<string> {
  const row = await getDb().settings.get(key);
  return row?.value ?? fallback;
}

async function writeKey(key: string, value: string): Promise<void> {
  await getDb().settings.put({ key, value });
}

export const Settings = {
  async getCloudBaseUrl(): Promise<string> {
    return readKey(KEYS.cloudBaseUrl, DEFAULTS.cloudBaseUrl);
  },
  setCloudBaseUrl(value: string): Promise<void> {
    return writeKey(KEYS.cloudBaseUrl, value);
  },
  async getGeminiApiKey(): Promise<string> {
    return readKey(KEYS.geminiApiKey, DEFAULTS.geminiApiKey);
  },
  setGeminiApiKey(value: string): Promise<void> {
    return writeKey(KEYS.geminiApiKey, value);
  },
  async getGeminiModel(): Promise<string> {
    return readKey(KEYS.geminiModel, DEFAULTS.geminiModel);
  },
  setGeminiModel(value: string): Promise<void> {
    return writeKey(KEYS.geminiModel, value);
  },
  async getAutoRefreshOnFocus(): Promise<boolean> {
    const raw = await readKey(KEYS.autoRefreshOnFocus, DEFAULTS.autoRefreshOnFocus);
    return raw !== "false";
  },
  setAutoRefreshOnFocus(value: boolean): Promise<void> {
    return writeKey(KEYS.autoRefreshOnFocus, value ? "true" : "false");
  },
  async getPushNotificationsEnabled(): Promise<boolean> {
    const raw = await readKey(
      KEYS.pushNotificationsEnabled,
      DEFAULTS.pushNotificationsEnabled,
    );
    return raw === "true";
  },
  setPushNotificationsEnabled(value: boolean): Promise<void> {
    return writeKey(KEYS.pushNotificationsEnabled, value ? "true" : "false");
  },
};
