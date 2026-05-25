import { useEffect, useState } from "react";
import {
  Alert,
  Button,
  FormControlLabel,
  Snackbar,
  Stack,
  Switch,
  TextField,
  Typography,
} from "@mui/material";
import { fr } from "@/i18n/fr";
import { DEFAULTS, Settings } from "@/data/settings";

const VERSION = "0.1.0";

/**
 * Settings: Worker URL, Gemini key + model, auto-refresh toggle, install
 * helper. Reads from the Settings store on mount and writes on save —
 * we don't write per-field because IndexedDB writes from typing would
 * be noisy and there's no harm in batching.
 */
export function SettingsScreen() {
  const [cloudBaseUrl, setCloudBaseUrl] = useState(DEFAULTS.cloudBaseUrl);
  const [geminiApiKey, setGeminiApiKey] = useState("");
  const [geminiModel, setGeminiModel] = useState(DEFAULTS.geminiModel);
  const [autoRefresh, setAutoRefresh] = useState(true);
  const [saved, setSaved] = useState(false);

  useEffect(() => {
    void (async () => {
      setCloudBaseUrl(await Settings.getCloudBaseUrl());
      setGeminiApiKey(await Settings.getGeminiApiKey());
      setGeminiModel(await Settings.getGeminiModel());
      setAutoRefresh(await Settings.getAutoRefreshOnFocus());
    })();
  }, []);

  const save = async () => {
    await Settings.setCloudBaseUrl(cloudBaseUrl.trim() || DEFAULTS.cloudBaseUrl);
    await Settings.setGeminiApiKey(geminiApiKey.trim());
    await Settings.setGeminiModel(geminiModel.trim() || DEFAULTS.geminiModel);
    await Settings.setAutoRefreshOnFocus(autoRefresh);
    setSaved(true);
  };

  return (
    <Stack spacing={2}>
      <TextField
        label={fr.settings.cloudBaseUrl}
        value={cloudBaseUrl}
        onChange={(e) => setCloudBaseUrl(e.target.value)}
        fullWidth
      />
      <TextField
        label={fr.settings.geminiKey}
        value={geminiApiKey}
        onChange={(e) => setGeminiApiKey(e.target.value)}
        helperText={fr.settings.geminiKeyHint}
        type="password"
        fullWidth
      />
      <TextField
        label={fr.settings.geminiModel}
        value={geminiModel}
        onChange={(e) => setGeminiModel(e.target.value)}
        fullWidth
      />
      <FormControlLabel
        control={
          <Switch
            checked={autoRefresh}
            onChange={(e) => setAutoRefresh(e.target.checked)}
          />
        }
        label={fr.settings.autoRefresh}
      />

      <Button variant="contained" onClick={save}>
        {fr.settings.save}
      </Button>

      <Alert severity="info">{fr.settings.installHint}</Alert>

      <Typography variant="overline" color="text.secondary">
        {fr.settings.about}
      </Typography>
      <Typography variant="body2" color="text.secondary">
        {fr.settings.version}: {VERSION}
      </Typography>

      <Snackbar
        open={saved}
        autoHideDuration={2000}
        onClose={() => setSaved(false)}
        message={fr.settings.saved}
      />
    </Stack>
  );
}
