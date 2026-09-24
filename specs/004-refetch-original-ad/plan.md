---
spec: 004-refetch-original-ad
type: feature
status: draft
updated: 2026-09-24
---

# Plan 004 — Fetching the original ad again

**Purpose:** how the claims in `spec.md` get built. The what lives there; this file
holds no acceptance criterion.

## Approach

The button runs the nightly pipeline's own code, narrowed to one offer id. Each of the four stages
(enrichment, content, fields, scoring) gets an id-scoped entry point that shares its loop body with
`run()`, and a small orchestrator calls them in the night's order after resetting the offer's stamps.
The obvious alternative, a dedicated refetch service calling freshly extracted per-offer helpers, was
not taken. It would be a second path through four stages that already encode a dozen decisions
(budget, model recording, when `score_model` is nulled, what counts as settled), and the first
change to one path that misses the other is a button that behaves differently from the night. The
second structural change is the rate window. It moves out of `AdFetcher`, where it lived per pass,
into one shared instance. That is what lets the run and the button draw from the same minute. The
run keeps waiting for a permit; the button asks once and gives up with a reason.

## Affected Files and Modules

| Path | Change | Claim |
|------|--------|-------|
| `backend/…/enrich/FetchWindow.java` (new) | the sliding window and its clock, lifted out of `AdFetcher` as a singleton bean; `tryTake()` never waits, `awaitTake()` waits as today | ISC-245 |
| `backend/…/enrich/AdFetcher.java` | takes a `FetchWindow` instead of owning a deque; keeps the per-pass `taken` budget; a `fetchFresh(url)` path that skips `cache.find` and takes its permit with `tryTake()` before robots.txt, then robots, then the network, still `cache.store` | ISC-244, ISC-245 |
| `backend/…/enrich/EnrichmentService.java` | `run()` and a new `runFor(long id)` share one loop over a `Due` list; `runFor` selects by id with the same predicate minus `enriched_at IS NULL`, fetches fresh, and on a deferral writes nothing and throws `NoPermit` | ISC-244, ISC-246, ISC-247 |
| `backend/…/content/ContentService.java` | `runFor(long id)`: the `DUE` predicate plus `AND id = ?`, same `segment` + `record` | ISC-246 |
| `backend/…/fields/FieldsService.java` | `runFor(long id)`, same shape; skipped without a model exactly as `run()` is | ISC-246, ISC-249 |
| `backend/…/score/ScoringService.java` | `scoreFor(long id)`: the per-candidate branch of `run()` extracted into one method both call, so no model means `Score.unscored` with the rule reasons rather than `NoJudge` | ISC-246, ISC-249 |
| `backend/…/enrich/OfferRefetch.java` (new) | the orchestrator: reset `enriched_at`, `content_at`, `fields_at` for the id, then the four `runFor` calls in order; not `@Transactional` across the network call | ISC-244, ISC-246, ISC-248 |
| `backend/…/web/OfferController.java` | `POST /{id}/fetch` → `OfferRefetch`, answers `offers.find(id)`; `NoPermit` → 429 with the reason as text, not on the list → 409 like `/score` | ISC-244, ISC-246, ISC-247 |
| `backend/src/test/…/enrich/OfferRefetchTest.java` (new) | WireMock + Testcontainers, one test per claim row | ISC-244 … ISC-249 |
| `backend/src/test/…/enrich/AdFetcherTest.java` | follows the window moving out; the existing sliding-window tests keep their meaning against `FetchWindow` | ISC-245 |
| `frontend/src/app/core/api/shortlist.api.ts` | `refetch(id)` POST, next to `rescore` | ISC-246 |
| `frontend/src/app/core/store/shortlist.events.ts`, `shortlist.store.ts` | `fetchRequested / fetched / fetchFailed`, `fetching: number \| null`, `fetchError: string \| null`, an `exhaustMap` effect mirroring the rescore one; `fetched` replaces the entry | ISC-246, ISC-247 |
| `frontend/src/app/core/toast/toast.store.ts` | on `fetched` a success toast when the ad arrived and an info toast with the note when it did not; nothing on `fetchFailed`, which stays inline per `frontend/CLAUDE.md` | ISC-247 |
| `frontend/src/app/features/offer-detail/offer-detail.{html,ts}` | a `canFetch` computed (hardPass, `archivedAt` null, `url`, no `fullText`), the button beside the caption, the reason under it | ISC-243, ISC-247 |
| `frontend/src/app/features/offer-detail/offer-detail.spec.ts` | the four-condition table and the failure rendering | ISC-243, ISC-247 |
| `frontend/public/i18n/en.json` | `detail.fetchAgain`, `detail.fetching`, `error.fetch`, the toast strings | ISC-243 |
| `docs/decisions/pipeline-enrich-content.md` | why the button bypasses the cache and not robots or the window, and why the window is shared | ISC-245 |
| `docs/BACKEND-FLOWS.md` | the per-offer path as a short sequence beside the rescore | ISC-246 |
| `CHANGELOG.md` § Unreleased | Added: fetch the original ad again | ISC-246 |

The `…` prefix is `backend/src/main/java/de/codeministry/leadgen`. No migration: every column the
reset touches exists.

## Interfaces

`POST /api/v1/offers/{id}/fetch`, no body. `200` answers the `ShortlistEntry` as `GET /{id}` would.
`409` with a plain-text reason when the offer is not one the night would fetch. `429` with a
plain-text reason when the shared window has no permit, and nothing is written. A fetch that reached
the page and failed is **not** an error status: it answers `200` with the entry, whose
`enrichmentNote` carries the new reason. A failed fetch is a recorded outcome, the same as at night.
The one caller is the offer detail through `shortlist.api.ts`.

## Risks

| Risk | Blast radius | Early warning | Mitigation |
|------|--------------|---------------|------------|
| Lifting the window changes the nightly run's pacing | every nightly enrichment | the existing sliding-window tests in `AdFetcherTest` go red | move the tests with the window unchanged before touching `AdFetcher`; the budget stays per pass |
| Resetting `content_at`/`fields_at` on an offer the fetch then fails for | that offer's panels | a failed refetch shows blocks gone | reset content and fields only after the fetch stored `full_text`; enrichment's own reset is the only one before the fetch |
| The request runs for tens of seconds with a local model on segmentation, fields and judge | one HTTP request | the dev proxy or nginx times out | the spinner state; `frontend/nginx.conf` sets no `proxy_read_timeout`, so nginx's 60 s default applies to this call as it already does to the rescore; raised for `/api/` only if a measured refetch comes near it |
| An `@Transactional` creeping around the orchestrator holds a write lock across the network | concurrent run's filter stage | a run stalls while a button is pressed | the orchestrator stays non-transactional, the same reason `EnrichmentService.run` documents |
| LLM budget spent by a button | the daily cap | `LlmBudget` refuses | the stages already take from the budget and degrade exactly as at night |

## Conformance Impact

- BE-DB-03 migration immutability: untouched, no migration.
- G-FE-02 i18n parity (grandfathered): extended by the new keys in both catalogs, `en.json` and `de.json`; the parity spec covers them, the baseline row stays as it is.
