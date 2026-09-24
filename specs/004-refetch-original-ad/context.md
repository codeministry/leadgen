---
spec: 004-refetch-original-ad
created: 2026-09-23T22:55:00Z
updated: 2026-09-24T00:00:00Z
rounds: 2
---

<!-- CONTEXT LOG — a record, not an authority. Nothing here gates anything and nothing
     reads it back. Every answer that changes the build lives in spec.md or plan.md. -->

# Context 004 — Fetching the original ad again

## Goal — confirmed 2026-09-23T22:40:00Z

An offer whose original ad was not fetched and that has a URL shows a button in the ad card; it
fetches that one URL now, past the cached failure and still honouring robots.txt, and on success the
caption gives way to the ad and the offer's content, fields and score are brought up to date.

Principal's words, verbatim: "feature Quellen per Button in der Kachel erneut einlesen, falls nicht vorhanden => “FROM THE SOURCE DOCUMENT. THE ORIGINAL AD WAS NOT FETCHED.”"

## Round 1 — before the spec, 2026-09-23T22:40:00Z

### Q1 · Which sentence should be the goal of spec 004?
- Offered: single-offer retry with content, fields and score brought up to date (recommended) | fetch
  only, the rest at the next run | the same plus a bulk "fetch all incomplete again" | a refetch button
  on every ad card, fetched or not
- Chosen: single-offer retry
- Landed in: ## Goal, ISC-243, ISC-246, § Out of Scope

### Q2 · After the button fetches the ad successfully, how do segmentation, fields and score catch up?
- Offered: inline in one request, answering the whole entry like `/score` (recommended) | fetch
  inline, the rest in an `@Async` worker | fetch inline, the rest at the next run
- Chosen: inline, one request
- Landed in: ISC-246

Read from the repo instead of asked: `EnrichmentService.DUE` never selects a stamped offer again;
`PageCache` keeps failures for the TTL; the fetcher's order is robots, then rate limit, then network;
`ContentService` and `FieldsService` have only batch `run()` entry points; `POST /{id}/score` already
sets the shape of a per-offer action that answers the whole entry.

## Round 2 — before the plan, 2026-09-24

### Q1 · How should the manual fetch run through the four stages?
- Offered: the night's code narrowed to one id, stamps reset, stages in the night's order (recommended) | a dedicated refetch service over extracted per-offer helpers
- Chosen: the night's code, one id
- Landed in: plan.md § Approach; spec.md § Decisions; ISC-243 narrowed to passed and unarchived offers

### Q2 · Where does the rate window live, so ISC-245 holds against a parallel run and repeated clicks?
- Offered: one shared window, the button never waits and answers 429 (recommended) | a fresh window per click plus a per-offer lock, weakening ISC-245
- Chosen: shared window
- Landed in: plan.md § Approach, § Interfaces; spec.md § Decisions

Read from the repo instead of asked: `AdFetcher` owned its window per pass; `ScoringService.rescore` throws `NoJudge` without a model, which ISC-249 cannot use; `frontend/nginx.conf` sets no read timeout.
