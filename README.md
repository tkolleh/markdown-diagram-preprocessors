# Markup Diagram Pre-processors

Render `d2` and `mermaid` labeled diagram blocks in **markup documents** into
inline SVG, so diagrams show up rendered in [Marked 2](https://marked2app.com/)
(or any tool that accepts a custom markup preprocessor). Both **Markdown** and
**AsciiDoc** are supported.

Each preprocessor scans a markup document for diagram blocks, shells out to the
corresponding renderer (`d2` or `mmdc`), and replaces each block with a centered
`<div>` containing the generated SVG. The rest of the document is left
untouched. Each script prepends the common Homebrew/system locations to `PATH`
in its shebang wrapper so it works when launched from markup preview apps such
as [Marked 2](https://marked2app.com/), not just an interactive shell.

## Supported markup & diagram blocks

| Format | Flag | D2 block | Mermaid block |
| --- | --- | --- | --- |
| Markdown | `-f md` (default) | ` ```d2 … ``` ` | ` ```mermaid … ``` ` |
| AsciiDoc | `-f adoc` | `[d2]` or `[source,d2]` + `---- … ----` | `[mermaid]` or `[source,mermaid]` + `---- … ----` |

For AsciiDoc, **both** the diagram-style (`[d2]`) and source-style
(`[source,d2]`) block headers are recognized.

## Components

| File | Purpose |
| --- | --- |
| `process-markup.sh` | Orchestrator. Pipes a document through the D2 preprocessor, then the Mermaid preprocessor, in sequence. Format-aware via `-f`. |
| `d2-preprocessor/process-d2.sc` | Replaces D2 blocks (Markdown or AsciiDoc) with SVG rendered by `d2`. |
| `mermaid-preprocessor/process-mermaid.sc` | Replaces Mermaid blocks (Markdown or AsciiDoc) with SVG rendered by `mmdc`, with `<script>` tags and XML declarations stripped from the output. |
| `tests/run.sh` | Fixture-based test suite (see [Testing](#testing)). |

> Pre-processors can also run standalone; the orchestrator simply chains them so
> a single document can contain both diagram types.

Each preprocessor selects which block syntax to scan for from a small
`FenceSpec` table keyed by `--format`, so the detection/replacement pipeline is
identical across formats — only the fence patterns differ.

## Prerequisites

- [`scala-cli`](https://scala-cli.virtuslab.org/) — runs the `.sc` scripts
- [`d2`](https://d2lang.com/) — for D2 diagrams (`d2` on `PATH`)
- [`@mermaid-js/mermaid-cli`](https://github.com/mermaid-js/mermaid-cli) — for Mermaid diagrams (`mmdc` on `PATH`)
- [`ripgrep`](https://github.com/BurntSushi/ripgrep) — `rg`, used to locate diagram blocks

```sh
brew install scala-cli d2 ripgrep
npm install -g @mermaid-js/mermaid-cli
```

## Usage

Run the full pipeline (D2 then Mermaid):

```sh
# Markdown (default format), file in / file out
./process-markup.sh -i input.md -o output.md

# AsciiDoc
./process-markup.sh -i input.adoc -o output.adoc -f adoc

# stdin to stdout
cat input.md | ./process-markup.sh

# Verbose (forwarded to both child preprocessors)
./process-markup.sh -i input.adoc -o output.html -f adoc -v
```

Run a single preprocessor on its own:

```sh
./d2-preprocessor/process-d2.sc -i input.md -o output.md
./mermaid-preprocessor/process-mermaid.sc -i input.adoc -f adoc
```

### AsciiDoc example

````asciidoc
[d2]
----
x -> y
----

[source,mermaid]
----
graph TD
  A --> B
----
````

Both blocks above are rendered to inline SVG; either AsciiDoc block-header form
works for either diagram type.

### Flags (shared by all three)

| Flag | Meaning |
| --- | --- |
| `-i <file>` | Input markup file (default: stdin) |
| `-o <file>` | Output file (default: stdout) |
| `-f <md\|adoc>` | Markup format (default: `md`) |
| `-v` | Verbose logging to stderr |

## Testing

```sh
./tests/run.sh
```

The suite is fixture-based (`tests/fixtures/`) and **assumes `d2`, `mmdc`, and
`rg` are installed** — it asserts on real `<svg>` output rather than degrading
gracefully. It covers both formats, both AsciiDoc fence forms, cross-engine
isolation, and the passthrough invariant (a document with no diagram blocks
emerges byte-for-byte unchanged). It exits non-zero if any assertion fails or a
renderer is missing.

## Use with Marked 2

In **Marked 2 → Preferences → Advanced → Custom Processor**, point the
preprocessor at `process-markup.sh` (use the absolute path). Marked 2 feeds the
document on stdin and reads the rendered HTML on stdout. To preview AsciiDoc,
configure the processor command with `-f adoc`.

If a diagram fails to render, the offending block is replaced with a visible red
error box containing the renderer's stderr, so the rest of the document still
previews.

## License

[EUPL-1.2](LICENSE) © TJ Kolleh
