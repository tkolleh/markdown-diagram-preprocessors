#!/bin/sh
# Fixture-based test suite for the markup diagram preprocessors.
#
# Assumes the diagram renderers `d2` and `mmdc` are installed and on PATH;
# assertions check for real <svg output, not error fallbacks.
#
# Usage: tests/run.sh        (run from anywhere; paths resolve to repo root)
# Exit:  0 if all assertions pass, 1 otherwise.

export PATH="/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin:$PATH"

TESTS_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$TESTS_DIR/.." && pwd)"
FIXTURES="$TESTS_DIR/fixtures"

D2="$ROOT_DIR/d2-preprocessor/process-d2.sc"
MERMAID="$ROOT_DIR/mermaid-preprocessor/process-mermaid.sc"
ORCH="$ROOT_DIR/process-markup.sh"

PASS=0
FAIL=0

# assert_eq <description> <expected> <actual>
assert_eq() {
  if [ "$2" = "$3" ]; then
    PASS=$((PASS + 1))
    printf '  ok   %s\n' "$1"
  else
    FAIL=$((FAIL + 1))
    printf '  FAIL %s (expected=%s actual=%s)\n' "$1" "$2" "$3"
  fi
}

# count <pattern> <file>  — number of lines matching a fixed string.
# `grep -c` prints exactly one integer (incl. 0) and exits 1 on no match, so we
# capture stdout and default only when the capture is empty (file unreadable).
count()    { c="$(grep -c "$1" "$2" 2>/dev/null)";  echo "${c:-0}"; }
# count_re <ere> <file>
count_re() { c="$(grep -cE "$1" "$2" 2>/dev/null)"; echo "${c:-0}"; }

TMP="$(mktemp -d /tmp/markup-tests-XXXXXX)"
trap 'rm -rf "$TMP"' EXIT INT TERM

# ── Precondition: renderers present ──────────────────────────────────────────
command -v d2   >/dev/null 2>&1 || { echo "ERROR: d2 not on PATH"   >&2; exit 2; }
command -v mmdc >/dev/null 2>&1 || { echo "ERROR: mmdc not on PATH" >&2; exit 2; }
command -v rg   >/dev/null 2>&1 || { echo "ERROR: rg not on PATH"   >&2; exit 2; }

echo "== D2 preprocessor: Markdown =="
"$D2" -i "$FIXTURES/markdown-mixed.md" >"$TMP/md-d2.html" 2>/dev/null
assert_eq "two d2 diagrams rendered"          2 "$(count 'class="d2-diagram"' "$TMP/md-d2.html")"
assert_eq "mermaid block left untouched"      1 "$(count_re '^```mermaid' "$TMP/md-d2.html")"
assert_eq "no leftover d2 fences"             0 "$(count_re '^```d2' "$TMP/md-d2.html")"
assert_eq "real svg present"                  1 "$([ "$(count '<svg' "$TMP/md-d2.html")" -ge 1 ] && echo 1 || echo 0)"

echo "== Mermaid preprocessor: Markdown =="
"$MERMAID" -i "$FIXTURES/markdown-mixed.md" >"$TMP/md-mm.html" 2>/dev/null
assert_eq "one mermaid diagram rendered"      1 "$(count 'class="mermaid-diagram"' "$TMP/md-mm.html")"
assert_eq "d2 blocks left untouched"          2 "$(count_re '^```d2' "$TMP/md-mm.html")"
assert_eq "no leftover mermaid fences"        0 "$(count_re '^```mermaid' "$TMP/md-mm.html")"

echo "== D2 preprocessor: AsciiDoc (both fence forms) =="
"$D2" -i "$FIXTURES/asciidoc-mixed.adoc" -f adoc >"$TMP/adoc-d2.html" 2>/dev/null
assert_eq "[d2] + [source,d2] both rendered"  2 "$(count 'class="d2-diagram"' "$TMP/adoc-d2.html")"
assert_eq "no leftover d2 fences"             0 "$(count_re '^\[(source,)?\s*d2\]' "$TMP/adoc-d2.html")"
assert_eq "mermaid fences left untouched"     2 "$(count_re '^\[(source,)?\s*mermaid\]' "$TMP/adoc-d2.html")"

echo "== Mermaid preprocessor: AsciiDoc (both fence forms) =="
"$MERMAID" -i "$FIXTURES/asciidoc-mixed.adoc" -f adoc >"$TMP/adoc-mm.html" 2>/dev/null
assert_eq "[mermaid] + [source,mermaid] both" 2 "$(count 'class="mermaid-diagram"' "$TMP/adoc-mm.html")"
assert_eq "no leftover mermaid fences"        0 "$(count_re '^\[(source,)?\s*mermaid\]' "$TMP/adoc-mm.html")"

echo "== Orchestrator: AsciiDoc end-to-end =="
"$ORCH" -i "$FIXTURES/asciidoc-mixed.adoc" -f adoc >"$TMP/adoc-orch.html" 2>/dev/null
assert_eq "orchestrator exit code"            0 "$?"
assert_eq "two d2 diagrams"                   2 "$(count 'class="d2-diagram"' "$TMP/adoc-orch.html")"
assert_eq "two mermaid diagrams"              2 "$(count 'class="mermaid-diagram"' "$TMP/adoc-orch.html")"
assert_eq "zero leftover fences"              0 "$(count_re '^\[(source,)?\s*(d2|mermaid)\]' "$TMP/adoc-orch.html")"

echo "== Passthrough invariant: no-diagram doc unchanged =="
"$ORCH" -i "$FIXTURES/no-diagrams.md" >"$TMP/passthrough.html" 2>/dev/null
if diff -q "$FIXTURES/no-diagrams.md" "$TMP/passthrough.html" >/dev/null 2>&1; then
  assert_eq "document byte-identical" yes yes
else
  assert_eq "document byte-identical" yes no
fi

echo "== Guard: invalid format fails fast =="
"$ORCH" -i "$FIXTURES/no-diagrams.md" -f rst >/dev/null 2>&1
assert_eq "invalid format exits non-zero"     1 "$?"

echo ""
echo "Passed: $PASS  Failed: $FAIL"
[ "$FAIL" -eq 0 ]
