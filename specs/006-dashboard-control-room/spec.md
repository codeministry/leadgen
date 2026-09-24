---
task: "The dashboard as a control room"
slug: 006-dashboard-control-room
spec_type: feature
isa_master: ../../ISA.md
isa_feature: F34
constitution: ../constitution.md
phase: complete
progress: 10/10
started: 2026-09-24T03:55:00Z
updated: 2026-09-24T00:40:00Z
principal_stated_goal: "das dashboard sieht zu technisch aus und sollte etwas \"aufgepeppt\" werden, eine mischung aus markting und tech. frage dazu auch den design agenten"
principal_stated_goal_source: prompt
principal_stated_goal_signal: 3
principal_stated_goal_locked: 2026-09-24T03:40:00Z
context_sufficient: true
interview_invoked: false
context_log: context.md
---

<!-- SPEC — a derived view of ../../ISA.md (feature F34). Claim IDs belong to the master.
     Sync: Skill("Spec", "sync 006-dashboard-control-room"). Never edit the master from this file.
     principal_stated_goal is the one German string in this folder: the format keeps the principal's
     words byte for byte, and they carry no value the constitution keeps out of specs/. -->

# 006 — The dashboard as a control room

## Problem

The dashboard speaks the database's language. Its tiles say "Extracted", "Written · 0 rows" and
"Every extracted offer was a new row"; its table has columns for documents, extracted, written and
announced, and names sources by their kebab-case ids. Three of the four tiles show a large zero on a
quiet morning, at the same size and weight as the one number the morning is about, which stands
third. The funnel is seven bars of one colour with "−0 (0.0 %)" beside them. Nothing on the screen
leads to a survivor, and the archive note, the model name and the stage timings sit on the main
surface. It is a control panel for the pipeline, not a screen for the person who runs it. The
operator's words: it looks too technical and wants a mix of marketing and tech.

## Vision

He opens the app at eight and one cell tells him the morning in one number: how many made it
through, in the signal, with the funnel as a single line under it and one button to the shortlist.
Around it, four small cells answer the next four questions without a click: what came in over the
last two weeks, how the scores fall, what is due today, whether the run was healthy. The screen has
the density of an instrument panel and the confidence of a landing page. The machine room is one
click away and opens itself only when something went wrong.

## Out of Scope

- **Offer cards on the dashboard** (direction A). Decided 2026-09-24: the operator chose C.
- **A comparison to the previous run** ("+6 vs yesterday"). It needs the runs series, which the
  summary endpoint does not carry; out unless asked.
- **Any change to the analytics screen**, the funnel endpoint or the ingest heartbeat.
- **A count-up animation.** A TypeScript timer with a duration outside `motion.css` is exactly what
  DS-APP-41 refuses.

## Constraints

- **The signal stays exclusive** (ISC-224): it appears on the hero figure, the shortlisted series of
  the sparkline and the strong band of the distribution, and nowhere else on the screen; the
  allowlist grows by the two new chart files and stays exact.
- **One `btn-primary` per template** (ISC-231's guard): the hero's is the dashboard's one.
- **`.lg-panel` is the cell surface**, so every cell carries the dashboard's section edge; a hero
  variant lives in `primitives.css`, and any wash is a `color-mix` token, never a literal.
- **Contrast is measured** in the browser tier for the hero and one cell of each kind, both themes.
- **The summary is one endpoint** with three groups; the dashboard never requests `/analytics`.
- **Honesty on a quiet morning**: nothing extracted is said in words, never as an old number.
- **Density**: the five cells stand above the fold at 1440×900; below 48rem they stack.

## Goal

The dashboard becomes a bento control room: one hero cell with the survivor count in the signal,
the compact funnel rail and one call to action to the shortlist, four small cells around it (a
fourteen-day intake sparkline, the score distribution, follow-ups due, run health) fed by one slim
summary endpoint, the machine-room details collapsed underneath and opening on a failed run, every
label in the reader's words, all of it inside the renovated design system and above the fold at
1440×900.

## Features

### F34 · The dashboard as a control room

**Why:** The dashboard speaks the database's language — "extracted", "written · 0 rows", source ids
in kebab-case — gives three large zeros the same weight as the one number the morning is about, and
links to none of the survivors it counts; the operator wants it to read as a mix of marketing energy
and product precision.

- [x] ISC-265: The dashboard's first read is one hero cell spanning two columns and two rows of a bento grid: the survivor count in the signal at display size, the funnel rail in a compact one-line form beneath it, and one `btn-primary` linking to `/shortlist`; on a morning with nothing extracted the hero says so in words and never shows an older count as this morning's.
- [x] ISC-266: `GET /api/v1/analytics/summary` answers with three groups and nothing else — intake per day for the last fourteen days (extracted and shortlisted), the count per score band, and the last run's health (`finishedAt`, the failed stage if any, the number of source mismatches) — and the dashboard requests neither `/api/v1/analytics` nor any other new endpoint.
- [x] ISC-267: The grid holds the hero and four small cells (intake sparkline, score distribution, follow-ups due, run health); at 1440×900 all five cells stand fully above the fold, and below 48rem they stack in one column in that order with no sideways scroll. (after: ISC-265)
- [x] ISC-268: The intake sparkline paints extracted in the primary and shortlisted in the signal, the score distribution paints the strong band in the signal, the weak band in secondary and the rest muted, and nothing else on the dashboard but the hero figure takes the signal; the ISC-224 allowlist grows by exactly the two chart components' files and the hero template, to sixteen, and stays exact. (after: ISC-266)
- [x] ISC-269: Every label on the dashboard is the reader's word, not the pipeline's: no "extracted", "written", "rows", "documents" or "announced" as a tile or cell label; the `dashboard.*` catalog keys are rewritten in both languages and `i18n-parity.spec.ts` stays green.
- [x] ISC-270: The machine room — the per-source table, the stage timings, the model name, the archive note — sits in one `<details>` under the grid, closed by default and open by itself when the last run failed or a source mismatched; the `role="status"` line that says a run is going stays outside it and visible. (after: ISC-267)
- [x] ISC-271: Anti: a number counts up, or a duration literal enters a stylesheet; the cells' reveal reads `--lg-reveal-*` from `motion.css` and is off under `prefers-reduced-motion`.
- [x] ISC-272: The hero figure (the signal's text twin at display size) is at least 3:1 and every cell label and value at least 4.5:1 on the cell's surface in both themes, measured in the browser tier. (after: ISC-265)
- [x] ISC-273: `dashboard.spec.ts` covers the hero's empty morning, the machine room opening on a failed run, and the summary request; the funnel rail's baseline numbers (ISC-68) hold in the compact form. (after: ISC-270)
- [x] ISC-274: `docs/decisions/frontend-design-system.md` carries the control-room decisions, `docs/decisions/read-side.md` names the summary endpoint, `CHANGELOG.md` § Unreleased names the screen and the endpoint, and `WorkingNotesStaySmallTest` stays green. (after: ISC-266)

## Test Strategy

| isc | type | check | threshold | tool | anchors_to |
|---|---|---|---|---|---|
| ISC-265 | bun-test | render the dashboard with a summary of 64/510 and with one of 0/0 | hero shows "64" in `text-signal` at display size and a `btn-primary` to `/shortlist`; the empty morning shows the catalog's "nothing new" line and no count as new | Vitest | `dashboard.spec.ts`, `dashboard-hero` |
| ISC-266 | bun-test | `AnalyticsSummaryQueryServiceTest` over a seeded corpus; `dashboard.spec.ts` asserts the request set; `rg -n "analytics\.api\|/api/v1/analytics'" frontend/src/app/features/dashboard` | three groups with the named fields, nothing else; the dashboard requests `/analytics/summary` and never `/analytics`; rg = 0 | JUnit, Vitest, rg | `AnalyticsSummaryController`, `summary.store.ts` |
| ISC-267 | manual | `VerifyViewport.ts` at 1440×900: bottom of every cell ≤ 900; at 320 and 375 the cells' order and `scrollWidth` | five cells above the fold; single column; no overflow | Interceptor `VerifyViewport.ts` | `dashboard.css` |
| ISC-268 | bash | `rg -l` over the six signal names, specs excluded | exactly sixteen files: the ten of ISC-224 plus `dashboard-hero.html`, `intake-spark.css`, `intake-spark.html`, `score-bands.css`, `score-bands.html`, `score-bands.ts` | rg | `intake-spark.ts`, `score-bands.ts`, `dashboard-hero.html` |
| ISC-269 | bun-test | `dashboard.spec.ts` scans every `.cell-label` and the hero eyebrow for the five pipeline words; `i18n-parity.spec.ts` | 0; green | Vitest | `dashboard.html`, `en.json`, `de.json` |
| ISC-270 | bun-test | render with a healthy last run, then with `failedStage` set, then with a mismatch | `details` closed, open, open; the `role="status"` line outside the details in all three | Vitest | `dashboard.spec.ts` |
| ISC-271 | bash | `rg -n 'setInterval\|requestAnimationFrame' frontend/src/app/features/dashboard --glob '*.ts'`; `bun run lint:css` | no timer-driven count; green | rg, stylelint | `dashboard.css`, `motion.css` |
| ISC-272 | bun-test | browser spec renders the hero and one cell of each kind under both themes | ≥ 3:1 figure, ≥ 4.5:1 labels and values | Vitest browser mode | `contrast.browser.spec.ts` |
| ISC-273 | bun-test | `dashboard.spec.ts`, `funnel-rail.spec.ts` | green, the 1,289 / 239 / 18.5 % baseline unchanged in compact form | Vitest | `dashboard.spec.ts`, `funnel-rail.spec.ts` |
| ISC-274 | bun-test | `WorkingNotesStaySmallTest`; `rg -n -i 'control room\|analytics/summary' docs CHANGELOG.md` | green; ≥ 1 each | JUnit, rg | `frontend-design-system.md`, `read-side.md`, `CHANGELOG.md` |

## Decisions

- **2026-09-24 — Direction C over the Designer agent's A.** The agent recommended the editorial
  "Morning Edition" with the top three offers as cards; the operator chose the bento control room,
  the denser and more precise of the three. What A had that C keeps: the one number in the signal
  as the first read, one call to action, the machine room out of the lobby.
- **2026-09-24 — A slim summary endpoint rather than the full `/analytics`.** The sparkline and the
  distribution exist today only inside the analytics screen's payload, which carries every run,
  tag and portal. A dashboard that pays for that on every open is the wrong trade; three groups in
  one small answer is one controller and one query, no schema change.
- **2026-09-24 — Marks, with their reasoned defaults.** ⟨?: the machine room closed by default —
  it is the same content as today, one click away, open on its own when it matters⟩ ⟨?: the hero
  sentence in the catalog reads "N of M made it through" in English and its German equivalent,
  plain rather than playful⟩ ⟨?: no comparison to the previous run⟩.
- **2026-09-24 — The allowlist is counted in files, and a component is three of them.** ISC-268 said
  "the two new chart files"; the two charts are five files (a stylesheet and a template each, and
  the bands' series colours in the class), and the hero figure the claim itself asks for makes the
  hero template a sixth. Re-cut to sixteen, named one by one; ISC-224's ten stays in its stub as
  the count it was, and its claim now says the dashboard reads the signal too. A comment in the
  header template that named the token was reworded, because the allowlist is about readers, not
  mentions.
- **2026-09-24 — A preview line on the closed machine room.** The operator, after the first cut:
  closed by default the page looked bare, open by default would be too much at once. The summary
  line carries when the run finished, how many sources it read, what was new, how long it took
  and the model that judged, so the closed disclosure is a sentence and not a label.
- **2026-09-24 — `.hero` is daisyUI's.** The hero cell was class `.hero` for one build and
  centred its content in a grid; renamed `.control-hero`. The third class-name trap after
  `.status` and `.label`.
- **2026-09-24 — Marks resolved.** The machine room closed by default, now with the preview; the
  hero sentence plain, "N of M made it through"; no comparison to the previous run.
- **2026-09-24 — Four lines of facts under the preview.** The one-line preview still left the
  closed fold looking lost under five cells (the operator, second look). The summary now carries
  four labelled lines read off the recorded run: sources read, documents and mismatches; the hard
  filter's considered, passed and removed with the stage that removed most; the stages timed and
  the slowest; judged, shortlisted, for review and whether the digest was written. Spans made
  blocks, because a summary allows phrasing content only; the browser keeps its own marker.

## Verification

- ISC-265 — dashboard.spec 'leads with the survivor count in the signal and one primary button to the shortlist' (64 of 510 in `.figure.text-signal.type-display-xl`, exactly one `btn-primary`, `href="/shortlist"`) and 'says a quiet night in words and never as a number'; `dashboard-hero` renders inside `.lg-panel-hero`; 13/13 green (2026-09-24)
- ISC-266 — `AnalyticsSummaryQueryServiceTest` green over Testcontainers: fourteen zero-filled days cut in the server zone, bands over the whole archive, failed stage and mismatch count; `AnalyticsSummaryController` at `GET /api/v1/analytics/summary`; dashboard.spec 'asks for the summary and never for the analytics screen payload'; the feature's one `analytics'` hit is the spec's own assertion, no api call (2026-09-24)
- ISC-268 — the six-name `rg -l` over `frontend/src/app`, specs excluded, = 16 files: the ten of ISC-224 + `dashboard-hero.html`, `intake-spark.css`, `intake-spark.html`, `score-bands.css`, `score-bands.html`, `score-bands.ts`; the header template's comment that named the token was reworded so the count is readers only (2026-09-24)
- ISC-269 — dashboard.spec 'names no pipeline word as a cell label' over every `.cell-label` and the hero eyebrow; `dashboard.*` is 58 keys in each catalog, `i18n-parity.spec.ts` green; the five words remain in the machine room's table headings and the mismatch sentence, where they are the pipeline's (2026-09-24)
- ISC-270 — dashboard.spec 'keeps the machine room closed on a healthy run' (details closed, preview names 169 new offers, the model and 9.3 s), 'opens the machine room on a failed run', 'opens the machine room when a source came up short'; the `role="status"` running line stands above the `<details>` in `dashboard.html` (2026-09-24)
- ISC-271 — `rg -n 'setInterval|requestAnimationFrame|setTimeout' features/dashboard --glob '*.ts'` = 0 outside specs; `bun run lint:css` green under the duration rule; `dashboard.css` `.cell` reveals on `var(--lg-reveal-duration) var(--lg-reveal-ease)` and is `animation: none` under `prefers-reduced-motion: reduce` (2026-09-24)
- ISC-272 — contrast.browser.spec 'the control room reads (ISC-272)' ×2 themes: 'the hero figure is ≥ 3:1 and its texts ≥ 4.5:1 on the hero wash' and 'a cell label, a value and the two chart sums are ≥ 4.5:1 on the cell surface'; 36/36 green in headless Chromium (2026-09-24)
- ISC-273 — dashboard.spec 13/13 including the quiet night, the failed run opening the details and the summary request; funnel-rail.spec 'FunnelRail, compact' holds 1,289 / 239 / 18.5 % (2026-09-24)
- ISC-274 — `rg -c -i 'control room'`: `frontend-design-system.md` 3, `CHANGELOG.md` 1; `analytics/summary`: `read-side.md` 1, `CHANGELOG.md` 1; `WorkingNotesStaySmallTest` green in `./gradlew check` (2026-09-24)
- ISC-267 — manual, the operator's word on 2026-09-24 over the live dashboard in both themes: "ja past"; the measurement behind it: `VerifyViewport.ts` at 1440×900 cell bottoms 299 and 411 (all ≤ 900, the two top cells at one height since the leftover panel-stack margin went); at 375 and 320 one distinct left edge, the declared order, `scrollWidth == clientWidth` at all three widths; dark and light shots on file (2026-09-24)
