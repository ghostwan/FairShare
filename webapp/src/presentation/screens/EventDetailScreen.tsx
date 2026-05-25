import { useState } from "react";
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
import DeleteIcon from "@mui/icons-material/Delete";
import EditIcon from "@mui/icons-material/Edit";
import ShareIcon from "@mui/icons-material/Share";
import { useLiveQuery } from "dexie-react-hooks";
import { useNavigate, useParams } from "react-router-dom";
import { getDb } from "@/data/db";
import {
  addParticipant,
  deleteCustomCategory,
  deleteExpense,
  deleteParticipant,
  listCategories,
  renameParticipant,
  upsertCustomCategory,
} from "@/data/repositories";
import {
  computeBalances,
  computeSettlements,
  totalSpent,
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
import { CategoryEditorDialog } from "../components/CategoryEditorDialog";

type TabKey = "expenses" | "balances" | "participants" | "categories";

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
    return (
      <Stack spacing={2} alignItems="center" sx={{ pt: 4 }}>
        <Typography variant="body1" align="center">
          Aucune donnée pour cet évènement.
        </Typography>
        <Typography
          variant="body2"
          color="text.secondary"
          align="center"
          sx={{ maxWidth: 360 }}
        >
          L'autre device n'a peut-être pas encore poussé l'historique
          vers le serveur de sync. Utilise le bouton de rafraîchissement
          en haut à droite, ou demande à la personne qui t'a invité
          d'ouvrir l'app puis "Synchroniser maintenant" dans les
          paramètres.
        </Typography>
      </Stack>
    );
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
        <Tab value="categories" label={fr.tabs.categories} />
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
          currency={event.currency}
          participants={participants}
          expenses={expenses}
        />
      )}
      {tab === "participants" && (
        <ParticipantsTab eventId={eventId} participants={participants} />
      )}
      {tab === "categories" && (
        <CategoriesTab eventId={eventId} categories={categories} />
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
  currency: string;
  participants: import("@/core/domain/models").Participant[];
  expenses: import("@/core/domain/models").Expense[];
}) {
  const balances = computeBalances(props.participants, props.expenses);
  const settlements = computeSettlements(balances);
  const allZero = balances.every((b) => b.netCents === 0);

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
          <List dense>
            {settlements.map((s, i) => (
              <ListItem key={i}>
                <ListItemText
                  primary={`${s.fromName} ${fr.balances.owes} ${formatMoneyCents(s.amountCents, props.currency)} ${fr.balances.to} ${s.toName}`}
                />
              </ListItem>
            ))}
          </List>
        </Box>
      )}
    </Stack>
  );
}

function ParticipantsTab(props: {
  eventId: string;
  participants: import("@/core/domain/models").Participant[];
}) {
  const [adding, setAdding] = useState(false);
  const [renaming, setRenaming] =
    useState<import("@/core/domain/models").Participant | null>(null);
  const [removing, setRemoving] =
    useState<import("@/core/domain/models").Participant | null>(null);

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
        {props.participants.map((p) => (
          <ListItem key={p.id}>
            <ListItemText primary={p.name} />
            <ListItemSecondaryAction>
              <IconButton onClick={() => setRenaming(p)}>
                <EditIcon />
              </IconButton>
              <IconButton onClick={() => setRemoving(p)}>
                <DeleteIcon />
              </IconButton>
            </ListItemSecondaryAction>
          </ListItem>
        ))}
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

function CategoriesTab(props: {
  eventId: string;
  categories: Category[];
}) {
  const [editing, setEditing] = useState<Category | null | "new">(null);
  const [removing, setRemoving] = useState<Category | null>(null);

  const custom = props.categories.filter((c) => !c.isDefault);

  return (
    <Stack spacing={1}>
      <Button
        variant="contained"
        startIcon={<AddIcon />}
        onClick={() => setEditing("new")}
      >
        {fr.categories.add}
      </Button>
      <Typography variant="overline" color="text.secondary">
        Par défaut
      </Typography>
      <List dense>
        {DEFAULT_CATEGORIES.map((c) => (
          <ListItem key={c.id}>
            <ListItemText
              primary={`${c.emoji} ${c.name}`}
              secondary={fr.categories.defaultBadge}
            />
          </ListItem>
        ))}
      </List>
      <Typography variant="overline" color="text.secondary">
        Personnalisées
      </Typography>
      {custom.length === 0 && (
        <Typography color="text.secondary">{fr.categories.empty}</Typography>
      )}
      <List dense>
        {custom.map((c) => (
          <ListItem key={c.id}>
            <ListItemText primary={`${c.emoji} ${c.name}`} />
            <ListItemSecondaryAction>
              <IconButton onClick={() => setEditing(c)}>
                <EditIcon />
              </IconButton>
              <IconButton onClick={() => setRemoving(c)}>
                <DeleteIcon />
              </IconButton>
            </ListItemSecondaryAction>
          </ListItem>
        ))}
      </List>

      <CategoryEditorDialog
        open={editing != null}
        initial={editing === "new" ? null : editing}
        onCancel={() => setEditing(null)}
        onConfirm={async (data) => {
          await upsertCustomCategory(props.eventId, {
            id: editing === "new" ? undefined : editing?.id,
            name: data.name,
            emoji: data.emoji,
            color: data.color,
          });
          setEditing(null);
        }}
      />
      <ConfirmDialog
        open={!!removing}
        title={fr.categories.delete}
        confirmLabel={fr.categories.delete}
        onCancel={() => setRemoving(null)}
        onConfirm={async () => {
          if (removing) await deleteCustomCategory(removing);
          setRemoving(null);
        }}
      />
    </Stack>
  );
}
