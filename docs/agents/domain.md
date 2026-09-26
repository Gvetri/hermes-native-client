# Domain Docs

How engineering skills should consume this repo's domain documentation.

## Before exploring, read these

- **`CONTEXT.md`** at the repo root, if it exists.
- **`docs/adr/`**: read ADRs relevant to the area you're working in, if they exist.

If these files don't exist, proceed silently. Don't flag their absence or suggest creating them upfront.

## File structure

This repo uses a single-context layout:

```text
/
├── CONTEXT.md
├── docs/
│   ├── agents/
│   └── adr/
└── src/
```

## Use the glossary's vocabulary

Use domain terms as defined in `CONTEXT.md`. If a concept you need is missing, reconsider the terminology or note the gap.

## Flag ADR conflicts

If your output contradicts an existing ADR, surface that explicitly rather than silently overriding it.
