import { useEffect, useState } from "react";
import {
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  IconButton,
  List,
  ListItem,
  ListItemButton,
  ListItemSecondaryAction,
  ListItemText,
  Stack,
  Tab,
  Tabs,
  Typography,
} from "@mui/material";
import AddIcon from "@mui/icons-material/Add";
import CheckIcon from "@mui/icons-material/Check";
import DeleteIcon from "@mui/icons-material/Delete";
import EditIcon from "@mui/icons-material/Edit";
import ShareIcon from "@mui/icons-material/Share";
import SyncIcon from "@mui/icons-material/Sync";
import { useLiveQuery } from "dexie-react-hooks";
import { useNavigate, useParams } from "react-router-dom";
import { getDb } from "@/data/db";
import {
  addParticipant,
  deleteExpense,
  deleteParticipant,
  listCategories,
  recordSettlement,
  renameParticipant,
} from "@/data/repositories";
import {
  computeBalances,
  computeSettlements,
  totalSpent,
  totalsPaidBy,
} from "@/core/domain/balances";
import { fr } from "@/i18n/fr";
import {
  argbToCssHex,
  formatDate,
  formatMoneyCents,
  formatSignedMoneyCents,
} from "../format";
import { DEFAULT_CATEGORIES } from "@/core/domain/defaultCategories";
import type { Category } from "@/core/domain/models";
import { TextPromptDialog } from "../components/TextPromptDialog";
import { ConfirmDialog } from "../components/ConfirmDialog";
import { syncNow } from "@/sync/coordinator";
import { Alert } from "@mui/material";

type TabKey = "expenses" | "balances" | "participants";

export function EventDetailScreen() {
  const { eventId = "" } = useParams<{ eventId: string }>();
  const navigate = useNavigate();
  const [tab, setTab] = useState<TabKey>("expenses");

  const event = useLiveQuery(() => getDb().events.get(eventId), [eventId]);
  const participants = useLiveQuery(
    () => getDb().participants.where("eventId").equals(eventId).sortBy("name"),
    [eventId],
    [],
  );
  const expenses = useLiveQuery(
    () =>
      getDb()
        .expenses.where("eventId")
        .equals(eventId)
        .toArray()
        .then((xs) => xs.sort((a, b) => b.date - a.date)),
    [eventId],
    [],
  );
  const categories = useLiveQuery(
    () => listCategories(eventId),
    [eventId],
    [] as Category[],
  );

  if (event == null) {
    return <EmptyEventState eventId={eventId} />;
  }

  return (
    <Stack spacing={2} sx={{ flex: 1 }}>
      <SummaryCard
        totalCents={totalSpent(expenses)}
        currency={event.currency}
        participantsCount={participants.length}
        onInvite={() => navigate(`/event/${eventId}/invite`)}
      />

      <Tabs
        value={tab}
        onChange={(_, v) => setTab(v as TabKey)}
        variant="fullWidth"
      >
        <Tab value="expenses" label={fr.tabs.expenses} />
        <Tab value="balances" label={fr.tabs.balances} />
        <Tab value="participants" label={fr.tabs.participants} />
      </Tabs>

      {tab === "expenses" && (
        <ExpensesTab
          eventId={eventId}
          currency={event.currency}
          expenses={expenses}
          participantsById={new Map(participants.map((p) => [p.id, p]))}
          categoriesById={new Map(categories.map((c) => [c.id, c]))}
        />
      )}
      {tab === "balances" && (
        <BalancesTab
          eventId={eventId}
          currency={event.currency}
          participants={participants}
          expenses={expenses}
        />
      )}
      {tab === "participants" && (
        <ParticipantsTab
          eventId={eventId}
          currency={event.currency}
          participants={participants}
          expenses={expenses}
        />
      )}
    </Stack>
  );
}

function EmptyEventState({ eventId }: { eventId: string }) {
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [autoRan, setAutoRan] = useState(false);

  const run = async () => {
    setBusy(true);
    setError(null);
    setResult(null);
    try {
      const r = await syncNow(eventId);
      if (r.error) {
        setError(
          `${r.error.message}${r.error.status ? ` (HTTP ${r.error.status})` : ""}`,
        );
      } else {
        setResult(
          `Push: ${r.pushed} · Pull: ${r.pulled}. ` +
            (r.pulled === 0
              ? "Le serveur n'a rien renvoyé. L'autre device n'a probablement pas poussé l'historique : demande-lui d'ouvrir l'app puis « Synchroniser maintenant » dans les réglages."
              : "Si rien ne s'affiche, recharge la page."),
        );
      }
    } catch (e) {
      setError(`${(e as Error).message ?? e}`);
    } finally {
      setBusy(false);
    }
  };

  // Auto-trigger one sync on mount: most of the time the bootstrap
  // already pulled, but if the user arrived here via stale state
  // (page reload, swipe-back from join) a fresh attempt is cheap.
  useEffect(() => {
    if (autoRan) return;
    setAutoRan(true);
    void run();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <Stack spacing={2} alignItems="stretch" sx={{ pt: 4, maxWidth: 480, mx: "auto" }}>
      <Typography variant="body1" align="center">
        Aucune donnée pour cet évènement.
      </Typography>
      <Typography variant="body2" color="text.secondary" align="center">
        On essaie de récupérer l'historique depuis le serveur. Si rien
        n'arrive, demande à la personne qui t'a invité d'ouvrir l'app
        puis « Synchroniser maintenant » dans les réglages.
      </Typography>
      <Button
        variant="contained"
        startIcon={<SyncIcon />}
        onClick={() => void run()}
        disabled={busy}
      >
        {busy ? "Synchronisation…" : "Synchroniser maintenant"}
      </Button>
      {result && (
        <Alert severity="info" sx={{ whiteSpace: "pre-wrap" }}>
          {result}
        </Alert>
      )}
      {error && (
        <Alert severity="error" sx={{ whiteSpace: "pre-wrap", wordBreak: "break-word" }}>
          {error}
        </Alert>
      )}
    </Stack>
  );
}

function SummaryCard(props: {
  totalCents: number;
  currency: string;
  participantsCount: number;
  onInvite: () => void;
}) {
  return (
    <Card variant="outlined">
      <CardContent>
        <Stack direction="row" alignItems="center" spacing={2}>
          <Box sx={{ flex: 1 }}>
            <Typography variant="overline" color="text.secondary">
              Total dépensé
            </Typography>
            <Typography variant="h5">
              {formatMoneyCents(props.totalCents, props.currency)}
            </Typography>
            <Typography variant="caption" color="text.secondary">
              {props.participantsCount} participant
              {props.participantsCount > 1 ? "s" : ""}
            </Typography>
          </Box>
          <Button
            variant="outlined"
            startIcon={<ShareIcon />}
            onClick={props.onInvite}
          >
            {fr.events.invite}
          </Button>
        </Stack>
      </CardContent>
    </Card>
  );
}

function ExpensesTab(props: {
  eventId: string;
  currency: string;
  expenses: import("@/core/domain/models").Expense[];
  participantsById: Map<string, import("@/core/domain/models").Participant>;
  categoriesById: Map<string, Category>;
}) {
  const navigate = useNavigate();
  const [toDelete, setToDelete] = useState<
    import("@/core/domain/models").Expense | null
  >(null);

  if (props.expenses.length === 0) {
    return (
      <Stack spacing={2}>
        <Typography color="text.secondary">{fr.expenses.empty}</Typography>
        <Button
          variant="contained"
          startIcon={<AddIcon />}
          onClick={() => navigate(`/event/${props.eventId}/expense/new`)}
        >
          {fr.expenses.add}
        </Button>
      </Stack>
    );
  }

  return (
    <Stack spacing={1}>
      <Stack direction="row" spacing={1}>
        <Button
          variant="contained"
          startIcon={<AddIcon />}
          onClick={() => navigate(`/event/${props.eventId}/expense/new`)}
          fullWidth
        >
          {fr.expenses.add}
        </Button>
        <Button
          variant="outlined"
          onClick={() => navigate(`/event/${props.eventId}/receipt`)}
        >
          {fr.receipt.scan}
        </Button>
      </Stack>
      <List dense>
        {props.expenses.map((e) => {
          const payer = props.participantsById.get(e.payerId);
          const cat =
            (e.categoryId && props.categoriesById.get(e.categoryId)) ||
            (e.categoryId &&
              DEFAULT_CATEGORIES.find((c) => c.id === e.categoryId));
          return (
            <ListItem
              key={e.id}
              disablePadding
              secondaryAction={
                <IconButton
                  edge="end"
                  onClick={(ev) => {
                    ev.stopPropagation();
                    setToDelete(e);
                  }}
                >
                  <DeleteIcon />
                </IconButton>
              }
            >
              <ListItemButton
                onClick={() =>
                  navigate(`/event/${props.eventId}/expense/${e.id}`)
                }
              >
                <ListItemText
                  primary={
                    <Stack direction="row" spacing={1} alignItems="center">
                      {cat && (
                        <Chip
                          size="small"
                          label={`${cat.emoji} ${cat.name}`}
                          sx={{
                            bgcolor: argbToCssHex(cat.color) + "22",
                            color: argbToCssHex(cat.color),
                          }}
                        />
                      )}
                      <Typography component="span">{e.title}</Typography>
                    </Stack>
                  }
                  secondary={`${formatMoneyCents(e.amountCents, props.currency)} — ${payer?.name ?? "?"} · ${formatDate(e.date)}`}
                />
              </ListItemButton>
            </ListItem>
          );
        })}
      </List>
      <ConfirmDialog
        open={!!toDelete}
        title={fr.expenses.confirmDelete}
        confirmLabel={fr.expenses.delete}
        onCancel={() => setToDelete(null)}
        onConfirm={async () => {
          if (toDelete) await deleteExpense(toDelete);
          setToDelete(null);
        }}
      />
    </Stack>
  );
}

function BalancesTab(props: {
  eventId: string;
  currency: string;
  participants: import("@/core/domain/models").Participant[];
  expenses: import("@/core/domain/models").Expense[];
}) {
  const balances = computeBalances(props.participants, props.expenses);
  const settlements = computeSettlements(balances);
  const allZero = balances.every((b) => b.netCents === 0);
  const [busyKey, setBusyKey] = useState<string | null>(null);

  const onSettle = async (s: import("@/core/domain/balances").Settlement) => {
    const key = `${s.fromId}->${s.toId}`;
    setBusyKey(key);
    try {
      await recordSettlement(
        props.eventId,
        s.fromId,
        s.fromName,
        s.toId,
        s.toName,
        s.amountCents,
      );
    } finally {
      setBusyKey(null);
    }
  };

  return (
    <Stack spacing={2}>
      {allZero && (
        <Typography color="text.secondary">{fr.balances.empty}</Typography>
      )}
      {!allZero && (
        <List dense>
          {balances.map((b) => (
            <ListItem key={b.participantId}>
              <ListItemText
                primary={b.participantName}
                secondary={
                  b.netCents > 0
                    ? fr.balances.creditor
                    : b.netCents < 0
                      ? fr.balances.debtor
                      : "—"
                }
              />
              <Typography
                color={b.netCents > 0 ? "success.main" : b.netCents < 0 ? "error" : "text.secondary"}
              >
                {formatSignedMoneyCents(b.netCents, props.currency)}
              </Typography>
            </ListItem>
          ))}
        </List>
      )}

      {settlements.length > 0 && (
        <Box>
          <Typography variant="subtitle1" gutterBottom>
            {fr.balances.suggestion}
          </Typography>
          <Stack spacing={1}>
            {settlements.map((s, i) => {
              const key = `${s.fromId}->${s.toId}`;
              return (
                <Card key={`${i}-${key}`} variant="outlined">
                  <CardContent>
                    <Stack
                      direction="row"
                      alignItems="center"
                      spacing={1}
                      sx={{ mb: 1 }}
                    >
                      <Typography variant="subtitle1" sx={{ flex: 1 }}>
                        {s.fromName} → {s.toName}
                      </Typography>
                      <Typography variant="subtitle1" fontWeight={600}>
                        {formatMoneyCents(s.amountCents, props.currency)}
                      </Typography>
                    </Stack>
                    <Stack direction="row" justifyContent="flex-end">
                      <Button
                        variant="contained"
                        size="small"
                        startIcon={<CheckIcon />}
                        onClick={() => void onSettle(s)}
                        disabled={busyKey != null}
                      >
                        {fr.balances.settle}
                      </Button>
                    </Stack>
                  </CardContent>
                </Card>
              );
            })}
          </Stack>
        </Box>
      )}
    </Stack>
  );
}

function ParticipantsTab(props: {
  eventId: string;
  currency: string;
  participants: import("@/core/domain/models").Participant[];
  expenses: import("@/core/domain/models").Expense[];
}) {
  const [adding, setAdding] = useState(false);
  const [renaming, setRenaming] =
    useState<import("@/core/domain/models").Participant | null>(null);
  const [removing, setRemoving] =
    useState<import("@/core/domain/models").Participant | null>(null);

  const totals = totalsPaidBy(props.expenses);

  return (
    <Stack spacing={1}>
      <Button
        variant="contained"
        startIcon={<AddIcon />}
        onClick={() => setAdding(true)}
      >
        {fr.participants.add}
      </Button>
      {props.participants.length === 0 && (
        <Typography color="text.secondary">{fr.participants.empty}</Typography>
      )}
      <List dense>
        {props.participants.map((p) => {
          const paid = totals.get(p.id) ?? 0;
          return (
            <ListItem key={p.id}>
              <ListItemText
                primary={p.name}
                secondary={`${fr.participants.totalPaid}: ${formatMoneyCents(paid, props.currency)}`}
              />
              <ListItemSecondaryAction>
                <IconButton onClick={() => setRenaming(p)}>
                  <EditIcon />
                </IconButton>
                <IconButton onClick={() => setRemoving(p)}>
                  <DeleteIcon />
                </IconButton>
              </ListItemSecondaryAction>
            </ListItem>
          );
        })}
      </List>

      <TextPromptDialog
        open={adding}
        title={fr.participants.add}
        label={fr.participants.name}
        confirmLabel={fr.participants.add}
        onCancel={() => setAdding(false)}
        onConfirm={async (name) => {
          await addParticipant(props.eventId, name);
          setAdding(false);
        }}
      />
      <TextPromptDialog
        open={!!renaming}
        title={fr.participants.rename}
        label={fr.participants.name}
        initialValue={renaming?.name ?? ""}
        confirmLabel={fr.participants.rename}
        onCancel={() => setRenaming(null)}
        onConfirm={async (name) => {
          if (renaming) await renameParticipant(renaming, name);
          setRenaming(null);
        }}
      />
      <ConfirmDialog
        open={!!removing}
        title={fr.participants.removeConfirm}
        confirmLabel={fr.participants.remove}
        onCancel={() => setRemoving(null)}
        onConfirm={async () => {
          if (removing) await deleteParticipant(removing);
          setRemoving(null);
        }}
      />
    </Stack>
  );
}

