import { useMemo } from "react";
import {
  Box,
  List,
  ListItem,
  ListItemText,
  Stack,
  Typography,
} from "@mui/material";
import { useParams } from "react-router-dom";
import { useLiveQuery } from "dexie-react-hooks";
import { fr } from "@/i18n/fr";
import { getDb } from "@/data/db";
import { listCategories } from "@/data/repositories";
import type { Category, Expense } from "@/core/domain/models";
import {
  computeCategoryStats,
  type CategoryStat,
} from "@/core/domain/categoryStats";
import { argbToCssHex, formatMoneyCents } from "../format";

/**
 * Stats screen — per-category breakdown of all the event's expenses,
 * sorted by total spent. 1:1 port of the Android `StatsList`
 * composable in `EventDetailScreen.kt`:
 *
 *  - "Total" header row with the grand total.
 *  - One row per category: emoji + name, total amount, a horizontal
 *    progress bar colored with the category accent, and a caption
 *    showing "{percent}% • {count}".
 *  - Settlements are excluded (already filtered upstream in
 *    `computeCategoryStats`).
 *
 * Unlike the Android version this lives on its own route reached
 * from a top-bar icon — the webapp's event tabs row is already full
 * (Dépenses / Balances / Participants) and there isn't enough room
 * for a fourth tab on a phone-sized PWA.
 */
export function StatsScreen() {
  const { eventId = "" } = useParams<{ eventId: string }>();

  const event = useLiveQuery(() => getDb().events.get(eventId), [eventId]);
  const expenses = useLiveQuery(
    () => getDb().expenses.where("eventId").equals(eventId).toArray(),
    [eventId],
    [] as Expense[],
  );
  const categories = useLiveQuery(
    () => listCategories(eventId),
    [eventId],
    [] as Category[],
  );

  const stats = useMemo(
    () => computeCategoryStats(expenses, categories),
    [expenses, categories],
  );
  const grandTotal = useMemo(
    () => stats.reduce((s, x) => s + x.totalCents, 0),
    [stats],
  );
  const currency = event?.currency ?? "EUR";

  if (stats.length === 0) {
    return (
      <Box sx={{ py: 4, textAlign: "center" }}>
        <Typography color="text.secondary">{fr.stats.empty}</Typography>
      </Box>
    );
  }

  return (
    <Stack spacing={1.5}>
      <ListItem disableGutters sx={{ py: 0.5 }}>
        <ListItemText
          primary={
            <Typography variant="subtitle1" sx={{ fontWeight: 500 }}>
              {fr.stats.total}
            </Typography>
          }
        />
        <Typography variant="subtitle1" sx={{ fontWeight: 600 }}>
          {formatMoneyCents(grandTotal, currency)}
        </Typography>
      </ListItem>
      <List dense disablePadding>
        {stats.map((s) => (
          <CategoryStatRow
            key={s.category?.id ?? "__uncategorized__"}
            stat={s}
            currency={currency}
          />
        ))}
      </List>
    </Stack>
  );
}

function CategoryStatRow({
  stat,
  currency,
}: {
  stat: CategoryStat;
  currency: string;
}) {
  const tint = stat.category != null ? argbToCssHex(stat.category.color) : "#9E9E9E";
  const label = stat.category
    ? `${stat.category.emoji} ${stat.category.name}`
    : fr.stats.uncategorized;
  const pct = Math.round(stat.fraction * 100);

  return (
    <Stack spacing={0.5} sx={{ py: 1 }}>
      <Stack direction="row" alignItems="center" spacing={1}>
        <Typography sx={{ flex: 1 }}>{label}</Typography>
        <Typography sx={{ fontWeight: 600 }}>
          {formatMoneyCents(stat.totalCents, currency)}
        </Typography>
      </Stack>
      <Stack direction="row" alignItems="center" spacing={1}>
        {/* Track + fill, mirrors the Android Box-in-Box bar. */}
        <Box
          sx={{
            flex: 1,
            height: 8,
            borderRadius: 1,
            bgcolor: `${tint}26`, // ~15% alpha
            overflow: "hidden",
          }}
        >
          <Box
            sx={{
              width: `${Math.max(0, Math.min(100, stat.fraction * 100))}%`,
              height: "100%",
              bgcolor: tint,
              borderRadius: 1,
            }}
          />
        </Box>
        <Typography variant="caption" color="text.secondary">
          {pct}% • {stat.count}
        </Typography>
      </Stack>
    </Stack>
  );
}
