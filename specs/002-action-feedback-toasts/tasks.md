---
spec: 002-action-feedback-toasts
plan: plan.md
updated: 2026-09-23 (all five stages closed)
---

# Tasks 002 — Feedback after an action, and for a run whoever started it

**Purpose:** atomic, checkable steps. Each task hangs on exactly one claim ID from
`spec.md`. This file defines nothing, it decomposes.

## Legend

`[P]` = parallelizable. `(after: T…)` = must run after that task.

`[P]` was derived from `IsaFrontier.ts frontier` on 2026-09-23: only ISC-205 is takeable, so only its tasks with no
`after` edge and no shared file carry it. Every handler task below touches `toast.store.ts`, which is why none of the
mapping tasks is parallel with another, whatever their claims' edges say. The live frontier is the authority at
dispatch time; this column is a hint.

## Tasks

### Stage 1 — the mechanism and the archive slice

- [x] T1 · ISC-205 · [P] — `Toast` shape, the tone union and the literal tone-to-class lookup · `frontend/src/app/core/toast/toast.model.ts`
- [x] T2 · ISC-205 · [P] — the event group: `raised`, `dismissed`, `expired`, `held`, `released` · `frontend/src/app/core/toast/toast.events.ts`
- [x] T3 · ISC-205 — the store: state, the reducer for the five events, no domain mapping yet (after: T1, T2) · `frontend/src/app/core/toast/toast.store.ts`
- [x] T4 · ISC-205 — the stack component: one `alert` per toast, close button dispatching `dismissed`, `routerLink` when a link is set (after: T3) · `frontend/src/app/layout/toast-stack/toast-stack.ts`, `toast-stack.html`, `toast-stack.css`
- [x] T5 · ISC-205 — the shell renders `<lg-toast-stack/>` once, outside `.measure` (after: T4) · `frontend/src/app/layout/app-shell/app-shell.html`, `app-shell.ts`
- [x] T6 · ISC-205 — shell spec: the stack is rendered once; rg over `features/` for `toastEvents.` (after: T5) · `frontend/src/app/layout/app-shell/app-shell.spec.ts`
- [x] T7 · ISC-206 — handler: `shortlistEvents.archived` → `raised` with direction, title and `/shortlist/:id` (after: T3) · `frontend/src/app/core/toast/toast.store.ts`
- [x] T8 · ISC-217 — the `toast` group in the English catalog, the archive keys first, counts as ICU plurals (after: T7) · `frontend/public/i18n/en.json`
- [x] T9 · ISC-217 — the same group in the German catalog (after: T8) · `frontend/public/i18n/de.json`
- [x] T10 · ISC-217 — parity spec over the whole flattened catalog, both directions (after: T9) · `frontend/src/app/core/i18n/i18n-parity.spec.ts`
- [x] T11 · ISC-206 — spec block: archived and restored entries, one toast each, direction, title, link (after: T7, T8) · `frontend/src/app/core/toast/toast.store.spec.ts`
- [x] T12 · ISC-214 — lifetime in the store: an RxJS `timer` per `raised`, `expired` on elapse, `held`/`released` pausing it, the cap dropping the oldest (after: T3) · `frontend/src/app/core/toast/toast.store.ts`
- [x] T13 · ISC-214 — the motion file: `--lg-toast-duration`, `--lg-toast-ease`, imported beside `tokens.css` (after: T4) · `frontend/src/styles/motion.css`, `frontend/src/styles.css`
- [x] T14 · ISC-214 — hover and focus dispatch `held`/`released` on the stack (after: T4, T12) · `frontend/src/app/layout/toast-stack/toast-stack.ts`, `toast-stack.html`
- [x] T15 · ISC-214 — spec block: fake timers, past the duration, held past it, four raised (after: T12, T14) · `frontend/src/app/core/toast/toast.store.spec.ts`, `frontend/src/app/layout/toast-stack/toast-stack.spec.ts`
- [x] T16 · ISC-215 — the region: `role="status"`, `aria-live="polite"`, a named close button, `tabindex` nowhere (after: T4) · `frontend/src/app/layout/toast-stack/toast-stack.html`
- [x] T17 · ISC-215 — spec block: region attributes, button name, `document.activeElement` unchanged after a raise (after: T16) · `frontend/src/app/layout/toast-stack/toast-stack.spec.ts`
- [x] T18 · ISC-219 — tones through the literal lookup only, `alert-success` and `alert-info` spelled out, no colour literal, no accent (after: T4, T13) · `frontend/src/app/layout/toast-stack/toast-stack.css`, `frontend/src/app/core/toast/toast.model.ts`
- [x] T19 · ISC-216 — first foregrounded browser pass of the slice: archive from the detail and from a card, at 1280px and 390px, before any other mapping copies the shape (after: T11, T13, T16) · `frontend/src/app/layout/toast-stack/toast-stack.css`

### Stage 2 — the remaining action mappings

- [x] T20 · ISC-207 — handler: `bulkArchived` → one toast naming `payload.archived` (after: T7) · `frontend/src/app/core/toast/toast.store.ts`
- [x] T21 · ISC-207 — spec block: five ids, `archived: 3`, the toast names 3; the plural keys in both catalogs (after: T20) · `frontend/src/app/core/toast/toast.store.spec.ts`, `frontend/public/i18n/en.json`, `frontend/public/i18n/de.json`
- [x] T22 · ISC-208 — handler: `applicationEvents.updated` → title, state label, `/pipeline/:id`; nothing on `changed` (after: T7) · `frontend/src/app/core/toast/toast.store.ts`
- [x] T23 · ISC-208 — spec block: `changed` then `updated` raises one; `changed` then `changeFailed` raises none; the keys (after: T22) · `frontend/src/app/core/toast/toast.store.spec.ts`, `frontend/public/i18n/en.json`, `frontend/public/i18n/de.json`
- [x] T24 · ISC-209 — handler: `rescored` → title and stored score (after: T7) · `frontend/src/app/core/toast/toast.store.ts`
- [x] T25 · ISC-209 — spec block and the keys (after: T24) · `frontend/src/app/core/toast/toast.store.spec.ts`, `frontend/public/i18n/en.json`, `frontend/public/i18n/de.json`
- [x] T26 · ISC-210 — `settled` carries `{name, outcome}`; both handlers dispatch it; the reducer reads `payload.name`; the dispatching spec follows (after: T3) · `frontend/src/app/core/store/manual.events.ts`, `manual.store.ts`, `manual.store.spec.ts`
- [x] T27 · ISC-210 — handler: `settled` → document and outcome (after: T26, T7) · `frontend/src/app/core/toast/toast.store.ts`
- [x] T28 · ISC-210 — spec block, both outcomes, and the keys (after: T27) · `frontend/src/app/core/toast/toast.store.spec.ts`, `frontend/public/i18n/en.json`, `frontend/public/i18n/de.json`
- [x] T29 · ISC-213 — anti spec block: the five failure events raise nothing; record the `role="alert"` count before and after (after: T7) · `frontend/src/app/core/toast/toast.store.spec.ts`
- [x] T30 · ISC-218 — spec block: the stack's only dispatch is `dismissed`; every link the store can raise resolves against `app.routes.ts` (after: T22) · `frontend/src/app/toast-links.spec.ts` (app level: it needs the route table and the store, and neither layer may import the other)

### Stage 3 — the runs

- [x] T31 · ISC-211 — `IngestStore`: `requested` also dispatches `currentRequested`; spec for the immediate ask (after: T3) · `frontend/src/app/core/store/ingest.store.ts`, `ingest.store.spec.ts`
- [x] T32 · ISC-211 — handler: `currentLoaded` non-null with a new id → one toast; `lastRunStarted` keyed on the id; nothing on `requested` (after: T31, T7) · `frontend/src/app/core/toast/toast.store.ts`
- [x] T33 · ISC-211 — spec block: `requested` then run 7 twice, run 8 alone; the keys (after: T32) · `frontend/src/app/core/toast/toast.store.spec.ts`, `frontend/public/i18n/en.json`, `frontend/public/i18n/de.json`
- [x] T34 · ISC-212 — handler: `finished` → toast keyed on `finishedAt`; `refreshEvents.requested('run-ended')` sets `awaitingRunEnd`; the next `lastRunLoaded` raises when its `finishedAt` is new and clears the flag; a `lastRunLoaded` with no flag raises nothing (after: T32) · `frontend/src/app/core/toast/toast.store.ts`
- [x] T35 · ISC-212 — spec block: the three cases from the Test Strategy, written and shortlisted named; the keys with two plurals (after: T34) · `frontend/src/app/core/toast/toast.store.spec.ts`, `frontend/public/i18n/en.json`, `frontend/public/i18n/de.json`

### Stage 4 — motion and the record

- [x] T36 · ISC-216 — reduced-motion gate on the stack's own enter and leave, beside DaisyUI's; the foregrounded browser pass through `VerifyViewport.ts` with the preference off and on, plus VoiceOver reading one raise (after: T19, T35) · `frontend/src/app/layout/toast-stack/toast-stack.css`
- [x] T37 · ISC-220 — the toast decisions in the interface-language section: one mechanism, answers not requests, a link never an undo, failures inline (after: T35) · `docs/decisions/frontend-design-system.md`
- [x] T38 · ISC-220 — one rule line in the frontend notes (after: T37) · `frontend/CLAUDE.md`
- [x] T39 · ISC-220 — § Unreleased: the feature, the `settled` shape, the parity spec (after: T37) · `CHANGELOG.md`
- [x] T40 · ISC-220 — run `WorkingNotesStaySmallTest` after the three edits (after: T38, T39) · `backend/src/test/java/de/codeministry/leadgen/WorkingNotesStaySmallTest.java`

### Stage 5 — a tone per action family

- [x] T41 · ISC-221 — the tone union gains `warning`, the literal map its class, the comment the three families · `frontend/src/app/core/toast/toast.model.ts`
- [x] T42 · ISC-221 — the family per handler: archive/bulk/rejected/closing states warning, restore/confirmed/scored/forward success, runs and unscored info; `CLOSED_AGAINST_US` literal set (after: T41) · `frontend/src/app/core/toast/toast.store.ts`
- [x] T43 · ISC-221 — spec blocks assert the tone on every mapping; a move into LOST is warning, into WON success (after: T42) · `frontend/src/app/core/toast/toast.store.spec.ts`
- [x] T44 · ISC-221 — the record: a bullet in the Toasts section on the families and the widening of amber; a changelog line (after: T42) · `docs/decisions/frontend-design-system.md`, `CHANGELOG.md`
- [x] T45 · ISC-221 — pixel capture of an archive toast beside a restore toast, both themes' legibility of `alert-soft alert-warning` read off the image (after: T42) · `frontend/src/app/layout/toast-stack/toast-stack.css`

## Probe Mapping

| Task | Claim | Probe (from `spec.md` § Test Strategy) |
|------|-------|----------------------------------------|
| T1–T6 | ISC-205 | rg for `toastEvents.` outside `core/toast/` and `toast-stack/`; the shell spec counts `lg-toast-stack` |
| T7, T11 | ISC-206 | dispatch `archived` for an archived and a restored entry |
| T8–T10 | ISC-217 | compare the `toast` key sets of `en.json` and `de.json` |
| T12–T15 | ISC-214 | fake timers: past the duration; held past it; four raised at once |
| T16, T17 | ISC-215 | render the stack with one toast: region attributes, button name, active element |
| T18 | ISC-219 | `bun run lint:css`; rg for `'alert-' +`; rg for `accent` in the stack |
| T19, T36 | ISC-216 | archive an offer in a real foregrounded browser, once with reduced motion on |
| T20, T21 | ISC-207 | dispatch `bulkArchived` with five ids and `archived: 3` |
| T22, T23 | ISC-208 | `changed` then `updated`; `changed` then `changeFailed` |
| T24, T25 | ISC-209 | dispatch `rescored` |
| T26–T28 | ISC-210 | dispatch `settled` with each outcome |
| T29 | ISC-213 | dispatch the five failure events; `rg -c 'role="alert"'` unchanged |
| T30 | ISC-218 | rg for dispatchers in the stack; resolve every link against `app.routes.ts` |
| T31–T33 | ISC-211 | `requested` then `currentLoaded(run 7)` twice; `currentLoaded(run 8)` alone |
| T34, T35 | ISC-212 | `finished(@T)` then `run-ended` + `lastRunLoaded(@T)`; `run-ended` + `lastRunLoaded(@U)`; `lastRunLoaded(@V)` alone |
| T37–T40 | ISC-220 | `WorkingNotesStaySmallTest`; rg for `toast` in the decision doc and the changelog |
| T41–T45 | ISC-221 | dispatch one answer event per family, and a status change into LOST and into WON; rg for `alert-error` |
