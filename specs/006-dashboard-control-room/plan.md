---
spec: 006-dashboard-control-room
type: feature
status: draft
updated: 2026-09-24
---

# Plan 006 — The dashboard as a control room

**Purpose:** how the claims in `spec.md` get built. The what lives there; this file
holds no acceptance criterion.

## Approach

Endpoint first, then the grid. The summary endpoint is one read-side controller with one query
per group and a JUnit test over the seeded corpus, built and green before any template changes,
because every small cell depends on it and a grid built against fake data is reviewed twice. Then
the grid in one pass: the hero (a new `dashboard-hero` component with the compact funnel rail and
the one primary button), the four cells (two new small charts in `shared/`, two stat tiles), the
machine room as a `<details>`, and the catalog rewritten in both languages. The contrast rows and
the density measurement close it, and the records last. One review in the browser at the end of
the grid pass, both themes, 1440 and 375.

The obvious alternative was to read the full `/analytics` and build frontend-only; it was not
taken because the dashboard would pay for the analytics screen's whole payload on every open, and
the operator chose the endpoint when asked.

## Affected Files and Modules

| Path | Change | Claim |
|------|--------|-------|
| `backend/…/analytics/AnalyticsSummaryController.java`, `AnalyticsSummaryQuery.java` (new) | `GET /api/v1/analytics/summary`: intake per day (14 d), score bands, last run health | ISC-266 |
| `backend/src/test/java/…/AnalyticsSummaryControllerTest.java` (new) | three groups, nothing else, against Testcontainers Postgres | ISC-266 |
| `frontend/src/app/core/api/analytics.api.ts`, `core/model/analytics.ts` | the `AnalyticsSummary` type and the call | ISC-266 |
| `frontend/src/app/core/store/dashboard.store.ts`, `dashboard.events.ts` | `summaryLoaded`, refreshed with the run heartbeat like the funnel | ISC-266, ISC-270 |
| `frontend/src/app/features/dashboard/dashboard.html`, `dashboard.css`, `dashboard.ts` | the bento grid, the `<details>` machine room | ISC-265, ISC-267, ISC-270 |
| `frontend/src/app/features/dashboard/dashboard-hero/*` (new) | the hero cell | ISC-265, ISC-272 |
| `frontend/src/app/shared/funnel-rail/*` | `compact` input: one line of stage totals and the survivor bar | ISC-265, ISC-273 |
| `frontend/src/app/shared/chart/intake-spark.ts`, `score-bands.ts` (new) | the two small charts on the chart palette | ISC-268 |
| `frontend/src/styles/primitives.css` | `.lg-panel-hero` variant | ISC-265 |
| `frontend/public/i18n/en.json`, `de.json` | `dashboard.*` rewritten | ISC-269 |
| `frontend/src/app/features/dashboard/dashboard.spec.ts` | the three scenarios | ISC-273 |
| `frontend/src/app/core/theme/contrast.browser.spec.ts` | hero and cell rows | ISC-272 |
| `frontend/src/app/core/theme/theme-colors.spec.ts` or the ISC-224 probe | allowlist grows by two | ISC-268 |
| `docs/decisions/frontend-design-system.md`, `docs/decisions/read-side.md`, `CHANGELOG.md` | the record | ISC-274 |

## Interfaces

| Contract | Before | After | Callers |
|----------|--------|-------|---------|
| `GET /api/v1/analytics/summary` | — | `{intake: [{day, extracted, shortlisted}] (14), scoreBands: {strong, weak, out, unscored}, lastRun: {finishedAt, failedStage, mismatches}}` | the dashboard store |
| `FunnelRail` | full rail | plus `compact` input, default false | dashboard hero |
| `dashboard.*` catalog | pipeline words | the reader's words, same key set in both languages | the dashboard |

## Risks

| Risk | Blast radius | Early warning | Mitigation |
|------|--------------|---------------|------------|
| the five cells do not fit above 900 px once real numbers wrap | the density claim | `VerifyViewport.ts` at 1440×900 | hero under 280 px; cells at one line of label plus one figure |
| the signal creeps into a cell's accent | ISC-224 | the allowlist probe | only the two chart files may name it |
| the machine room hides a failure | trust | `dashboard.spec.ts` failed-run scenario | open on `failedStage` or `mismatches > 0`, status line outside |
| the summary query is slow on the deployed corpus | the dashboard's load | the JUnit test's timing over the seeded corpus | index-backed reads, fourteen days only |

## Conformance Impact

| Baseline entry | Effect |
|----------------|--------|
| FE-TST-05 coverage as a ratchet (grandfathered) | left |
| BE-ARCH-01..03 vertical slices (not measured) | left; the new controller follows the existing read-side package shape |
