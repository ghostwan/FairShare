import { useEffect, useRef, useState } from "react";
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  CircularProgress,
  IconButton,
  List,
  ListItem,
  ListItemText,
  MenuItem,
  Stack,
  TextField,
  Typography,
} from "@mui/material";
import DeleteIcon from "@mui/icons-material/Delete";
import { useNavigate, useParams } from "react-router-dom";
import { useLiveQuery } from "dexie-react-hooks";
import { fr } from "@/i18n/fr";
import { getDb } from "@/data/db";
import { Settings } from "@/data/settings";
import { upsertExpense } from "@/data/repositories";
import type { Expense, ExpenseItem, Participant } from "@/core/domain/models";
import { assignReceiptItems } from "@/core/domain/receiptAssign";
import { formatMoneyCents } from "../format";

/**
 * Receipt scan flow: snap a photo (or pick a file), POST it base64-
 * encoded to Gemini, render the parsed line items so the user can
 * tweak, then save as a regular expense whose `items` carry the
 * receipt detail.
 *
 * Mirrors the Android `GeminiReceiptParser` request shape so the same
 * key works for both clients — same model id, same prompt, same JSON
 * schema, same response-mime-type contract.
 */

interface ParsedItem {
  id: string;
  label: string;
  quantity: number;
  priceCents: number;
  assignedTo: string[];
}

interface ParsedReceipt {
  merchant: string | null;
  items: ParsedItem[];
}

const PROMPT = `
Tu reçois la photo d'un ticket de caisse (français, devise EUR).
Identifie le nom du commerçant/restaurant imprimé en en-tête du ticket et renvoie-le
sous la clé "merchant" (string en Title Case, ou null si illisible).
Extrais STRICTEMENT la liste des articles consommés (pas les sous-totaux,
totaux, taxes, remises, services, infos établissement, dates ou numéros de table).
Pour chaque article, renvoie :
  - "label"      : nom court de l'article tel qu'imprimé
  - "quantity"   : nombre d'unités (entier >= 1, défaut 1 si non précisé)
  - "priceCents" : prix TOTAL de la ligne en centimes d'euro (entier).
                   Exemple : "2 x Bière 11,00" -> priceCents=1100, quantity=2.
                   Exemple : "Plat du jour 14,50" -> priceCents=1450, quantity=1.
Réponds UNIQUEMENT avec un objet JSON conforme au schéma {merchant, items}, sans texte autour.
`;

export function ReceiptScanScreen() {
  const { eventId = "" } = useParams<{ eventId: string }>();
  const navigate = useNavigate();
  const fileInputRef = useRef<HTMLInputElement | null>(null);

  const [apiKey, setApiKey] = useState<string | null>(null);
  const [model, setModel] = useState("gemini-2.5-flash");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [parsed, setParsed] = useState<ParsedReceipt | null>(null);
  const [merchant, setMerchant] = useState("");
  const [payerId, setPayerId] = useState("");

  const participants = useLiveQuery(
    () => getDb().participants.where("eventId").equals(eventId).sortBy("name"),
    [eventId],
    [],
  );

  useEffect(() => {
    void (async () => {
      const k = await Settings.getGeminiApiKey();
      setApiKey(k.length > 0 ? k : null);
      setModel(await Settings.getGeminiModel());
    })();
  }, []);

  useEffect(() => {
    if (payerId === "" && participants.length > 0) setPayerId(participants[0]!.id);
  }, [participants, payerId]);

  const onFile = async (file: File) => {
    if (apiKey == null) {
      setError(fr.receipt.needsKey);
      return;
    }
    setBusy(true);
    setError(null);
    setParsed(null);
    try {
      const base64 = await fileToBase64(file);
      const result = await callGemini(apiKey, model, base64, file.type);
      setParsed(result);
      setMerchant(result.merchant ?? "");
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  const totalCents = (parsed?.items ?? []).reduce((s, it) => s + it.priceCents, 0);

  const toggleAssignee = (itemId: string, participantId: string) => {
    if (!parsed) return;
    setParsed({
      ...parsed,
      items: parsed.items.map((it) => {
        if (it.id !== itemId) return it;
        const has = it.assignedTo.includes(participantId);
        return {
          ...it,
          assignedTo: has
            ? it.assignedTo.filter((p) => p !== participantId)
            : [...it.assignedTo, participantId],
        };
      }),
    });
  };

  const save = async () => {
    if (!parsed || parsed.items.length === 0 || participants.length === 0) return;
    const ids = participants.map((p) => p.id);
    const itemModels: ExpenseItem[] = parsed.items.map((it) => ({
      id: it.id,
      label: it.label,
      priceCents: it.priceCents,
      quantity: it.quantity,
      assignedTo: it.assignedTo,
    }));
    const shares = assignReceiptItems(itemModels, ids);
    const exp: Expense = {
      id: "",
      eventId,
      title: merchant.trim() || "Ticket",
      amountCents: totalCents,
      payerId,
      date: Date.now(),
      shares,
      items: itemModels,
      isSettlement: false,
      categoryId: null,
    };
    try {
      await upsertExpense(exp);
      navigate(`/event/${eventId}`, { replace: true });
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  return (
    <Stack spacing={2}>
      <Typography variant="h6">{fr.receipt.scan}</Typography>

      {apiKey == null && <Alert severity="warning">{fr.receipt.needsKey}</Alert>}

      <input
        ref={fileInputRef}
        type="file"
        accept="image/*"
        capture="environment"
        hidden
        onChange={(e) => {
          const f = e.target.files?.[0];
          if (f) void onFile(f);
          e.target.value = "";
        }}
      />
      <Stack direction="row" spacing={1}>
        <Button
          variant="contained"
          onClick={() => fileInputRef.current?.click()}
          disabled={busy || apiKey == null}
          fullWidth
        >
          {fr.receipt.snap}
        </Button>
      </Stack>

      {busy && (
        <Stack direction="row" spacing={1} alignItems="center">
          <CircularProgress size={20} />
          <Typography>{fr.receipt.analyzing}</Typography>
        </Stack>
      )}

      {error && <Alert severity="error">{error}</Alert>}

      {parsed && (
        <Stack spacing={1}>
          <TextField
            label="Commerçant"
            value={merchant}
            onChange={(e) => setMerchant(e.target.value)}
            fullWidth
          />
          <TextField
            select
            label={fr.expenses.payer}
            value={payerId}
            onChange={(e) => setPayerId(e.target.value)}
            fullWidth
            disabled={participants.length === 0}
          >
            {participants.map((p) => (
              <MenuItem key={p.id} value={p.id}>
                {p.name}
              </MenuItem>
            ))}
          </TextField>
          <Typography variant="overline" color="text.secondary">
            Articles ({parsed.items.length}) — total{" "}
            {formatMoneyCents(totalCents)}
          </Typography>
          <Stack spacing={1}>
            {parsed.items.map((it, i) => (
              <ItemCard
                key={it.id}
                item={it}
                participants={participants}
                onLabelChange={(label) =>
                  setParsed({
                    ...parsed,
                    items: parsed.items.map((x, j) =>
                      j === i ? { ...x, label } : x,
                    ),
                  })
                }
                onPriceChange={(priceCents) =>
                  setParsed({
                    ...parsed,
                    items: parsed.items.map((x, j) =>
                      j === i ? { ...x, priceCents } : x,
                    ),
                  })
                }
                onToggle={(pid) => toggleAssignee(it.id, pid)}
                onDelete={() =>
                  setParsed({
                    ...parsed,
                    items: parsed.items.filter((_, j) => j !== i),
                  })
                }
              />
            ))}
          </Stack>
          <PerPersonSummary
            items={parsed.items.map((it) => ({
              id: it.id,
              label: it.label,
              priceCents: it.priceCents,
              quantity: it.quantity,
              assignedTo: it.assignedTo,
            }))}
            participants={participants}
          />
          <Box>
            <Button
              variant="contained"
              onClick={save}
              disabled={parsed.items.length === 0 || participants.length === 0}
              fullWidth
            >
              {fr.expenses.save}
            </Button>
          </Box>
        </Stack>
      )}
    </Stack>
  );
}

/**
 * Editable card per receipt line: label + price text fields, filter
 * chips to toggle the assignee set, helper line that previews either
 * "X / personne" (item is assigned) or "réparti équitablement entre
 * tous" (fallback). Same UX as Android's ScanReceiptScreen.
 */
function ItemCard(props: {
  item: ParsedItem;
  participants: Participant[];
  onLabelChange: (label: string) => void;
  onPriceChange: (priceCents: number) => void;
  onToggle: (participantId: string) => void;
  onDelete: () => void;
}) {
  const { item } = props;
  const [priceText, setPriceText] = useState(
    (item.priceCents / 100).toFixed(2).replace(".", ","),
  );

  // Re-sync if the parent rewrites the price (e.g. on deletion of
  // sibling items causing keys to shuffle). Cheap: just on item id.
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
              → {formatMoneyCents(perPerson)} / personne
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

/** Bottom summary card showing the final per-person breakdown. */
function PerPersonSummary(props: {
  items: ExpenseItem[];
  participants: Participant[];
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
                {formatMoneyCents(byId.get(p.id) ?? 0)}
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

async function fileToBase64(file: File): Promise<string> {
  const buf = await file.arrayBuffer();
  const bytes = new Uint8Array(buf);
  let bin = "";
  const CHUNK = 0x8000;
  for (let i = 0; i < bytes.length; i += CHUNK) {
    bin += String.fromCharCode(...bytes.subarray(i, i + CHUNK));
  }
  return btoa(bin);
}

async function callGemini(
  apiKey: string,
  model: string,
  base64: string,
  mimeType: string,
): Promise<ParsedReceipt> {
  const url =
    `https://generativelanguage.googleapis.com/v1beta/models/${model}` +
    `:generateContent?key=${apiKey}`;
  const body = {
    contents: [
      {
        parts: [
          { text: PROMPT },
          { inline_data: { mime_type: mimeType || "image/jpeg", data: base64 } },
        ],
      },
    ],
    generationConfig: {
      responseMimeType: "application/json",
      responseSchema: {
        type: "OBJECT",
        required: ["items"],
        properties: {
          merchant: { type: "STRING" },
          items: {
            type: "ARRAY",
            items: {
              type: "OBJECT",
              required: ["label", "quantity", "priceCents"],
              properties: {
                label: { type: "STRING" },
                quantity: { type: "INTEGER" },
                priceCents: { type: "INTEGER" },
              },
            },
          },
        },
      },
    },
  };
  const resp = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!resp.ok) {
    const errText = await resp.text();
    throw new Error(`Gemini ${resp.status}: ${errText.slice(0, 200)}`);
  }
  const root = await resp.json();
  const text: string | undefined =
    root?.candidates?.[0]?.content?.parts?.[0]?.text;
  if (typeof text !== "string") throw new Error("Réponse Gemini vide");
  const parsed = JSON.parse(text);
  const arr: any[] = Array.isArray(parsed) ? parsed : parsed.items ?? parsed.receipt ?? [];
  const merchant: string | null =
    typeof parsed?.merchant === "string" ? parsed.merchant.trim() || null : null;
  const items: ParsedItem[] = arr
    .map((el): ParsedItem | null => {
      const label = typeof el.label === "string" ? el.label.trim() : "";
      const quantity = Number(el.quantity);
      const priceCents = Number(el.priceCents);
      if (!label || !Number.isFinite(priceCents) || priceCents <= 0) return null;
      return {
        id: crypto.randomUUID(),
        label,
        quantity: Number.isFinite(quantity) && quantity > 0 ? Math.floor(quantity) : 1,
        priceCents: Math.round(priceCents),
        assignedTo: [],
      };
    })
    .filter((x): x is ParsedItem => x != null);
  return { merchant, items };
}

