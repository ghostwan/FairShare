import { useEffect, useState } from "react";
import {
  Box,
  Button,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Stack,
  Typography,
} from "@mui/material";
import QRCode from "qrcode";
import { fr } from "@/i18n/fr";
import { encodeGeminiKey } from "@/core/settings/geminiKeyCodec";

/**
 * Renders the user's Gemini key + model as a `fairshare://gemini?...`
 * QR. Same `<img>` (toDataURL) strategy as InviteScreen to avoid the
 * canvas / flexbox stretching issue. The plain key is never displayed
 * — only a truncated preview — to discourage screenshots.
 */
export function ShareGeminiKeyDialog(props: {
  open: boolean;
  apiKey: string;
  model: string | null;
  onClose: () => void;
}) {
  const [dataUrl, setDataUrl] = useState<string | null>(null);

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

  const preview =
    props.apiKey.length > 8
      ? `${props.apiKey.slice(0, 4)}…${props.apiKey.slice(-4)} (${props.apiKey.length} car.)`
      : `(${props.apiKey.length} car.)`;

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
          <Typography variant="caption" color="text.secondary">
            {preview}
          </Typography>
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={props.onClose}>{fr.common.close}</Button>
      </DialogActions>
    </Dialog>
  );
}
