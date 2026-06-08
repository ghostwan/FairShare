import { useEffect, useState } from "react";
import {
  Box,
  Button,
  Card,
  CardActionArea,
  CardContent,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControlLabel,
  IconButton,
  Menu,
  MenuItem,
  Stack,
  Switch,
  TextField,
  Typography,
} from "@mui/material";
import AddIcon from "@mui/icons-material/Add";
import MoreVertIcon from "@mui/icons-material/MoreVert";
import QrCodeScannerIcon from "@mui/icons-material/QrCodeScanner";
import { useNavigate } from "react-router-dom";
import { useLiveQuery } from "dexie-react-hooks";
import { getDb } from "@/data/db";
import {
  createEvent,
  deleteEventLocally,
  setEventArchived,
} from "@/data/repositories";
import { fr } from "@/i18n/fr";
import { APP_VERSION, formatBuildTime } from "@/buildInfo";
import { ConfirmDialog } from "../components/ConfirmDialog";
import type { Event } from "@/core/domain/models";

export function EventsListScreen() {
  const navigate = useNavigate();
  const allEvents = useLiveQuery(
    () => getDb().events.orderBy("createdAt").reverse().toArray(),
    [],
    [],
  );
  const [creating, setCreating] = useState(false);
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [giftModeEnabled, setGiftModeEnabled] = useState(true);
  const [showArchived, setShowArchived] = useState(false);
  const [menuFor, setMenuFor] = useState<{
    event: Event;
    anchor: HTMLElement;
  } | null>(null);
  const [confirmDelete, setConfirmDelete] = useState<Event | null>(null);

  useEffect(() => {
    /* noop */
  }, []);

  const onSubmit = async () => {
    const trimmed = name.trim();
    if (trimmed.length === 0) return;
    const evt = await createEvent(
      trimmed,
      description.trim() || undefined,
      giftModeEnabled,
    );
    setCreating(false);
    setName("");
    setDescription("");
    setGiftModeEnabled(true);
    navigate(`/event/${evt.id}`);
  };

  if (allEvents == null) return null;

  const visible = allEvents.filter((e) => !!e.archived === showArchived);
  const hasArchived = allEvents.some((e) => e.archived);

  return (
    <Stack spacing={2} sx={{ flex: 1 }}>
      <Stack direction="row" spacing={1}>
        <Button
          variant="contained"
          startIcon={<AddIcon />}
          onClick={() => setCreating(true)}
          fullWidth
        >
          {fr.events.create}
        </Button>
        <Button
          variant="outlined"
          startIcon={<QrCodeScannerIcon />}
          onClick={() => navigate("/join")}
        >
          {fr.events.join}
        </Button>
      </Stack>

      {hasArchived && (
        <FormControlLabel
          control={
            <Switch
              checked={showArchived}
              onChange={(e) => setShowArchived(e.target.checked)}
            />
          }
          label={fr.events.archived}
        />
      )}

      {visible.length === 0 && (
        <Typography color="text.secondary">{fr.events.empty}</Typography>
      )}

      {visible.map((e) => (
        <Card key={e.id} variant="outlined">
          <Stack direction="row" alignItems="center">
            <CardActionArea
              onClick={() => navigate(`/event/${e.id}`)}
              sx={{ flex: 1 }}
            >
              <CardContent>
                <Typography variant="h6">{e.name}</Typography>
                {e.description && (
                  <Typography variant="body2" color="text.secondary">
                    {e.description}
                  </Typography>
                )}
              </CardContent>
            </CardActionArea>
            <IconButton
              onClick={(ev) => {
                ev.stopPropagation();
                setMenuFor({ event: e, anchor: ev.currentTarget });
              }}
              aria-label="more"
              sx={{ mr: 0.5 }}
            >
              <MoreVertIcon />
            </IconButton>
          </Stack>
        </Card>
      ))}

      <Menu
        anchorEl={menuFor?.anchor ?? null}
        open={menuFor != null}
        onClose={() => setMenuFor(null)}
      >
        {menuFor && !menuFor.event.archived && (
          <MenuItem
            onClick={async () => {
              const id = menuFor.event.id;
              setMenuFor(null);
              await setEventArchived(id, true);
            }}
          >
            {fr.events.archive}
          </MenuItem>
        )}
        {menuFor && menuFor.event.archived && (
          <MenuItem
            onClick={async () => {
              const id = menuFor.event.id;
              setMenuFor(null);
              await setEventArchived(id, false);
            }}
          >
            {fr.events.unarchive}
          </MenuItem>
        )}
        <MenuItem
          onClick={() => {
            if (menuFor) setConfirmDelete(menuFor.event);
            setMenuFor(null);
          }}
        >
          {fr.events.deleteLocal}
        </MenuItem>
      </Menu>

      <ConfirmDialog
        open={confirmDelete != null}
        title={fr.events.confirmDeleteLocal}
        confirmLabel={fr.events.deleteLocal}
        onCancel={() => setConfirmDelete(null)}
        onConfirm={async () => {
          if (confirmDelete) await deleteEventLocally(confirmDelete.id);
          setConfirmDelete(null);
        }}
      />

      <Dialog open={creating} onClose={() => setCreating(false)} fullWidth>
        <DialogTitle>{fr.events.create}</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            <TextField
              label={fr.events.createPrompt}
              value={name}
              onChange={(e) => setName(e.target.value)}
              autoFocus
            />
            <TextField
              label={fr.events.descriptionOptional}
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              multiline
              minRows={2}
            />
            <Stack spacing={0.5}>
              <FormControlLabel
                control={
                  <Switch
                    checked={giftModeEnabled}
                    onChange={(e) => setGiftModeEnabled(e.target.checked)}
                  />
                }
                label={fr.events.giftMode}
              />
              <Typography variant="caption" color="text.secondary">
                {fr.events.giftModeHint}
              </Typography>
            </Stack>
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setCreating(false)}>{fr.common.cancel}</Button>
          <Button variant="contained" onClick={onSubmit}>
            {fr.events.create}
          </Button>
        </DialogActions>
      </Dialog>

      <Box sx={{ position: "fixed", right: 16, bottom: 16 }}>
        <IconButton
          color="primary"
          size="large"
          sx={{ bgcolor: "primary.main", color: "white", boxShadow: 4 }}
          onClick={() => setCreating(true)}
          aria-label={fr.events.create}
        >
          <AddIcon />
        </IconButton>
      </Box>

      {/* Build identity, helpful for spotting whether the PWA picked
          up a fresh shell after a deploy. Padded so the FAB doesn't
          cover it on short event lists. */}
      <Box sx={{ flex: 1 }} />
      <Typography
        variant="caption"
        color="text.secondary"
        align="center"
        sx={{ pb: 1, pt: 2, opacity: 0.6 }}
      >
        v{APP_VERSION} · {formatBuildTime()}
      </Typography>
    </Stack>
  );
}
