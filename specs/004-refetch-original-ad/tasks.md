---
spec: 004-refetch-original-ad
plan: plan.md
updated: 2026-09-24
---

# Tasks 004 — Fetching the original ad again

**Purpose:** atomic, checkable steps. Each task hangs on exactly one claim ID from
`spec.md`. This file defines nothing, it decomposes.

## Legend

`[P]` = parallelizable. `(after: T…)` = must run after that task. `· <lane>` = derived from the path column:
the constitution has no `## Lanes` block, so the defaults apply, with `server` for `backend/`, `web` for
`frontend/` and `docs` for `docs/` and `CHANGELOG.md`. Backend tests sit in `server` beside the code they
probe. `[seam]` = the contract between two lanes; nothing across it runs before it.

`[P]` was derived from `IsaFrontier.ts frontier` on 2026-09-24. ISC-243, 244, 245 and 248 are takeable;
ISC-246 and 247 wait on ISC-244, and ISC-249 waits on ISC-246. Only two tasks carry `[P]`, T1 and T19.
Every other task on a takeable claim either follows the window move, because the fetcher is rebuilt on
top of it, or shares `OfferRefetchTest.java`. The seam is T9, on ISC-244 because that claim names the endpoint: `plan.md` § Interfaces fixes the
contract, T9 lands it with the enrichment step only, and ISC-244 closes on it before any ISC-246 task
can start. That order is forced by the claim edge, not chosen. The `…` prefix is
`backend/src/main/java/de/codeministry/leadgen`, and `…test` is the same under `src/test`.

## Tasks

### Server — the shared window and the fresh fetch

- [x] T1 · ISC-245 · [P] · server — `FetchWindow` bean: the sliding window, the injectable clock and `pause` lifted out of `AdFetcher`; `tryTake()` never waits, `awaitTake()` waits as the run does today · `…/enrich/FetchWindow.java`
- [x] T2 · ISC-245 · server — the sliding-window tests move to `FetchWindowTest` with their meaning unchanged; `AdFetcherTest` keeps the gate-order tests (after: T1) · `…test/enrich/FetchWindowTest.java`, `…test/enrich/AdFetcherTest.java`
- [x] T3 · ISC-245 · server — `AdFetcher` draws from the injected `FetchWindow` and keeps only the per-pass `taken` budget; `EnrichmentService` hands the bean in (after: T2) · `…/enrich/AdFetcher.java`, `…/enrich/EnrichmentService.java`
- [x] T4 · ISC-244 · server — `AdFetcher.fetchFresh(url)`: skips `cache.find`, takes its permit with `tryTake()` before robots.txt instead of waiting, still `cache.store`s every answer (after: T3) · `…/enrich/AdFetcher.java`
- [x] T5 · ISC-244 · server — `EnrichmentService.runFor(id)`: the `run()` loop body extracted and shared; the id query is `DUE` without `enriched_at IS NULL`; a deferral writes nothing and throws `NoPermit` (after: T4) · `…/enrich/EnrichmentService.java`

### Server — the later stages for one id, and the endpoint

- [x] T6 · ISC-246 · server — `ContentService.runFor(id)`: `DUE`/`DUE_WITH_A_MODEL` plus `AND id = ?`, same `segment` and `record` (after: T5) · `…/content/ContentService.java`
- [x] T7 · ISC-246 · server — `FieldsService.runFor(id)`: `DUE` plus `AND id = ?`, same budget and `record`, skipped without a model exactly as `run()` is (after: T5) · `…/fields/FieldsService.java`
- [x] T8 · ISC-246 · server — `ScoringService.scoreFor(id)`: the per-candidate branch of `run()` extracted into one method both call, so no judge means `Score.unscored` with the rule reasons (after: T5) · `…/score/ScoringService.java`
- [x] T9 · ISC-244 · [seam] · server — `POST /api/v1/offers/{id}/fetch` and `OfferRefetch.refetch(long id)` with the enrichment step only; `NoPermit` → 429 and not-fetchable → 409 as plain text, `200` answers `offers.find(id)`, per `plan.md` § Interfaces (after: T5) · `…/web/OfferController.java`, `…/enrich/OfferRefetch.java`
- [x] T10 · ISC-246 · server — `OfferRefetch` grows the later stages: only once `full_text` is stored, reset `content_at`/`fields_at` and run content, fields, `scoreFor`; no `@Transactional` anywhere in it (after: T6, T7, T8, T9) · `…/enrich/OfferRefetch.java`

### Server — probes

- [x] T11 · ISC-244 · server — probe: cached 403, stub now answers 200 (after: T9) · `…test/enrich/OfferRefetchTest.java`
- [x] T12 · ISC-245 · server — probe: robots-disallowed path; window spent by a parallel pass, answer 429 (after: T11) · `…test/enrich/OfferRefetchTest.java`
- [x] T13 · ISC-246 · server — probe: stubbed ad, stamps and score fresher than the call, body is the entry (after: T10, T12) · `…test/enrich/OfferRefetchTest.java`
- [x] T14 · ISC-247 · server — probe: 500 and timeout, offer kept, note replaced, status 200 (after: T13) · `…test/enrich/OfferRefetchTest.java`
- [x] T15 · ISC-248 · server — probe: a second unfetched offer byte-identical, `pipeline_run` count unchanged (after: T14) · `…test/enrich/OfferRefetchTest.java`
- [x] T16 · ISC-249 · server — probe: no scoring model configured, `full_text` stored and a rule score written (after: T15) · `…test/enrich/OfferRefetchTest.java`

### Web

- [x] T17 · ISC-246 · web — `refetch(id)` POST beside `rescore` (after: T9) · `frontend/src/app/core/api/shortlist.api.ts`
- [x] T18 · ISC-246 · web — `fetchRequested / fetched / fetchFailed`, `fetching` and `fetchError` state, an `exhaustMap` effect mirroring the rescore one; `fetched` replaces the entry (after: T17) · `frontend/src/app/core/store/shortlist.events.ts`, `frontend/src/app/core/store/shortlist.store.ts`
- [x] T19 · ISC-243 · [P] · web — `canFetch` computed (hardPass, `archivedAt` null, `url`, no `fullText`), the button beside the caption, `detail.fetchAgain` and `detail.fetching` keys · `frontend/src/app/features/offer-detail/offer-detail.ts`, `frontend/src/app/features/offer-detail/offer-detail.html`, `frontend/public/i18n/en.json`
- [x] T20 · ISC-243 · web — spec: the four-condition table (after: T19) · `frontend/src/app/features/offer-detail/offer-detail.spec.ts`
- [x] T21 · ISC-247 · web — the click dispatches `fetchRequested`, spinner while `fetching`, `fetchError` under the button; `error.fetch` key (after: T18, T19) · `frontend/src/app/features/offer-detail/offer-detail.ts`, `frontend/src/app/features/offer-detail/offer-detail.html`, `frontend/public/i18n/en.json`
- [x] T22 · ISC-247 · web — toasts on `fetched`: success when the ad arrived, warning with the note when the page refused again; `fetchFailed` raises none and stays inline, per `frontend/CLAUDE.md` (after: T21) · `frontend/src/app/core/toast/toast.store.ts`, `frontend/public/i18n/en.json`
- [x] T23 · ISC-247 · web — spec: a failure renders the reason and the detail stays (after: T20, T21) · `frontend/src/app/features/offer-detail/offer-detail.spec.ts`

### Docs

- [x] T24 · ISC-245 · docs — why the button bypasses the cache and neither robots nor the window, and why the window is shared (after: T4) · `docs/decisions/pipeline-enrich-content.md`
- [x] T25 · ISC-246 · docs — the per-offer path as a short sequence beside the rescore (after: T10) · `docs/BACKEND-FLOWS.md`
- [x] T26 · ISC-246 · docs — § Unreleased, Added: fetch the original ad again (after: T10) · `CHANGELOG.md`

### ISC-294 — added during the build

- [x] T27 · ISC-294 · server — `FETCHABLE` refuses an offer with `full_text`; the button writes with `RECORD_UNLESS_READ`, so a failed fetch cannot overwrite text that landed meanwhile; the 409 sentence names the case · `…/enrich/EnrichmentService.java`
- [x] T28 · ISC-294 · server — probes: an offer with text answers 409 and asks nobody; a failed fetch racing a stored one leaves the text (after: T27) · `…test/enrich/OfferRefetchTest.java`

## Probe Mapping

| Task | Claim | Probe (from `spec.md` § Test Strategy) |
|------|-------|----------------------------------------|
| T19, T20 | ISC-243 | render the detail for a passed, unarchived offer with a URL and no `full_text`, then flip each of the four conditions · `offer-detail.spec.ts` |
| T4, T5, T9, T11 | ISC-244 | cached 403, stub answers 200, POST `/offers/{id}/fetch` · `OfferRefetchTest` |
| T1, T2, T3, T12, T24 | ISC-245 | robots-disallowed path; window spent by a parallel pass · `OfferRefetchTest`, `RobotsPolicyTest` |
| T6–T10, T13, T17, T18, T25, T26 | ISC-246 | manual fetch of a stubbed ad page · `OfferRefetchTest` |
| T14, T21, T22, T23 | ISC-247 | stub answers 500 and times out; the store receives the failure · `OfferRefetchTest`, `offer-detail.spec.ts` |
| T15 | ISC-248 | two unfetched offers, fetch one · `OfferRefetchTest` |
| T27, T28 | ISC-294 | seed an offer with `full_text`, stub its page 500, POST; a failed write racing stored text · `OfferRefetchTest` |
| T16 | ISC-249 | manual fetch with no scoring model configured · `OfferRefetchTest` |
