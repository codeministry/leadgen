---
spec: 006-dashboard-control-room
plan: plan.md
updated: 2026-09-24
---

# Tasks 006 — The dashboard as a control room

**Purpose:** atomic, checkable steps. Each task hangs on exactly one claim ID from
`spec.md`. This file defines nothing, it decomposes.

## Legend

`[P]` = parallelizable. `(after: T…)` = must run after that task.

`[P]` was derived from `IsaFrontier.ts frontier` on 2026-09-24: ISC-265, ISC-266, ISC-269 and ISC-271 are takeable;
the rest wait on them. The endpoint (T1–T4) and the catalog (T13–T14) touch no file the grid touches, so they are
parallel; everything in `dashboard.html` is serial.

## Tasks

### Stage 1 — the endpoint

- [x] T1 · ISC-266 · [P] — the query: intake per day for 14 days, score bands, last run health, one `JdbcClient` read each · `backend/src/main/java/de/codeministry/leadgen/analytics/AnalyticsSummaryQueryService.java`
- [x] T2 · ISC-266 — the controller and the response record (after: T1) · `backend/src/main/java/de/codeministry/leadgen/web/AnalyticsSummaryController.java`
- [x] T3 · ISC-266 — the JUnit test over the seeded corpus: three groups, the named fields, nothing else (after: T2) · `backend/src/test/java/de/codeministry/leadgen/analytics/AnalyticsSummaryQueryServiceTest.java`
- [x] T4 · ISC-266 — the frontend type and call; the dashboard store loads it and refreshes with the run heartbeat (after: T2) · `frontend/src/app/core/model/analytics.ts`, `core/api/analytics.api.ts`, `core/store/summary.store.ts`, `summary.events.ts`

### Stage 2 — the grid

- [x] T5 · ISC-265 · [P] — `FunnelRail` gains `compact`: stage totals in one line and the survivor bar · `frontend/src/app/shared/funnel-rail/funnel-rail.ts`, `funnel-rail.html`, `funnel-rail.css`
- [x] T6 · ISC-265 — `dashboard-hero`: the figure in `text-signal` at display size, the compact rail, the one `btn-primary`, the empty-morning line (after: T5) · `frontend/src/app/features/dashboard/dashboard-hero/*`
- [x] T7 · ISC-265 — `.lg-panel-hero` in the primitives (after: T6) · `frontend/src/styles/primitives.css`
- [x] T8 · ISC-268 — `intake-spark`: 14 bars, extracted in primary, shortlisted in the signal (after: T4) · `frontend/src/app/shared/chart/intake-spark.ts`
- [x] T9 · ISC-268 — `score-bands`: strong in the signal, weak in secondary, the rest muted (after: T4) · `frontend/src/app/shared/chart/score-bands.ts`
- [x] T10 · ISC-267 — the bento grid in the template and stylesheet: hero 2×2, four cells, single column below 48rem (after: T6, T8, T9) · `frontend/src/app/features/dashboard/dashboard.html`, `dashboard.css`
- [x] T11 · ISC-270 — the machine room as `<details>`, open on `failedStage` or mismatches, the status line outside (after: T10) · `frontend/src/app/features/dashboard/dashboard.html`, `dashboard.ts`
- [x] T12 · ISC-271 — the cells' reveal on `--lg-reveal-*`, reduced motion off; no timer anywhere (after: T10) · `frontend/src/app/features/dashboard/dashboard.css`
- [x] T13 · ISC-269 · [P] — the `dashboard.*` keys rewritten, English · `frontend/public/i18n/en.json`
- [x] T14 · ISC-269 — the same keys, German (after: T13) · `frontend/public/i18n/de.json`
- [x] T15 · ISC-268 — the ISC-224 allowlist probe grows by the two chart files (after: T8, T9) · `specs/006-dashboard-control-room/spec.md` (the probe row), `frontend/src/app/core/theme/theme-colors.spec.ts` if the guard names it

### Stage 3 — proof and record

- [x] T16 · ISC-273 — `dashboard.spec.ts`: 64/510 hero, 0/0 morning, failed run opens the details, the summary request; `funnel-rail.spec.ts` compact baseline (after: T11, T14) · `frontend/src/app/features/dashboard/dashboard.spec.ts`, `frontend/src/app/shared/funnel-rail/funnel-rail.spec.ts`
- [x] T17 · ISC-272 — contrast rows: hero figure, one cell of each kind, both themes (after: T10) · `frontend/src/app/core/theme/contrast.browser.spec.ts`
- [x] T18 · ISC-267 — `VerifyViewport.ts` at 1440×900, 375 and 320; the browser review in both themes (after: T12) · (evidence only)
- [x] T19 · ISC-274 — the record: decision bullets, the endpoint in `read-side.md`, the changelog, `WorkingNotesStaySmallTest` (after: T16, T18) · `docs/decisions/frontend-design-system.md`, `docs/decisions/read-side.md`, `CHANGELOG.md`

## Probe Mapping

| Task | Claim | Probe (from `spec.md` § Test Strategy) |
|------|-------|----------------------------------------|
| T1–T4 | ISC-266 | `AnalyticsSummaryQueryServiceTest`; rg for `/analytics` in the dashboard |
| T5–T7 | ISC-265 | render with 64/510 and with 0/0 |
| T8, T9, T15 | ISC-268 | the signal allowlist probe at sixteen files |
| T10, T18 | ISC-267 | `VerifyViewport.ts` at 1440×900, 375, 320 |
| T11 | ISC-270 | render healthy, failed, mismatched |
| T12 | ISC-271 | rg for timers; `bun run lint:css` |
| T13, T14 | ISC-269 | scan for the five pipeline words; parity spec |
| T16 | ISC-273 | `dashboard.spec.ts`, `funnel-rail.spec.ts` |
| T17 | ISC-272 | the browser contrast rows |
| T19 | ISC-274 | `WorkingNotesStaySmallTest`; rg over the records |
