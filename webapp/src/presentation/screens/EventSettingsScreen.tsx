import { useState } from "react";
import {
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
  setEventGiftModeEnabled,
  upsertCustomCategory,
} from "@/data/repositories";
import { getDb } from "@/data/db";
import { DEFAULT_CATEGORIES } from "@/core/domain/defaultCategories";
import type { Category } from "@/core/domain/models";
import { fr } from "@/i18n/fr";
import { CategoryEditorDialog } from "../components/CategoryEditorDialog";
import { ConfirmDialog } from "../components/ConfirmDialog";

/**
 * Per-event settings. Currently hosts the categories CRUD (extracted
 * from EventDetailScreen's tabs) and the gift-mode toggle. Push
 * notifications used to live here too, but the toggle moved to global
 * Settings — one subscription covers every paired event.
 */
export function EventSettingsScreen() {
  const { eventId = "" } = useParams<{ eventId: string }>();
  const event = useLiveQuery(() => getDb().events.get(eventId), [eventId]);
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
      <Typography variant="h6">{fr.eventSettings.giftSection}</Typography>
      <Stack spacing={0.5}>
        <FormControlLabel
          control={
            <Switch
              checked={event?.giftModeEnabled ?? true}
              disabled={event == null}
              onChange={(e) =>
                void setEventGiftModeEnabled(eventId, e.target.checked)
              }
            />
          }
          label={fr.events.giftMode}
        />
        <Typography variant="caption" color="text.secondary">
          {fr.events.giftModeHint}
        </Typography>
      </Stack>

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
