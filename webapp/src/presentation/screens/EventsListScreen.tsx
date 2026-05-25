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
  IconButton,
  Stack,
  TextField,
  Typography,
} from "@mui/material";
import AddIcon from "@mui/icons-material/Add";
import QrCodeScannerIcon from "@mui/icons-material/QrCodeScanner";
import { useNavigate } from "react-router-dom";
import { useLiveQuery } from "dexie-react-hooks";
import { getDb } from "@/data/db";
import { createEvent } from "@/data/repositories";
import { fr } from "@/i18n/fr";

export function EventsListScreen() {
  const navigate = useNavigate();
  const events = useLiveQuery(
    () => getDb().events.orderBy("createdAt").reverse().toArray(),
    [],
    [],
  );
  const [creating, setCreating] = useState(false);
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");

  // Best-effort migration from a previously-installed PWA: nothing to
  // do today but the hook keeps the symmetry with Android's onStart.
  useEffect(() => {
    /* noop */
  }, []);

  const onSubmit = async () => {
    const trimmed = name.trim();
    if (trimmed.length === 0) return;
    const evt = await createEvent(trimmed, description.trim() || undefined);
    setCreating(false);
    setName("");
    setDescription("");
    navigate(`/event/${evt.id}`);
  };

  if (events == null) return null;

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

      {events.length === 0 && (
        <Typography color="text.secondary">{fr.events.empty}</Typography>
      )}

      {events.map((e) => (
        <Card key={e.id} variant="outlined">
          <CardActionArea onClick={() => navigate(`/event/${e.id}`)}>
            <CardContent>
              <Typography variant="h6">{e.name}</Typography>
              {e.description && (
                <Typography variant="body2" color="text.secondary">
                  {e.description}
                </Typography>
              )}
            </CardContent>
          </CardActionArea>
        </Card>
      ))}

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
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setCreating(false)}>{fr.common.cancel}</Button>
          <Button variant="contained" onClick={onSubmit}>
            {fr.events.create}
          </Button>
        </DialogActions>
      </Dialog>

      {/* FAB-ish floater for mobile; keeps the action thumb-reachable. */}
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
    </Stack>
  );
}
