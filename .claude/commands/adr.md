---
description: Create the next-numbered Architecture Decision Record and index it
argument-hint: <short decision title>
allowed-tools: Read, Write, Edit, Glob
---

Create a new ADR for: **$ARGUMENTS**

1. List `docs/adr/` to find the highest existing number. The next ADR is that plus one, zero-padded
   to four digits.
2. Derive the filename as `docs/adr/NNNN-<kebab-case-of-the-title>.md`.
3. Read `docs/adr/TEMPLATE.md` and write the new file from it, with:
   - the heading `# ADR-NNNN — <title, stated as a decision, imperative mood>`,
   - `**Status:** Proposed`,
   - `**Date:**` today's date in `YYYY-MM-DD`.
4. Fill in **Context**, **Decision**, **Consequences**, and **Alternatives considered** from the
   conversation so far. Where the conversation does not supply something, leave a clearly marked
   `TODO` rather than inventing content — a confident-sounding ADR built on guesses is worse than a
   visibly incomplete one.
5. Add a row to the index table in `docs/adr/README.md`, in numeric order, with status `Proposed`.
6. If this decision changes or replaces an existing one, say so: set the old ADR's status to
   `Superseded by [ADR-NNNN](NNNN-….md)` and reference it from the new one. **Never edit an accepted
   ADR into a different decision** — the record of what was believed, and why, is the point.
7. Report the path you created and summarise the decision in two or three sentences. Do not commit.

House rules for the content:

- **Consequences must include what gets worse.** An ADR listing only benefits is a sales pitch, not a
  record.
- **Alternatives must say what would have to change** for them to win later, when that is knowable.
- Mark anything unmeasured as *(unverified)* or *(measure)*. Do not present desk research as an
  observation.
- Cross-link related ADRs and the relevant document under `docs/`.
