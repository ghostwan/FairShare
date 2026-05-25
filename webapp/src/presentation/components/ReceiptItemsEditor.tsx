import { useEffect, useState } from "react";
import {
  Box,
  Card,
  CardContent,
  Chip,
  IconButton,
  List,
  ListItem,
  ListItemText,
  Stack,
  TextField,
  Typography,
} from "@mui/material";
import DeleteIcon from "@mui/icons-material/Delete";
import AddIcon from "@mui/icons-material/Add";
import { Button } from "@mui/material";
import type { ExpenseItem, Participant } from "@/core/domain/models";
import { assignReceiptItems } from "@/core/domain/receiptAssign";
import { formatMoneyCents } from "../format";

/**
 * Stateless editor for a list of receipt items with per-item
 * participant assignments. Used both by the receipt scan flow (where
 * items are first populated by Gemini) and the expense edit flow
 * (where the items are loaded from the saved expense). The parent
 * owns the items array — this component never mutates state on its
 * own, just emits the next list through [onChange].
 *
 * Renders a `PerPersonSummary` card at the bottom recomputed live
 * from the current items, so users see exactly what each participant
 * will pay before saving.
 */
export function ReceiptItemsEditor(props: {
  items: ExpenseItem[];
  participants: Participant[];
  onChange: (items: ExpenseItem[]) => void;
  currency?: string;
  /** Show an "Add item" button at the bottom of the list. */
  allowAdd?: boolean;
}) {
  const { items, participants, onChange } = props;

  const updateItem = (index: number, patch: Partial<ExpenseItem>) =>
    onChange(items.map((it, i) => (i === index ? { ...it, ...patch } : it)));

  const removeItem = (index: number) =>
    onChange(items.filter((_, i) => i !== index));

  const toggleAssignee = (index: number, participantId: string) => {
    const it = items[index];
    if (!it) return;
    const has = it.assignedTo.includes(participantId);
    updateItem(index, {
      assignedTo: has
        ? it.assignedTo.filter((p) => p !== participantId)
        : [...it.assignedTo, participantId],
    });
  };

  const addItem = () =>
    onChange([
      ...items,
      {
        id: crypto.randomUUID(),
        label: "",
        priceCents: 0,
        quantity: 1,
        assignedTo: [],
      },
    ]);

  return (
    <Stack spacing={1}>
      {items.map((it, i) => (
        <ItemCard
          key={it.id}
          item={it}
          participants={participants}
          currency={props.currency}
          onLabelChange={(label) => updateItem(i, { label })}
          onPriceChange={(priceCents) => updateItem(i, { priceCents })}
          onToggle={(pid) => toggleAssignee(i, pid)}
          onDelete={() => removeItem(i)}
        />
      ))}
      {props.allowAdd && (
        <Button
          variant="outlined"
          size="small"
          startIcon={<AddIcon />}
          onClick={addItem}
          sx={{ alignSelf: "flex-start" }}
        >
          Ajouter un article
        </Button>
      )}
      <PerPersonSummary
        items={items}
        participants={participants}
        currency={props.currency}
      />
    </Stack>
  );
}

function ItemCard(props: {
  item: ExpenseItem;
  participants: Participant[];
  currency?: string;
  onLabelChange: (label: string) => void;
  onPriceChange: (priceCents: number) => void;
  onToggle: (participantId: string) => void;
  onDelete: () => void;
}) {
  const { item } = props;
  const [priceText, setPriceText] = useState(
    (item.priceCents / 100).toFixed(2).replace(".", ","),
  );

  // Re-sync display when the item identity changes (e.g. siblings
  // deleted, list reordered). Cheap diff on id, intentionally not on
  // priceCents — we want to preserve in-progress typing.
  useEffect(() => {
    setPriceText((item.priceCents / 100).toFixed(2).replace(".", ","));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [item.id]);

  const perPerson =
    item.assignedTo.length > 0 && item.priceCents > 0
      ? Math.floor(item.priceCents / item.assignedTo.length)
      : null;

  return (
    <Card variant="outlined">
      <CardContent>
        <Stack spacing={1}>
          <Stack direction="row" spacing={1} alignItems="center">
            {item.quantity > 1 && (
              <Chip size="small" label={`${item.quantity}×`} />
            )}
            <TextField
              size="small"
              label="Article"
              value={item.label}
              onChange={(e) => props.onLabelChange(e.target.value)}
              sx={{ flex: 1 }}
            />
            <TextField
              size="small"
              label="€"
              value={priceText}
              onChange={(e) => {
                setPriceText(e.target.value);
                const cents = parseAmountToCents(e.target.value);
                if (cents != null) props.onPriceChange(cents);
              }}
              sx={{ width: 96 }}
              inputProps={{ inputMode: "decimal" }}
            />
            <IconButton onClick={props.onDelete} aria-label="delete item">
              <DeleteIcon />
            </IconButton>
          </Stack>
          <Box sx={{ display: "flex", flexWrap: "wrap", gap: 0.5 }}>
            {props.participants.map((p) => {
              const selected = item.assignedTo.includes(p.id);
              return (
                <Chip
                  key={p.id}
                  label={p.name}
                  size="small"
                  variant={selected ? "filled" : "outlined"}
                  color={selected ? "primary" : "default"}
                  onClick={() => props.onToggle(p.id)}
                />
              );
            })}
          </Box>
          {perPerson != null && (
            <Typography variant="caption" color="primary">
              → {formatMoneyCents(perPerson, props.currency)} / personne
            </Typography>
          )}
          {item.assignedTo.length === 0 && (
            <Typography variant="caption" color="text.secondary">
              Non assigné → réparti équitablement entre tous
            </Typography>
          )}
        </Stack>
      </CardContent>
    </Card>
  );
}

function PerPersonSummary(props: {
  items: ExpenseItem[];
  participants: Participant[];
  currency?: string;
}) {
  if (props.items.length === 0 || props.participants.length === 0) return null;
  const shares = assignReceiptItems(
    props.items,
    props.participants.map((p) => p.id),
  );
  const byId = new Map(shares.map((s) => [s.participantId, s.amountCents]));
  return (
    <Card variant="outlined">
      <CardContent>
        <Typography variant="subtitle2" gutterBottom>
          Détail par personne
        </Typography>
        <List dense disablePadding>
          {props.participants.map((p) => (
            <ListItem key={p.id} disablePadding sx={{ py: 0.25 }}>
              <ListItemText
                primary={p.name}
                slotProps={{ primary: { variant: "body2" } }}
              />
              <Typography variant="body2">
                {formatMoneyCents(byId.get(p.id) ?? 0, props.currency)}
              </Typography>
            </ListItem>
          ))}
        </List>
      </CardContent>
    </Card>
  );
}

/** Accepts "12,34" / "12.34" / "12" — returns integer cents or null. */
function parseAmountToCents(raw: string): number | null {
  const cleaned = raw.trim().replace(/\s/g, "").replace(",", ".");
  if (cleaned.length === 0) return null;
  const n = Number(cleaned);
  if (!Number.isFinite(n) || n < 0) return null;
  return Math.round(n * 100);
}
