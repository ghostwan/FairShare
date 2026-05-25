import { useEffect, useState } from "react";
import {
  Alert,
  Button,
  Snackbar,
  Stack,
  TextField,
  Typography,
} from "@mui/material";
import { useNavigate } from "react-router-dom";
import jsQR from "jsqr";
import { fr } from "@/i18n/fr";
import {
  decodeInvitation,
  InvitationDecodeException,
} from "@/core/invitation/codec";
import { bootstrapFromInvitation } from "@/sync/coordinator";
import { WorkerTransportError } from "@/core/sync/transport";

/**
 * Join an existing event from another device's invitation. Three paths:
 *
 *   1. **Auto-bootstrap from URL**: when the user lands on
 *      `/join?event=…&key=…` (the case for any QR scanned by an
 *      external camera app on iOS / Android that opens the link in
 *      Safari/Chrome), we read `window.location.href` on mount and
 *      run the join flow immediately. No tap required.
 *
 *   2. QR scan via `getUserMedia` + jsQR for in-app scanning. We keep
 *      the camera live and sample frames at ~10fps off a hidden
 *      canvas; the first decoded payload that parses as a valid
 *      `fairshare://join` (or the https mirror) wins.
 *
 *   3. Manual paste — covers cases where the auto-bootstrap couldn't
 *      reach the URL (someone forwarded the link as plain text, or
 *      the user navigated here manually).
 */
export function JoinScreen() {
  const navigate = useNavigate();
  const [pasted, setPasted] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [scanning, setScanning] = useState(false);
  const [autoTried, setAutoTried] = useState(false);

  const submit = async (url: string) => {
    setBusy(true);
    setError(null);
    try {
      const inv = decodeInvitation(url.trim());
      await bootstrapFromInvitation(inv.eventId, inv.eventKey);
      navigate(`/event/${inv.eventId}`, { replace: true });
    } catch (e) {
      if (e instanceof InvitationDecodeException) {
        setError(fr.join.invalid);
      } else if (e instanceof WorkerTransportError) {
        // Surface the underlying transport failure so the user knows
        // whether it's a network issue, a CORS preflight reject, or
        // a 401 (bearer mismatch — would mean the URL key was
        // tampered with or the inviter never pushed).
        setError(`Sync: ${e.message}`);
      } else {
        setError(`${fr.common.error}: ${(e as Error).message ?? e}`);
      }
    } finally {
      setBusy(false);
    }
  };

  // Auto-bootstrap when the user lands here from an external scan of
  // the inviter's QR (URL already carries event + key). We use the
  // current location.href so the codec sees the canonical form.
  useEffect(() => {
    if (autoTried) return;
    setAutoTried(true);
    if (typeof window === "undefined") return;
    const search = window.location.search;
    if (!search || !search.includes("event=") || !search.includes("key=")) {
      return;
    }
    void submit(window.location.href);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <Stack spacing={2}>
      <Typography variant="h6">{fr.join.heading}</Typography>

      {scanning ? (
        <QrCameraView
          onCancel={() => setScanning(false)}
          onScan={async (url) => {
            setScanning(false);
            await submit(url);
          }}
        />
      ) : (
        <Button
          variant="contained"
          onClick={() => setScanning(true)}
          disabled={busy}
        >
          {fr.join.scan}
        </Button>
      )}

      <Typography variant="subtitle2">{fr.join.paste}</Typography>
      <Typography variant="caption" color="text.secondary">
        {fr.join.pasteHint}
      </Typography>
      <TextField
        value={pasted}
        onChange={(e) => setPasted(e.target.value)}
        placeholder="https://fairshare-web-bdg.pages.dev/join?event=…"
        multiline
        minRows={2}
        fullWidth
      />
      <Button
        variant="outlined"
        onClick={() => void submit(pasted)}
        disabled={busy || pasted.trim().length === 0}
      >
        {fr.join.submit}
      </Button>

      <Snackbar
        open={error != null}
        onClose={() => setError(null)}
      >
        <Alert
          severity="error"
          onClose={() => setError(null)}
          sx={{ maxWidth: 360, whiteSpace: "pre-wrap", wordBreak: "break-word" }}
        >
          {error}
        </Alert>
      </Snackbar>
    </Stack>
  );
}

function QrCameraView(props: {
  onScan: (url: string) => void;
  onCancel: () => void;
}) {
  const [stream, setStream] = useState<MediaStream | null>(null);
  const [denied, setDenied] = useState(false);

  useEffect(() => {
    let cancelled = false;
    let videoEl: HTMLVideoElement | null = null;
    let raf = 0;

    void (async () => {
      try {
        const s = await navigator.mediaDevices.getUserMedia({
          video: { facingMode: { ideal: "environment" } },
          audio: false,
        });
        if (cancelled) {
          s.getTracks().forEach((t) => t.stop());
          return;
        }
        setStream(s);
        videoEl = document.getElementById("qr-video") as HTMLVideoElement;
        if (!videoEl) return;
        videoEl.srcObject = s;
        await videoEl.play();

        const canvas = document.createElement("canvas");
        const ctx = canvas.getContext("2d", { willReadFrequently: true });
        if (!ctx) return;

        const tick = () => {
          if (cancelled || !videoEl) return;
          if (videoEl.readyState >= 2) {
            canvas.width = videoEl.videoWidth;
            canvas.height = videoEl.videoHeight;
            ctx.drawImage(videoEl, 0, 0, canvas.width, canvas.height);
            const img = ctx.getImageData(0, 0, canvas.width, canvas.height);
            const code = jsQR(img.data, img.width, img.height, {
              inversionAttempts: "dontInvert",
            });
            if (code && code.data) {
              props.onScan(code.data);
              return;
            }
          }
          raf = window.setTimeout(tick, 100) as unknown as number;
        };
        tick();
      } catch {
        setDenied(true);
      }
    })();

    return () => {
      cancelled = true;
      if (raf) clearTimeout(raf);
      if (stream) stream.getTracks().forEach((t) => t.stop());
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  if (denied) {
    return (
      <Stack spacing={1}>
        <Alert severity="warning">{fr.qr.cameraDenied}</Alert>
        <Button onClick={props.onCancel}>{fr.common.close}</Button>
      </Stack>
    );
  }

  return (
    <Stack spacing={1} alignItems="stretch">
      <video
        id="qr-video"
        playsInline
        muted
        style={{ width: "100%", borderRadius: 8, background: "#000" }}
      />
      <Typography variant="caption" color="text.secondary" align="center">
        {fr.qr.point}
      </Typography>
      <Button onClick={props.onCancel}>{fr.common.cancel}</Button>
    </Stack>
  );
}
