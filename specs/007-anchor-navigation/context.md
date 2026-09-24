---
spec: 007-anchor-navigation
created: 2026-09-24T03:58:00Z
updated: 2026-09-24T03:58:00Z
rounds: 1
---

<!-- CONTEXT LOG — a record, not an authority. Nothing here gates anything and nothing
     reads it back. Every answer that changes the build lives in spec.md or plan.md. -->

# Context 007 — Anchor navigation on the long screens

## Goal — confirmed 2026-09-24T03:50:00Z

Analytics, sources, review and rules each get a left margin column with in-page anchor links to
their sections, sticky under the header, marking the section in view, collapsing to a chip row
under the page header on a phone, coloured by the screen's section colour and by nothing else,
through one shared component the split views never render.

Principal's words, verbatim: "und die Seiten "Analytics Sources Review Rules" sollten alle eine
linke maginal spalte mit anker-navigation erhalten, zur besseren übersicht und handling"

## Round 1 — no gaps the repo could not close, 2026-09-24

The request named the four screens and the placement. Read from the repo: analytics has five
`h2` panels (intake, runs, market, applications, scores), rules four (knockouts, thresholds,
weights, prompts), sources one title with a table and a panel that opens beside it, review a
queue pane beside a document; the sections of the last two are the panel's and the document's
own headings and are declared per screen rather than guessed here. `--lg-sticky-top` already
exists for the pinned list column, which is what the rail pins to; `--lg-section` is what the
active marker reads, and ISC-230 caps its readers at three stylesheets, so the fourth is added on
purpose. The shortlist and the pipeline are split views with their own columns and get no rail.

## Round 1b — draft marks, 2026-09-24

Marks in `spec.md`: the column width (`--lg-anchor-w`, 11rem); the mobile form (a chip row rather
than a select); focus landing on the heading. None changes the build structurally.

## Still open

Nothing. The spec has no fog.
