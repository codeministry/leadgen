---
spec: 006-dashboard-control-room
created: 2026-09-24T03:55:00Z
updated: 2026-09-24T03:55:00Z
rounds: 1
---

<!-- CONTEXT LOG — a record, not an authority. Nothing here gates anything and nothing
     reads it back. Every answer that changes the build lives in spec.md or plan.md. -->

# Context 006 — The dashboard as a control room

## Goal — confirmed 2026-09-24T03:40:00Z

The dashboard becomes a bento control room: one hero cell with the survivor count in the signal,
the compact funnel rail and one call to action to the shortlist, four small cells around it (a
fourteen-day intake sparkline, the score distribution, follow-ups due, run health) fed by one slim
summary endpoint, the machine-room details collapsed underneath and opening on a failed run, every
label in the reader's words, all of it inside the renovated design system and above the fold at
1440×900.

Principal's words, verbatim: "das dashboard sieht zu technisch aus und sollte etwas "aufgepeppt"
werden, eine mischung aus markting und tech. frage dazu auch den design agenten"

## Round 1 — before the spec, 2026-09-24

The Designer agent was asked first, read-only, as the principal requested. Its memo diagnosed
why the screen reads as technical (the database's vocabulary, three large zeros with the same
weight as the one number that matters, a funnel drawn as a spreadsheet, nothing on the page that
leads to a survivor, machine-room text on the main surface) and offered three directions: A
"Morning Edition" (editorial hero plus the top three offers as cards), B "Launch Page" (a tall
hero and three feature cards), C "Bento Control Room" (an asymmetric grid: hero cell, sparkline,
score distribution, follow-ups, run health, machine room). It recommended A.

### Q1 · Which direction? (the goal lock)

- Offered: A Morning Edition (recommended by the Designer agent and by me) | B Launch Page |
  C Bento Control Room
- Chosen: C Bento Control Room
- Landed in: `## Goal`, ISC-265, ISC-267

### Q2 · Where do the small cells' numbers come from?

- Offered: a slim `GET /api/v1/analytics/summary` (recommended) | the dashboard reads the full
  `/analytics` | only cells from data the dashboard already has
- Chosen: the slim endpoint
- Landed in: ISC-266, `plan.md` § Interfaces

Read from the repo, not asked: `/offers/funnel` and `/ingest/last` already carry the hero's numbers
and the last run's health; the applications store carries follow-ups due; `analytics.intake.byIngestedAt`
and the score bands exist only through the full `/analytics` payload, which is why Q2 was a question;
`ISC-224` holds the signal allowlist at ten files, so the two new charts widen it on purpose; one
`btn-primary` per template is what the tier guard allows, and "Run ingest" lives in the header's
template, so the hero may carry one.

## Round 1b — draft marks, 2026-09-24

Marks in `spec.md`: the machine room closed by default; the hero's wording; whether the hero
compares to the previous run ("+6 vs yesterday" needs the runs series, which the summary does not
carry, so it is out unless asked). None changes the build structurally.

## Still open

Nothing. The spec has no fog.
