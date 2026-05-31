# Markdown Mixed

Intro paragraph that should pass through verbatim.

```d2
x -> y
y -> z
```

Some prose between diagrams.

```mermaid
graph TD
  A --> B
```

A second D2 block to stress the nearest-line-number matcher:

```d2
a -> b
```

Trailing text with an inline `code span` that must not be touched.
