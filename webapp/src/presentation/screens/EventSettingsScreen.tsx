import { useEffect, useState } from "react";
import {
  Alert,
  Button,
  FormControlLabel,
  IconButton,
  List,
  ListItem,
  ListItemSecondaryAction,
  ListItemText,
  Stack,
  Switch,
  Typography,
} from "@mui/material";
import AddIcon from "@mui/icons-material/Add";
import DeleteIcon from "@mui/icons-material/Delete";
import EditIcon from "@mui/icons-material/Edit";
import { useLiveQuery } from "dexie-react-hooks";
import { useParams } from "react-router-dom";
import {
  deleteCustomCategory,
  listCategories,
  upsertCustomCategory,
} from "@/data/repositories";
import { DEFAULT_CATEGORIES } from "@/core/domain/defaultCategories";
import type { Category } from "@/core/domain/models";
import { fr } from "@/i18n/fr";
import { getDb } from "@/data/db";
import {
  disableWebPushForEvent,
  enableWebPushForEvent,
  getWebPushPref,
  isWebPushSupported,
} from "@/sync/webPush";
import { CategoryEditorDialog } from "../components/CategoryEditorDialog";
import { ConfirmDialog } from "../components/ConfirmDialog";

/**
 * Per-event settings. For now hosts the categories CRUD (extracted
 * from EventDetailScreen's tabs). Future event-scoped knobs (currency,
 * default split, etc.) will land here so the main detail view stays
 * focused on expenses / balances / participants.
 */
export function EventSettingsScreen() {
  const { eventId = "" } = useParams<{ eventId: string }>();
  const categories = useLiveQuery(
    () => listCategories(eventId),
    [eventId],
    [] as Category[],
  );

  const [editing, setEditing] = useState<Category | null | "new">(null);
  const [removing, setRemoving] = useState<Category | null>(null);

  const custom = categories.filter((c) => !c.isDefault);

  return (
    <Stack spacing={2}>
      <PushNotificationsSection eventId={eventId} />

      <Typography variant="h6">{fr.eventSettings.categoriesSection}</Typography>

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
          await upsertCustomCategory(eventId, {
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

/**
 * Toggle for Web Push notifications, scoped to the current event.
 * Loads the persisted opt-in once on mount, then drives `enable/
 * disableWebPushForEvent`. We surface a single reason string when the
 * subscribe attempt fails (no VAPID key, permission denied, etc.) so
 * the user understands why the switch flipped back to off.
 */
function PushNotificationsSection({ eventId }: { eventId: string }) {
  const supported = isWebPushSupported();
  const [enabled, setEnabled] = useState<boolean | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    void getWebPushPref(eventId).then((v) => {
      if (!cancelled) setEnabled(v);
    });
    return () => {
      cancelled = true;
    };
  }, [eventId]);

  const toggle = async (next: boolean) => {
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      const secret = await getDb().eventSecrets.get(eventId);
      if (!secret) {
        setError(fr.eventSettings.pushUnavailable);
        return;
      }
      if (next) {
        const res = await enableWebPushForEvent(eventId, secret.bearer);
        if (!res.enabled) {
          if (res.reason === "permission_denied") {
            setError(fr.eventSettings.pushPermissionDenied);
          } else if (res.reason === "no_vapid_key") {
            setError(fr.eventSettings.pushUnavailable);
          } else if (res.reason === "unsupported") {
            setError(fr.eventSettings.pushUnsupported);
          } else {
            setError(fr.eventSettings.pushUnavailable);
          }
          setEnabled(false);
          return;
        }
        setEnabled(true);
      } else {
        await disableWebPushForEvent(eventId, secret.bearer);
        setEnabled(false);
      }
    } finally {
      setBusy(false);
    }
  };

  return (
    <Stack spacing={1}>
      <Typography variant="h6">
        {fr.eventSettings.notificationsSection}
      </Typography>
      {!supported && (
        <Alert severity="info">{fr.eventSettings.pushUnsupported}</Alert>
      )}
      <FormControlLabel
        control={
          <Switch
            checked={enabled === true}
            disabled={!supported || busy || enabled === null}
            onChange={(_, v) => void toggle(v)}
          />
        }
        label={
          <Stack>
            <Typography>{fr.eventSettings.pushEnable}</Typography>
            <Typography variant="caption" color="text.secondary">
              {fr.eventSettings.pushEnableDescription}
            </Typography>
          </Stack>
        }
      />
      {error && <Alert severity="warning">{error}</Alert>}
    </Stack>
  );
}
