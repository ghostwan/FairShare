import { useEffect, useState } from "react";
import {
  Box,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Stack,
  TextField,
  Typography,
} from "@mui/material";
import { fr } from "@/i18n/fr";
import { argbToCssHex } from "../format";
import type { Category } from "@/core/domain/models";

/**
 * Editor for a custom category. Used to create (when `initial` is
 * null) or rename an existing one. Emoji is just a TextField — picking
 * from the system keyboard's emoji panel is the simplest cross-device
 * UX and avoids shipping a 200kb emoji picker library.
 */

const PALETTE_ARGB: number[] = [
  0xffef5350, 0xffec407a, 0xffab47bc, 0xff7e57c2,
  0xff5c6bc0, 0xff42a5f5, 0xff26c6da, 0xff26a69a,
  0xff66bb6a, 0xff9ccc65, 0xffd4e157, 0xffffee58,
  0xffffa726, 0xffff7043, 0xff8d6e63, 0xff78909c,
];

export interface CategoryEditorValue {
  name: string;
  emoji: string;
  color: number;
}

export function CategoryEditorDialog(props: {
  open: boolean;
  initial: Category | null;
  onCancel: () => void;
  onConfirm: (value: CategoryEditorValue) => void | Promise<void>;
}) {
  const [name, setName] = useState("");
  const [emoji, setEmoji] = useState("📦");
  const [color, setColor] = useState<number>(PALETTE_ARGB[0]!);

  useEffect(() => {
    if (!props.open) return;
    if (props.initial) {
      setName(props.initial.name);
      setEmoji(props.initial.emoji);
      setColor(props.initial.color);
    } else {
      setName("");
      setEmoji("📦");
      setColor(PALETTE_ARGB[0]!);
    }
  }, [props.open, props.initial]);

  const submit = async () => {
    const trimmed = name.trim();
    if (trimmed.length === 0) return;
    await props.onConfirm({ name: trimmed, emoji: emoji.trim() || "📦", color });
  };

  return (
    <Dialog open={props.open} onClose={props.onCancel} fullWidth>
      <DialogTitle>
        {props.initial ? fr.expenses.edit : fr.categories.add}
      </DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          <TextField
            label={fr.categories.name}
            value={name}
            onChange={(e) => setName(e.target.value)}
            autoFocus
            fullWidth
          />
          <TextField
            label={fr.categories.emoji}
            value={emoji}
            onChange={(e) => setEmoji(e.target.value)}
            inputProps={{ maxLength: 4 }}
            sx={{ width: 120 }}
          />
          <Typography variant="overline" color="text.secondary">
            {fr.categories.color}
          </Typography>
          <Box
            sx={{
              display: "grid",
              gridTemplateColumns: "repeat(8, 1fr)",
              gap: 1,
            }}
          >
            {PALETTE_ARGB.map((argb) => {
              const hex = argbToCssHex(argb);
              const selected = argb === color;
              return (
                <Box
                  key={argb}
                  onClick={() => setColor(argb)}
                  sx={{
                    width: 32,
                    height: 32,
                    borderRadius: "50%",
                    bgcolor: hex,
                    cursor: "pointer",
                    border: selected ? "3px solid #000" : "1px solid #ccc",
                    boxSizing: "border-box",
                  }}
                />
              );
            })}
          </Box>
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={props.onCancel}>{fr.common.cancel}</Button>
        <Button variant="contained" onClick={submit}>
          {fr.expenses.save}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
