import { useEffect, useMemo, useState } from "react";
import {
  Alert,
  Box,
  Button,
  Chip,
  FormControl,
  FormControlLabel,
  InputLabel,
  MenuItem,
  Select,
  Stack,
  Switch,
  TextField,
  Typography,
} from "@mui/material";
import CardGiftcardIcon from "@mui/icons-material/CardGiftcard";
import { useNavigate, useParams } from "react-router-dom";
import { useLiveQuery } from "dexie-react-hooks";
import { fr } from "@/i18n/fr";
import { getDb } from "@/data/db";
import { listCategories, upsertExpense } from "@/data/repositories";
import type {
  Category,
  Expense,
  ExpenseItem,
} from "@/core/domain/models";
import { assignReceiptItems } from "@/core/domain/receiptAssign";
import { splitEqually } from "@/core/domain/split";
import { argbToCssHex, formatMoneyCents } from "../format";
import { ReceiptItemsEditor } from "../components/ReceiptItemsEditor";

/**
 * Add / edit expense. Two flavours behind a single screen:
 *
 *  - **Simple mode** (default): equal-split chips, free-form amount,
 *    matches Android's `AddExpenseScreen`.
 *  - **Receipt mode** (when editing an expense whose `items` aren't
 *    empty — typically created via the scan flow): swaps the chips for
 *    a `ReceiptItemsEditor`, derives the amount from the items sum,
 *    and recomputes shares via `assignReceiptItems` on save. Mirrors
 *    Android's `EditReceiptScreen` route in `EditExpenseRouter`.
 *
 * Cents conversion: we parse the user's decimal input with the FR
 * locale (`,` as separator) and round half-away-from-zero. Display is
 * always 2 decimals via toFixed(2) when re-hydrating the form.
 */
export function AddExpenseScreen() {
  const { eventId = "", expenseId } = useParams<{
    eventId: string;
    expenseId?: string;
  }>();
  const navigate = useNavigate();
  const isEdit = expenseId != null;

  const event = useLiveQuery(() => getDb().events.get(eventId), [eventId]);
  const participants = useLiveQuery(
    () =>
      getDb().participants.where("eventId").equals(eventId).sortBy("name"),
    [eventId],
    [],
  );
  const categories = useLiveQuery(
    () => listCategories(eventId),
    [eventId],
    [] as Category[],
  );
  const existing = useLiveQuery(
    async () => (expenseId ? await getDb().expenses.get(expenseId) : undefined),
    [expenseId],
  ) as Expense | undefined;

  const [title, setTitle] = useState("");
  const [amountStr, setAmountStr] = useState("");
  const [payerId, setPayerId] = useState<string>("");
  const [participantsIncluded, setParticipantsIncluded] = useState<Set<string>>(
    new Set(),
  );
  const [participantsGifted, setParticipantsGifted] = useState<Set<string>>(
    new Set(),
  );
  const [date, setDate] = useState<string>(toLocalDateInput(Date.now()));
  const [categoryId, setCategoryId] = useState<string>("");
  const [isSettlement, setIsSettlement] = useState(false);
  const [items, setItems] = useState<ExpenseItem[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [hydrated, setHydrated] = useState(false);

  // Hydrate the form once from either the existing expense (edit) or
  // the participants list (create — default to "everyone selected").
  useEffect(() => {
    if (hydrated) return;
    if (isEdit) {
      if (existing == null) return;
      setTitle(existing.title);
      setAmountStr((existing.amountCents / 100).toFixed(2).replace(".", ","));
      setPayerId(existing.payerId);
      const included = new Set<string>();
      const gifted = new Set<string>();
      for (const s of existing.shares) {
        if (s.coveredBy && s.coveredBy.length > 0) gifted.add(s.participantId);
        else included.add(s.participantId);
      }
      setParticipantsIncluded(included);
      setParticipantsGifted(gifted);
      setDate(toLocalDateInput(existing.date));
      setCategoryId(existing.categoryId ?? "");
      setIsSettlement(existing.isSettlement);
      setItems(existing.items ?? []);
      setHydrated(true);
    } else if (participants.length > 0) {
      setPayerId((prev) => prev || participants[0]!.id);
      setParticipantsIncluded(new Set(participants.map((p) => p.id)));
      setParticipantsGifted(new Set());
      setHydrated(true);
    }
  }, [hydrated, isEdit, existing, participants]);

  const receiptMode = items.length > 0;
  const itemsTotalCents = useMemo(
    () => items.reduce((s, it) => s + it.priceCents, 0),
    [items],
  );
  const amountCents = useMemo(
    () => (receiptMode ? itemsTotalCents : parseAmountCents(amountStr)),
    [receiptMode, itemsTotalCents, amountStr],
  );
  const valid =
    title.trim().length > 0 &&
    amountCents != null &&
    amountCents > 0 &&
    payerId.length > 0 &&
    (receiptMode || participantsIncluded.size > 0);

  const submit = async () => {
    if (!valid || amountCents == null) return;
    // Preserve the participants list order so the cent remainder is
    // distributed predictably (matches the order they appear in the
    // UI: alphabetical by name from the parent `participants` query).
    const orderedIncluded = participants
      .map((p) => p.id)
      .filter((id) => participantsIncluded.has(id));
    const orderedGifted = participants
      .map((p) => p.id)
      .filter((id) => participantsGifted.has(id));
    const shares = receiptMode
      ? assignReceiptItems(items, participants.map((p) => p.id))
      : splitEqually(amountCents, orderedIncluded, orderedGifted);
    const exp: Expense = {
      id: existing?.id ?? "",
      eventId,
      title: title.trim(),
      amountCents,
      payerId,
      date: new Date(date).getTime(),
      shares,
      items,
      isSettlement,
      categoryId: categoryId || null,
    };
    try {
      await upsertExpense(exp);
      navigate(-1);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  if (event == null) return null;
  if (participants.length === 0) {
    return (
      <Stack spacing={2}>
        <Alert severity="info">{fr.participants.empty}</Alert>
        <Button onClick={() => navigate(-1)}>{fr.common.close}</Button>
      </Stack>
    );
  }

  return (
    <Stack spacing={2}>
      <Typography variant="h6">
        {isEdit ? fr.expenses.edit : fr.expenses.add}
      </Typography>
      <TextField
        label={fr.expenses.title}
        value={title}
        onChange={(e) => setTitle(e.target.value)}
        fullWidth
        autoFocus
      />
      <TextField
        label={fr.expenses.amount}
        value={receiptMode ? formatMoneyCents(itemsTotalCents) : amountStr}
        onChange={(e) => setAmountStr(e.target.value)}
        inputMode="decimal"
        fullWidth
        disabled={receiptMode}
        helperText={
          receiptMode ? "Calculé depuis les articles du ticket" : undefined
        }
      />
      <FormControl fullWidth>
        <InputLabel>{fr.expenses.payer}</InputLabel>
        <Select
          value={payerId}
          label={fr.expenses.payer}
          onChange={(e) => setPayerId(e.target.value)}
        >
          {participants.map((p) => (
            <MenuItem key={p.id} value={p.id}>
              {p.name}
            </MenuItem>
          ))}
        </Select>
      </FormControl>
      <TextField
        label={fr.expenses.date}
        type="datetime-local"
        value={date}
        onChange={(e) => setDate(e.target.value)}
        InputLabelProps={{ shrink: true }}
        fullWidth
      />
      <FormControl fullWidth>
        <InputLabel>{fr.expenses.category}</InputLabel>
        <Select
          value={categoryId}
          label={fr.expenses.category}
          onChange={(e) => setCategoryId(e.target.value)}
        >
          <MenuItem value="">{fr.expenses.categoryNone}</MenuItem>
          {categories.map((c) => (
            <MenuItem key={c.id} value={c.id}>
              {c.emoji} {c.name}
            </MenuItem>
          ))}
        </Select>
      </FormControl>

      <Typography variant="subtitle2">{fr.expenses.split}</Typography>
      {receiptMode ? (
        <ReceiptItemsEditor
          items={items}
          participants={participants}
          onChange={setItems}
          allowAdd
        />
      ) : (
        <>
          <Typography variant="caption" color="text.secondary">
            {fr.expenses.splitEqual}
          </Typography>
          <Typography variant="caption" color="text.secondary">
            {fr.expenses.giftHint}
          </Typography>
          <Box sx={{ display: "flex", flexWrap: "wrap", gap: 1 }}>
            {participants.map((p) => {
              const included = participantsIncluded.has(p.id);
              const gifted = participantsGifted.has(p.id);
              const state: "off" | "in" | "gift" = gifted
                ? "gift"
                : included
                  ? "in"
                  : "off";
              const onClick = () => {
                // Cycle: off → in → gift → off. The two sets are
                // kept disjoint by always recomputing both at once.
                const nextIn = new Set(participantsIncluded);
                const nextGift = new Set(participantsGifted);
                if (state === "off") {
                  nextIn.add(p.id);
                  nextGift.delete(p.id);
                } else if (state === "in") {
                  nextIn.delete(p.id);
                  nextGift.add(p.id);
                } else {
                  nextIn.delete(p.id);
                  nextGift.delete(p.id);
                }
                setParticipantsIncluded(nextIn);
                setParticipantsGifted(nextGift);
              };
              return (
                <Chip
                  key={p.id}
                  label={p.name}
                  onClick={onClick}
                  color={
                    state === "in"
                      ? "primary"
                      : state === "gift"
                        ? "secondary"
                        : "default"
                  }
                  variant={state === "off" ? "outlined" : "filled"}
                  icon={
                    state === "gift" ? (
                      <CardGiftcardIcon fontSize="small" />
                    ) : undefined
                  }
                />
              );
            })}
          </Box>
        </>
      )}

      <FormControlLabel
        control={
          <Switch
            checked={isSettlement}
            onChange={(e) => setIsSettlement(e.target.checked)}
          />
        }
        label={fr.expenses.isSettlement}
      />

      {/* Live chip showing the active category color, purely cosmetic */}
      {categoryId && (
        <Chip
          size="small"
          label={categories.find((c) => c.id === categoryId)?.name ?? ""}
          sx={{
            alignSelf: "flex-start",
            bgcolor:
              argbToCssHex(
                categories.find((c) => c.id === categoryId)?.color ?? 0xff607d8b,
              ) + "22",
          }}
        />
      )}

      <Stack direction="row" spacing={1}>
        <Button onClick={() => navigate(-1)} fullWidth>
          {fr.common.cancel}
        </Button>
        <Button
          variant="contained"
          onClick={submit}
          disabled={!valid}
          fullWidth
        >
          {fr.expenses.save}
        </Button>
      </Stack>

      {error && <Alert severity="error">{error}</Alert>}
    </Stack>
  );
}

function parseAmountCents(input: string): number | null {
  const trimmed = input.trim().replace(/\s/g, "").replace(",", ".");
  if (trimmed.length === 0) return null;
  const n = Number(trimmed);
  if (!Number.isFinite(n) || n < 0) return null;
  // Round half-away-from-zero on the cent.
  return Math.round(n * 100);
}

/** YYYY-MM-DDTHH:mm in local time for <input type="datetime-local">. */
function toLocalDateInput(epochMs: number): string {
  const d = new Date(epochMs);
  const pad = (n: number) => n.toString().padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(
    d.getHours(),
  )}:${pad(d.getMinutes())}`;
}
