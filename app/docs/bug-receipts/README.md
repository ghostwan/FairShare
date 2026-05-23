# Receipt bug reports

When the scanner produces a wrong result, drop your case here so the bug
becomes reproducible as a unit test.

## What to send

For each bug, create a subfolder `bug-XX-short-name/` containing:

1. **`receipt.jpg`** (or `.png`) — the photo you scanned. Crop / blur any
   personal info if you want.
2. **`ocr.log`** — the raw ML Kit dump captured from logcat (see below).
3. **`expected.md`** — a short description: what was wrong, what you
   expected. Lines merged? Missing item? Wrong price? Wrong quantity?

## How to capture the OCR dump

The debug build of the parser dumps every OCR token in logcat under the
tag `ReceiptOCR` (one element per line, format `text|cx|cy|height`).

```bash
# 1. Make sure you run a debug build
./run.sh debug

# 2. In another terminal, clear and follow the log
adb logcat -c
adb logcat -s ReceiptOCR:V > app/docs/bug-receipts/bug-XX-name/ocr.log &

# 3. Scan the problematic ticket in the app, then Ctrl-C the logcat
```

The dump starts with `=== BEGIN receipt dump (N tokens) ===` and ends
with `=== END receipt dump ===`. Keep everything between these markers.

## Then

Commit the folder and push. From the dump alone we can build a JVM unit
test that reproduces the bug without ML Kit, fix the parser, and keep
the test as a non-regression check.
