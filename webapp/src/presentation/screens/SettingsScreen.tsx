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
import QrCodeIcon from "@mui/icons-material/QrCode2";
import QrCodeScannerIcon from "@mui/icons-material/QrCodeScanner";
import { fr } from "@/i18n/fr";
import { DEFAULTS, Settings } from "@/data/settings";
import { ShareGeminiKeyDialog } from "../components/ShareGeminiKeyDialog";
import { QrCameraView } from "../components/QrCameraView";
import {
  decodeGeminiKey,
  isGeminiKeyUrl,
} from "@/core/settings/geminiKeyCodec";

const VERSION = "0.1.0";

/**
 * Settings: Worker URL, Gemini key + model, auto-refresh toggle, install
 * helper. Reads from the Settings store on mount and writes on save —
 * we don't write per-field because IndexedDB writes from typing would
 * be noisy and there's no harm in batching.
 *
 * The Gemini key can also be shared/imported via QR using the same
 * `fairshare://gemini?key=…&model=…` payload as the Android app, so a
 * user with the app on a phone can transfer their key to the webapp
 * (and vice-versa) without typing.
 */
export function SettingsScreen() {
  const [cloudBaseUrl, setCloudBaseUrl] = useState(DEFAULTS.cloudBaseUrl);
  const [geminiApiKey, setGeminiApiKey] = useState("");
  const [geminiModel, setGeminiModel] = useState(DEFAULTS.geminiModel);
  const [autoRefresh, setAutoRefresh] = useState(true);
  const [saved, setSaved] = useState(false);
  const [showShare, setShowShare] = useState(false);
  const [scanning, setScanning] = useState(false);
  const [importMsg, setImportMsg] = useState<{
    severity: "success" | "error";
    text: string;
  } | null>(null);

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

  const onScanned = async (raw: string) => {
    try {
      const decoded = decodeGeminiKey(raw);
      await Settings.setGeminiApiKey(decoded.key);
      if (decoded.model) await Settings.setGeminiModel(decoded.model);
      setGeminiApiKey(decoded.key);
      if (decoded.model) setGeminiModel(decoded.model);
      const preview =
        decoded.key.length > 8
          ? `${decoded.key.slice(0, 4)}…${decoded.key.slice(-4)} (${decoded.key.length} car.)`
          : `(${decoded.key.length} car.)`;
      setImportMsg({
        severity: "success",
        text: `${fr.settings.geminiKeyImported} — ${preview}`,
      });
    } catch (e) {
      setImportMsg({
        severity: "error",
        text: `${fr.settings.geminiKeyImportFailed}: ${(e as Error).message}`,
      });
    } finally {
      setScanning(false);
    }
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
      <Stack direction="row" spacing={1}>
        <Button
          variant="outlined"
          startIcon={<QrCodeIcon />}
          onClick={() => {
            if (!geminiApiKey.trim()) {
              setImportMsg({
                severity: "error",
                text: fr.settings.geminiKeyMissing,
              });
              return;
            }
            setShowShare(true);
          }}
          fullWidth
        >
          {fr.settings.shareGeminiKey}
        </Button>
        <Button
          variant="outlined"
          startIcon={<QrCodeScannerIcon />}
          onClick={() => setScanning((v) => !v)}
          fullWidth
        >
          {fr.settings.scanGeminiKey}
        </Button>
      </Stack>
      {scanning && (
        <QrCameraView
          accept={isGeminiKeyUrl}
          onScan={(raw) => void onScanned(raw)}
          onCancel={() => setScanning(false)}
        />
      )}
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

      <ShareGeminiKeyDialog
        open={showShare}
        apiKey={geminiApiKey.trim()}
        model={geminiModel.trim() || null}
        onClose={() => setShowShare(false)}
      />

      <Snackbar
        open={saved}
        autoHideDuration={2000}
        onClose={() => setSaved(false)}
        message={fr.settings.saved}
      />
      <Snackbar
        open={importMsg != null}
        autoHideDuration={4000}
        onClose={() => setImportMsg(null)}
      >
        <Alert
          severity={importMsg?.severity ?? "info"}
          onClose={() => setImportMsg(null)}
          sx={{ whiteSpace: "pre-wrap" }}
        >
          {importMsg?.text}
        </Alert>
      </Snackbar>
    </Stack>
  );
}
