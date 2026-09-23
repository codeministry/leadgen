---
spec: 002-action-feedback-toasts
created: 2026-09-23T16:40:00Z
updated: 2026-09-23T17:05:00Z
rounds: 2
---

<!-- CONTEXT LOG — a record, not an authority. Nothing here gates anything and nothing
     reads it back. Every answer that changes the build lives in spec.md or plan.md. -->

# Context 002 — Feedback after an action, and for a run whoever started it

## Goal — confirmed 2026-09-23T16:44:00Z

Every write a person makes in the browser — an archive or a restore, a bulk archive, a status change, a rescore, a
manual document confirmed or rejected — and every ingest run that begins or ends, whoever started it, is confirmed by
exactly one short toast that names what happened, raised by one mechanism anchored in the shell; failures keep their
inline place, and a toast carries a link and never an undo.

Principal's words, verbatim: "Der User soll in der App Feedback über Aktionen oder Ereignisse erhalten"

The vision that came with them, verbatim: "Die App braucht noch Toasties, die an sinnvollen Stellen nach Aktionen oder
Ereignissen Meldungen ausgeben. Bsp.: Ingest-Lauf gestartet / beendet, Offer archiviert / reaktiviert, Statuswechsel
des Offers in der Pipeline". The three examples are examples; the confirmed goal reads them as such.

## Round 1 — before the spec, 2026-09-23

### Q1 · Which goal sentence? (the goal lock)

- Offered: every write plus every run, whoever started it (recommended) | only the three named events, successes,
  this session only | one channel for everything, inline errors migrated, plus a history in the header
- Chosen: every write plus every run, whoever started it
- Landed in: `## Goal`, ISC-206 … ISC-212

### Q2 · What happens to failures?

- Offered: failures stay inline, toasts for successes and runs (recommended) | failures also as a toast, inline kept |
  failures move into toasts, inline write errors removed
- Chosen: failures stay inline
- Landed in: ISC-213, `## Out of Scope`

### Q3 · Does a toast carry an action?

- Offered: no undo, a link to the thing it names (recommended) | undo on archive/restore only, as the honest
  counter-action | undo on archive and on status change
- Chosen: no undo, a link
- Landed in: ISC-218, `## Out of Scope`, spec `## Decisions`

Read from the repo, not asked: `RefreshStore` already raises `run-ended` from the heartbeat's null transition and
says a run this browser started fires both paths, so "one toast per run" is a deduplication claim rather than a new
signal (ISC-211, ISC-212); `IngestReport` and `LastRunView` both carry `finishedAt`, which is the key; the inline
error paragraphs all carry `role="alert"`, which is what ISC-213 counts; DaisyUI 5 ships `toast` and `alert` and
already gates the toast animation on `prefers-reduced-motion`; the nav rail is a fixed bottom bar below 48rem, so
the stack's position is a mark, not a question.

## Round 1b — draft marks, 2026-09-23

Four marks in `spec.md`, none asked, because none changes the build structurally: the duration and the cap, the
stack's position on narrow screens, whether the rescore toast earns its place beside a score that is already on
screen, and whether the manual inbox counts as a write. All four carry the reasoned default and are overwritten at
review.

## Still open

Nothing. The spec has no fog.

## Round 2 — before the plan, 2026-09-23

### Q1 · In which order is 002 built?

- Offered: mechanism plus the archive slice, verified in the browser, then the remaining mappings in parallel, then
  the run mappings with the dedupe, then motion and docs (recommended) | every mapping first, browser at the end |
  runs first, actions after
- Chosen: mechanism plus one slice, then broad
- Landed in: plan.md § Approach

Read from the repo, not asked: both catalogs hold 464 keys with no difference, so the parity spec covers the whole
catalog and clears G-FE-02 rather than proving one group; `frontend/CLAUDE.md` is 10,534 of 12,000 characters, so
one rule line fits; `applications.store.spec.ts` already runs fake timers with `TestBed.tick()`, which is the pattern
the lifetime specs copy; the repo has no `src/styles/motion.css`, so the plan creates it where DS-APP-07 puts motion
tokens instead of adding a duration to `tokens.css`.
