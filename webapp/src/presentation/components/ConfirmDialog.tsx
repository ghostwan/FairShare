import {
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogContentText,
  DialogTitle,
} from "@mui/material";
import { fr } from "@/i18n/fr";

/**
 * Yes/No modal. Default destructive styling on the confirm action
 * because the only callers today are deletions; pass `destructive=false`
 * if the dialog gains a non-destructive use later.
 */
export function ConfirmDialog(props: {
  open: boolean;
  title: string;
  message?: string;
  confirmLabel?: string;
  cancelLabel?: string;
  destructive?: boolean;
  onCancel: () => void;
  onConfirm: () => void | Promise<void>;
}) {
  const destructive = props.destructive ?? true;
  return (
    <Dialog open={props.open} onClose={props.onCancel}>
      <DialogTitle>{props.title}</DialogTitle>
      {props.message && (
        <DialogContent>
          <DialogContentText>{props.message}</DialogContentText>
        </DialogContent>
      )}
      <DialogActions>
        <Button onClick={props.onCancel}>
          {props.cancelLabel ?? fr.common.cancel}
        </Button>
        <Button
          color={destructive ? "error" : "primary"}
          variant="contained"
          onClick={() => void props.onConfirm()}
        >
          {props.confirmLabel ?? fr.common.confirm}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
