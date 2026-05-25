import { useEffect, useState } from "react";
import {
  Alert,
  Box,
  Button,
  CircularProgress,
  Snackbar,
  Stack,
  Typography,
} from "@mui/material";
import { useParams } from "react-router-dom";
import QRCode from "qrcode";
import { fr } from "@/i18n/fr";
import { buildInvitationForEvent } from "@/sync/coordinator";

/**
 * Render the invitation as a QR code + copyable link. The QR encodes
 * the https URL so iOS Camera app opens it natively; the link is
 * shown below so users can also send it through any messaging app.
 *
 * We render the QR into an `<img>` (via `toDataURL`) rather than a
 * `<canvas>` because the canvas element exposes its native rasterised
 * dimensions through its `width`/`height` attributes, which fight with
 * the CSS `aspectRatio: 1/1` of the parent in flex containers — the
 * result is the QR getting stretched vertically on mobile. An `<img>`
 * with `width: 100%, height: auto` defers all sizing to CSS and keeps
 * the QR a perfect square at any viewport width.
 */
export function InviteScreen() {
  const { eventId = "" } = useParams<{ eventId: string }>();
  const [url, setUrl] = useState<string | null>(null);
  const [dataUrl, setDataUrl] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const u = await buildInvitationForEvent(eventId);
        if (cancelled) return;
        setUrl(u);
        const d = await QRCode.toDataURL(u, {
          margin: 1,
          // Generous bitmap size so the QR stays sharp when CSS
          // upscales it on tablets / desktop.
          width: 512,
          errorCorrectionLevel: "M",
        });
        if (!cancelled) setDataUrl(d);
      } catch (e) {
        if (!cancelled) setError(e instanceof Error ? e.message : String(e));
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [eventId]);

  const copy = async () => {
    if (!url) return;
    try {
      await navigator.clipboard.writeText(url);
      setCopied(true);
    } catch {
      // Some iOS PWAs deny clipboard in non-secure contexts; users can
      // still long-press the link below.
    }
  };

  return (
    <Stack spacing={2} alignItems="center">
      <Typography variant="body2" color="text.secondary" align="center">
        {fr.events.inviteHint}
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
            alt="Invitation QR code"
            style={{ width: "100%", height: "100%", display: "block" }}
          />
        ) : (
          <CircularProgress />
        )}
      </Box>
      {url && (
        <Typography
          variant="caption"
          sx={{
            wordBreak: "break-all",
            fontFamily: "monospace",
            color: "text.secondary",
            maxWidth: 320,
          }}
        >
          {url}
        </Typography>
      )}
      <Button variant="outlined" onClick={copy} disabled={!url}>
        {fr.events.inviteCopyLink}
      </Button>
      <Snackbar
        open={copied}
        autoHideDuration={2000}
        onClose={() => setCopied(false)}
        message={fr.events.inviteCopied}
      />
      <Snackbar
        open={error != null}
        autoHideDuration={4000}
        onClose={() => setError(null)}
      >
        <Alert severity="error" onClose={() => setError(null)}>
          {error}
        </Alert>
      </Snackbar>
    </Stack>
  );
}
