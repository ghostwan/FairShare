import { useEffect, useRef, useState } from "react";
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
 * We re-render the QR every time we open the screen because the
 * embedded seed reflects the *current* op log — new participants /
 * expenses added by the inviter since the last open should land in
 * the joiner's bootstrap.
 */
export function InviteScreen() {
  const { eventId = "" } = useParams<{ eventId: string }>();
  const [url, setUrl] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const canvasRef = useRef<HTMLCanvasElement | null>(null);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const u = await buildInvitationForEvent(eventId);
        if (cancelled) return;
        setUrl(u);
        if (canvasRef.current) {
          await QRCode.toCanvas(canvasRef.current, u, {
            margin: 1,
            scale: 6,
            errorCorrectionLevel: "M",
          });
        }
      } catch (e) {
        setError(e instanceof Error ? e.message : String(e));
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
          bgcolor: "background.paper",
          border: "1px solid",
          borderColor: "divider",
          borderRadius: 1,
        }}
      >
        {url ? (
          <canvas ref={canvasRef} style={{ maxWidth: "100%", height: "auto" }} />
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
