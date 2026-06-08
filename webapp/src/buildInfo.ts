import pkg from "../package.json";

/**
 * Build-time identity surfaced in the footer of the events list and
 * the Settings "About" block. `APP_VERSION` comes from package.json
 * (bump it on feature releases), `BUILD_TIME` is injected by Vite's
 * `define` on every build — that's the one that lets the user
 * eyeball whether the PWA actually fetched the new shell after a
 * deploy.
 *
 * In Vitest the `__BUILD_TIME__` global isn't substituted, so we
 * fall back to "dev" rather than throwing a ReferenceError.
 */

declare const __BUILD_TIME__: string | undefined;

export const APP_VERSION: string = pkg.version;

const RAW_BUILD_TIME: string =
  typeof __BUILD_TIME__ !== "undefined" ? __BUILD_TIME__ : "dev";

export const BUILD_TIME: string = RAW_BUILD_TIME;

/**
 * Pretty-print the build timestamp for the UI as `YYYY-MM-DD HH:mm`
 * in the user's locale. Falls back to the raw value if it's not a
 * valid date (e.g. "dev" in tests / dev server).
 */
export function formatBuildTime(): string {
  const t = Date.parse(RAW_BUILD_TIME);
  if (Number.isNaN(t)) return RAW_BUILD_TIME;
  const d = new Date(t);
  const pad = (n: number) => n.toString().padStart(2, "0");
  return (
    `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ` +
    `${pad(d.getHours())}:${pad(d.getMinutes())}`
  );
}
