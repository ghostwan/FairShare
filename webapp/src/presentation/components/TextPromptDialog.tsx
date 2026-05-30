import { useEffect, useState } from "react";
import {
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  TextField,
} from "@mui/material";
import { fr } from "@/i18n/fr";

/**
 * Single-line text prompt. Used by the participants tab to add /
 * rename people, and any other "ask the user for a label" flow.
 *
 * Re-syncs the local input state from `initialValue` whenever the
 * dialog reopens — otherwise the second open would still hold the
 * previous edit.
 */
export function TextPromptDialog(props: {
  open: boolean;
  title: string;
  label: string;
  initialValue?: string;
  confirmLabel?: string;
  onCancel: () => void;
  onConfirm: (value: string) => void | Promise<void>;
}) {
  const [value, setValue] = useState(props.initialValue ?? "");

  useEffect(() => {
    if (props.open) setValue(props.initialValue ?? "");
  }, [props.open, props.initialValue]);

  const submit = async () => {
    const trimmed = value.trim();
    if (trimmed.length === 0) return;
    await props.onConfirm(trimmed);
  };

  return (
    <Dialog open={props.open} onClose={props.onCancel} fullWidth>
      <DialogTitle>{props.title}</DialogTitle>
      <DialogContent>
        <TextField
          autoFocus
          fullWidth
          label={props.label}
          value={value}
          onChange={(e) => setValue(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter") void submit();
          }}
          sx={{ mt: 1 }}
        />
      </DialogContent>
      <DialogActions>
        <Button onClick={props.onCancel}>{fr.common.cancel}</Button>
        <Button variant="contained" onClick={submit}>
          {props.confirmLabel ?? fr.common.confirm}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
