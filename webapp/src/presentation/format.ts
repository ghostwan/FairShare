/**
 * Money + date formatters. Centralised so the EUR sign and the
 * "Aujourd'hui / Hier" relative dates render consistently across all
 * screens.
 */

const FR_DATE = new Intl.DateTimeFormat("fr-FR", {
  day: "2-digit",
  month: "short",
  year: "numeric",
});

const FR_TIME = new Intl.DateTimeFormat("fr-FR", {
  hour: "2-digit",
  minute: "2-digit",
});

const EUR = new Intl.NumberFormat("fr-FR", {
  style: "currency",
  currency: "EUR",
});

export function formatMoneyCents(cents: number, currency = "EUR"): string {
  const value = cents / 100;
  if (currency === "EUR") return EUR.format(value);
  return new Intl.NumberFormat("fr-FR", { style: "currency", currency }).format(
    value,
  );
}

export function formatSignedMoneyCents(cents: number, currency = "EUR"): string {
  const sign = cents > 0 ? "+" : "";
  return `${sign}${formatMoneyCents(cents, currency)}`;
}

export function formatDate(epochMs: number): string {
  const d = new Date(epochMs);
  const today = startOfDay(new Date());
  const yesterday = new Date(today);
  yesterday.setDate(yesterday.getDate() - 1);
  const ds = startOfDay(d);
  if (ds.getTime() === today.getTime()) {
    return `Aujourd'hui, ${FR_TIME.format(d)}`;
  }
  if (ds.getTime() === yesterday.getTime()) {
    return `Hier, ${FR_TIME.format(d)}`;
  }
  return FR_DATE.format(d);
}

function startOfDay(d: Date): Date {
  const c = new Date(d);
  c.setHours(0, 0, 0, 0);
  return c;
}

/**
 * Convert an ARGB int (Android-style 0xAARRGGBB) to a CSS `#RRGGBB`
 * string, dropping alpha — MUI badge chips already control their own
 * tint, so we only need the hue.
 */
export function argbToCssHex(argb: number): string {
  const r = (argb >>> 16) & 0xff;
  const g = (argb >>> 8) & 0xff;
  const b = argb & 0xff;
  return `#${r.toString(16).padStart(2, "0")}${g
    .toString(16)
    .padStart(2, "0")}${b.toString(16).padStart(2, "0")}`;
}
