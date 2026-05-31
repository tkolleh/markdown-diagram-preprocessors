#!/bin/sh
# Parent preprocessor for Marked 2 and similar markup preview tools.
# Passes a markup document through the D2 preprocessor then the Mermaid
# preprocessor in sequence. Supports Markdown and AsciiDoc.
#
# Usage: process-markup.sh [-i input] [-o output] [-f md|adoc] [-v]
#   -i  Input markup file (default: stdin)
#   -o  Output file (default: stdout)
#   -f  Markup format: md (default) or adoc (forwarded to both child preprocessors)
#   -v  Verbose mode (forwarded to both child preprocessors)
#
# Preprocessors (resolved relative to this script):
#   d2-preprocessor/process-d2.sc
#   mermaid-preprocessor/process-mermaid.sc

export PATH="/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin:$PATH"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
D2_SCRIPT="$SCRIPT_DIR/d2-preprocessor/process-d2.sc"
MERMAID_SCRIPT="$SCRIPT_DIR/mermaid-preprocessor/process-mermaid.sc"

# Validate that both preprocessors exist before doing any work
if [ ! -f "$D2_SCRIPT" ]; then
  echo "[process-markup] ERROR: D2 preprocessor not found at: $D2_SCRIPT" >&2
  exit 1
fi
if [ ! -f "$MERMAID_SCRIPT" ]; then
  echo "[process-markup] ERROR: Mermaid preprocessor not found at: $MERMAID_SCRIPT" >&2
  exit 1
fi

INPUT_FILE=""
OUTPUT_FILE=""
FORMAT="md"
VERBOSE=""

while [ $# -gt 0 ]; do
  case "$1" in
    -i) INPUT_FILE="$2"; shift 2 ;;
    -o) OUTPUT_FILE="$2"; shift 2 ;;
    -f) FORMAT="$2"; shift 2 ;;
    -v) VERBOSE="-v"; shift ;;
    *)  shift ;;
  esac
done

# Validate format up front so a typo fails fast rather than silently treating
# the document as Markdown.
case "$FORMAT" in
  md|adoc) ;;
  *) echo "[process-markup] ERROR: invalid format '$FORMAT' (expected md or adoc)" >&2; exit 1 ;;
esac

# Intermediate file: holds D2-processed output before the mermaid pass.
# Suffix tracks the format for clarity when inspecting during -v runs.
TMP_FILE="$(mktemp "/tmp/process-markup-XXXXXX.$FORMAT")"
trap 'rm -f "$TMP_FILE"' EXIT INT TERM

# ── Stage 1: D2 preprocessor ─────────────────────────────────────────────────
if [ -n "$VERBOSE" ]; then
  echo "[process-markup] Stage 1: D2 preprocessor ($D2_SCRIPT) [format=$FORMAT]" >&2
fi

if [ -n "$INPUT_FILE" ]; then
  scala-cli --power shebang "$D2_SCRIPT" -i "$INPUT_FILE" -o "$TMP_FILE" -f "$FORMAT" $VERBOSE
else
  scala-cli --power shebang "$D2_SCRIPT" -o "$TMP_FILE" -f "$FORMAT" $VERBOSE
fi

D2_EXIT=$?
if [ $D2_EXIT -ne 0 ]; then
  echo "[process-markup] D2 preprocessor failed (exit $D2_EXIT)" >&2
  exit $D2_EXIT
fi

# ── Stage 2: Mermaid preprocessor ────────────────────────────────────────────
if [ -n "$VERBOSE" ]; then
  echo "[process-markup] Stage 2: Mermaid preprocessor ($MERMAID_SCRIPT) [format=$FORMAT]" >&2
fi

if [ -n "$OUTPUT_FILE" ]; then
  scala-cli --power shebang "$MERMAID_SCRIPT" -i "$TMP_FILE" -o "$OUTPUT_FILE" -f "$FORMAT" $VERBOSE
else
  scala-cli --power shebang "$MERMAID_SCRIPT" -i "$TMP_FILE" -f "$FORMAT" $VERBOSE
fi

MERMAID_EXIT=$?
if [ $MERMAID_EXIT -ne 0 ]; then
  echo "[process-markup] Mermaid preprocessor failed (exit $MERMAID_EXIT)" >&2
  exit $MERMAID_EXIT
fi

if [ -n "$VERBOSE" ]; then
  echo "[process-markup] Done" >&2
fi
