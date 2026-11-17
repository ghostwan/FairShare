import { useEffect, useState } from "react";
import {
  Box,
  Button,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Snackbar,
  Stack,
  Typography,
} from "@mui/material";
import ContentCopyIcon from "@mui/icons-material/ContentCopy";
import QRCode from "qrcode";
import { fr } from "@/i18n/fr";
import { encodeGeminiKey } from "@/core/settings/geminiKeyCodec";

/**
 * Renders the user's Gemini key + model as a `fairshare://gemini?...`
 * QR. Same `<img>` (toDataURL) strategy as InviteScreen to avoid the
 * canvas / flexbox stretching issue. The plain key is also displayed
 * with a copy button so it can be sent to a friend through any
 * messaging app.
 */
export function ShareGeminiKeyDialog(props: {
  open: boolean;
  apiKey: string;
  model: string | null;
  onClose: () => void;
}) {
  const [dataUrl, setDataUrl] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    if (!props.open || !props.apiKey) {
      setDataUrl(null);
      return;
    }
    let cancelled = false;
    void (async () => {
      try {
        const url = encodeGeminiKey(props.apiKey, props.model);
        const d = await QRCode.toDataURL(url, {
          margin: 1,
          width: 512,
          errorCorrectionLevel: "M",
        });
        if (!cancelled) setDataUrl(d);
      } catch {
        if (!cancelled) setDataUrl(null);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [props.open, props.apiKey, props.model]);

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(props.apiKey);
      setCopied(true);
    } catch {
      // Clipboard can be denied (iOS PWA, insecure context); the key
      // stays selectable below.
    }
  };

  return (
    <Dialog open={props.open} onClose={props.onClose} fullWidth maxWidth="xs">
      <DialogTitle>{fr.settings.geminiQrTitle}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} alignItems="center">
          <Typography variant="body2" color="text.secondary" align="center">
            {fr.settings.geminiQrHint}
          </Typography>
          <Box
            sx={{
              width: "100%",
              maxWidth: 320,
              aspectRatio: "1 / 1",
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
              bgcolor: "#fff",
              border: "1px solid",
              borderColor: "divider",
              borderRadius: 1,
              overflow: "hidden",
            }}
          >
            {dataUrl ? (
              <img
                src={dataUrl}
                alt="Gemini key QR code"
                style={{ width: "100%", height: "100%", display: "block" }}
              />
            ) : (
              <CircularProgress />
            )}
          </Box>
          <Typography
            variant="body2"
            sx={{
              fontFamily: "monospace",
              wordBreak: "break-all",
              userSelect: "all",
              maxWidth: 320,
            }}
          >
            {props.apiKey}
          </Typography>
          {props.model && (
            <Typography variant="caption" color="text.secondary">
              {fr.settings.geminiModel}: {props.model}
            </Typography>
          )}
          <Button
            variant="outlined"
            startIcon={<ContentCopyIcon />}
            onClick={() => void copy()}
            disabled={!props.apiKey}
          >
            {fr.settings.geminiKeyCopy}
          </Button>
          <Typography variant="caption" color="text.secondary" align="center">
            {fr.settings.geminiKeyShareWarning}
          </Typography>
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={props.onClose}>{fr.common.close}</Button>
      </DialogActions>
      <Snackbar
        open={copied}
        autoHideDuration={2000}
        onClose={() => setCopied(false)}
        message={fr.settings.geminiKeyCopied}
      />
    </Dialog>
  );
}
