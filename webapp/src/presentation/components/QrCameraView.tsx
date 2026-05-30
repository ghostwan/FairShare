import { useEffect, useState } from "react";
import { Alert, Button, Stack, Typography } from "@mui/material";
import jsQR from "jsqr";
import { fr } from "@/i18n/fr";

/**
 * Reusable inline QR camera. Acquires `getUserMedia(environment)`, samples
 * ~10 fps off a hidden canvas, and surfaces the first decoded payload
 * accepted by [accept]. Designed to be embedded inline (no full-screen
 * takeover) so it composes cleanly inside forms and dialogs.
 *
 * On unmount or successful scan, the underlying MediaStream tracks are
 * stopped so the camera LED turns off.
 */
export function QrCameraView(props: {
  accept: (raw: string) => boolean;
  onScan: (raw: string) => void;
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
        videoEl = document.getElementById(
          "fairshare-qr-video",
        ) as HTMLVideoElement | null;
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
            if (code && code.data && props.accept(code.data)) {
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
        id="fairshare-qr-video"
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
