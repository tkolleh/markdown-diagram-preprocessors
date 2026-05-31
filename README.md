# Markup Diagram Pre-processors

Render `d2` and `mermaid` labeled code blocks in markup into inline SVG, so diagrams show up rendered in [Marked 2](https://marked2app.com/) (or any tool that accepts a custom markup preprocessor).

Each preprocessor scans a markup document for diagram code fences, shells out to the corresponding renderer (`d2` or `mmdc`), and replaces each fence with a centered `<div>` containing the generated SVG. The original markup file is left untouched everywhere else. Each script prepends the common Homebrew/system locations needed to run to `PATH` in its shebang wrapper so it works when launched from markup preview apps such as [Marked 2](https://marked2app.com/), not just an interactive shell.
## Components

| File | Purpose |
| --- | --- |
| `process-markdown.sh` | Orchestrator. Pipes Markdown through the D2 preprocessor, then the Mermaid preprocessor, in sequence. |
| `markdown-d2-preprocessor/process-d2-markdown.sc` | Replaces ` ```d2 ` blocks with SVG rendered by `d2`. |
| `markdown-mermaid-preprocessor/process-mermaid-markdown.sc` | Replaces ` ```mermaid ` blocks with SVG rendered by `mmdc`, with `<script>` tags and XML declarations stripped from the output. |


> Pre-processors can also run standalone; the orchestrator simply chains them so a single document can contain both diagram types.

## Prerequisites

- [`scala-cli`](https://scala-cli.virtuslab.org/) — runs the `.sc` scripts
- [`d2`](https://d2lang.com/) — for D2 diagrams (`d2` on `PATH`)
- [`@mermaid-js/mermaid-cli`](https://github.com/mermaid-js/mermaid-cli) — for Mermaid diagrams (`mmdc` on `PATH`)
- [`ripgrep`](https://github.com/BurntSushi/ripgrep) — `rg`, used to locate code fences

```sh
brew install scala-cli d2 ripgrep
npm install -g @mermaid-js/mermaid-cli
```

## Usage

Run the full pipeline (D2 then Mermaid):

```sh
# File in, file out
./process-markdown.sh -i input.md -o output.md

# stdin to stdout
cat input.md | ./process-markdown.sh

# Verbose (forwarded to both child preprocessors)
./process-markdown.sh -i input.md -o output.md -v
```

Run a single preprocessor on its own:

```sh
./markdown-d2-preprocessor/process-d2-markdown.sc -i input.md -o output.md
./markdown-mermaid-preprocessor/process-mermaid-markdown.sc -i input.md
```

### Flags (shared by all three)

| Flag | Meaning |
| --- | --- |
| `-i <file>` | Input markup file (default: stdin) |
| `-o <file>` | Output file (default: stdout) |
| `-v` | Verbose logging to stderr |

## Use with Marked 2

In **Marked 2 → Preferences → Advanced → Custom Processor**, point the
preprocessor at `process-markdown.sh` (use the absolute path). Marked 2 feeds
the document on stdin and reads the rendered HTML on stdout.

If a diagram fails to render, the offending block is replaced with a visible
red error box containing the renderer's stderr, so the rest of the document
still previews.

## License

[EUPL-1.2](LICENSE) © TJ Kolleh
