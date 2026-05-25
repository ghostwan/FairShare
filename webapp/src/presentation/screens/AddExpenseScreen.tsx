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
import { useNavigate, useParams } from "react-router-dom";
import { useLiveQuery } from "dexie-react-hooks";
import { fr } from "@/i18n/fr";
import { getDb } from "@/data/db";
import { listCategories, upsertExpense } from "@/data/repositories";
import type { Category, Expense, ExpenseShare } from "@/core/domain/models";
import { argbToCssHex } from "../format";

/**
 * Add / edit expense. Default split is equal shares between every
 * participant, matching Android's behaviour; the form lets the user
 * uncheck participants individually. Custom (per-person amount) splits
 * aren't exposed in the v1 webapp because the receipt-item flow that
 * needs them lives on Android only for now.
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
  const [date, setDate] = useState<string>(toLocalDateInput(Date.now()));
  const [categoryId, setCategoryId] = useState<string>("");
  const [isSettlement, setIsSettlement] = useState(false);
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
      setParticipantsIncluded(new Set(existing.shares.map((s) => s.participantId)));
      setDate(toLocalDateInput(existing.date));
      setCategoryId(existing.categoryId ?? "");
      setIsSettlement(existing.isSettlement);
      setHydrated(true);
    } else if (participants.length > 0) {
      setPayerId((prev) => prev || participants[0]!.id);
      setParticipantsIncluded(new Set(participants.map((p) => p.id)));
      setHydrated(true);
    }
  }, [hydrated, isEdit, existing, participants]);

  const amountCents = useMemo(() => parseAmountCents(amountStr), [amountStr]);
  const valid =
    title.trim().length > 0 &&
    amountCents != null &&
    amountCents > 0 &&
    payerId.length > 0 &&
    participantsIncluded.size > 0;

  const submit = async () => {
    if (!valid || amountCents == null) return;
    const ids = [...participantsIncluded];
    const shares = splitEquallyCents(amountCents, ids);
    const exp: Expense = {
      id: existing?.id ?? "",
      eventId,
      title: title.trim(),
      amountCents,
      payerId,
      date: new Date(date).getTime(),
      shares,
      items: existing?.items ?? [],
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
        value={amountStr}
        onChange={(e) => setAmountStr(e.target.value)}
        inputMode="decimal"
        fullWidth
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
      <Typography variant="caption" color="text.secondary">
        {fr.expenses.splitEqual}
      </Typography>
      <Box sx={{ display: "flex", flexWrap: "wrap", gap: 1 }}>
        {participants.map((p) => {
          const on = participantsIncluded.has(p.id);
          return (
            <Chip
              key={p.id}
              label={p.name}
              onClick={() => {
                setParticipantsIncluded((prev) => {
                  const next = new Set(prev);
                  if (next.has(p.id)) next.delete(p.id);
                  else next.add(p.id);
                  return next;
                });
              }}
              color={on ? "primary" : "default"}
              variant={on ? "filled" : "outlined"}
            />
          );
        })}
      </Box>

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

function splitEquallyCents(total: number, ids: string[]): ExpenseShare[] {
  if (ids.length === 0) return [];
  const base = Math.floor(total / ids.length);
  let remainder = total - base * ids.length;
  return ids.map((id) => {
    let amount = base;
    if (remainder > 0) {
      amount += 1;
      remainder -= 1;
    }
    return { participantId: id, amountCents: amount };
  });
}

/** YYYY-MM-DDTHH:mm in local time for <input type="datetime-local">. */
function toLocalDateInput(epochMs: number): string {
  const d = new Date(epochMs);
  const pad = (n: number) => n.toString().padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(
    d.getHours(),
  )}:${pad(d.getMinutes())}`;
}
