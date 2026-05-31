#!/bin/sh
# Parent preprocessor for Marked 2.
# Passes markdown through the D2 preprocessor then the Mermaid preprocessor in sequence.
#
# Usage: process-markdown.sh [-i input.md] [-o output.md] [-v]
#   -i  Input markdown file (default: stdin)
#   -o  Output file (default: stdout)
#   -v  Verbose mode (forwarded to both child preprocessors)
#
# Preprocessors (resolved relative to this script):
#   markdown-d2-preprocessor/process-d2-markdown.sc
#   markdown-mermaid-preprocessor/process-mermaid-markdown.sc

export PATH="/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin:$PATH"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
D2_SCRIPT="$SCRIPT_DIR/markdown-d2-preprocessor/process-d2-markdown.sc"
MERMAID_SCRIPT="$SCRIPT_DIR/markdown-mermaid-preprocessor/process-mermaid-markdown.sc"

# Validate that both preprocessors exist before doing any work
if [ ! -f "$D2_SCRIPT" ]; then
  echo "[process-markdown] ERROR: D2 preprocessor not found at: $D2_SCRIPT" >&2
  exit 1
fi
if [ ! -f "$MERMAID_SCRIPT" ]; then
  echo "[process-markdown] ERROR: Mermaid preprocessor not found at: $MERMAID_SCRIPT" >&2
  exit 1
fi

INPUT_FILE=""
OUTPUT_FILE=""
VERBOSE=""

while [ $# -gt 0 ]; do
  case "$1" in
    -i) INPUT_FILE="$2"; shift 2 ;;
    -o) OUTPUT_FILE="$2"; shift 2 ;;
    -v) VERBOSE="-v"; shift ;;
    *)  shift ;;
  esac
done

# Intermediate file: holds D2-processed output before the mermaid pass
TMP_FILE="$(mktemp /tmp/process-markdown-XXXXXX.md)"
trap 'rm -f "$TMP_FILE"' EXIT INT TERM

# ── Stage 1: D2 preprocessor ─────────────────────────────────────────────────
if [ -n "$VERBOSE" ]; then
  echo "[process-markdown] Stage 1: D2 preprocessor ($D2_SCRIPT)" >&2
fi

if [ -n "$INPUT_FILE" ]; then
  scala-cli --power shebang "$D2_SCRIPT" -i "$INPUT_FILE" -o "$TMP_FILE" $VERBOSE
else
  scala-cli --power shebang "$D2_SCRIPT" -o "$TMP_FILE" $VERBOSE
fi

D2_EXIT=$?
if [ $D2_EXIT -ne 0 ]; then
  echo "[process-markdown] D2 preprocessor failed (exit $D2_EXIT)" >&2
  exit $D2_EXIT
fi

# ── Stage 2: Mermaid preprocessor ────────────────────────────────────────────
if [ -n "$VERBOSE" ]; then
  echo "[process-markdown] Stage 2: Mermaid preprocessor ($MERMAID_SCRIPT)" >&2
fi

if [ -n "$OUTPUT_FILE" ]; then
  scala-cli --power shebang "$MERMAID_SCRIPT" -i "$TMP_FILE" -o "$OUTPUT_FILE" $VERBOSE
else
  scala-cli --power shebang "$MERMAID_SCRIPT" -i "$TMP_FILE" $VERBOSE
fi

MERMAID_EXIT=$?
if [ $MERMAID_EXIT -ne 0 ]; then
  echo "[process-markdown] Mermaid preprocessor failed (exit $MERMAID_EXIT)" >&2
  exit $MERMAID_EXIT
fi

if [ -n "$VERBOSE" ]; then
  echo "[process-markdown] Done" >&2
fi
