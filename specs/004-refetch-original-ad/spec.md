---
task: "Fetch the original ad again for one offer, from the ad card"
slug: 004-refetch-original-ad
spec_type: feature
isa_master: ../../ISA.md
isa_feature: F32
constitution: ../constitution.md
phase: climbing
progress: 8/8
started: 2026-09-23T22:55:00Z
updated: 2026-09-24T00:40:10Z
principal_stated_goal: "feature Quellen per Button in der Kachel erneut einlesen, falls nicht vorhanden => “FROM THE SOURCE DOCUMENT. THE ORIGINAL AD WAS NOT FETCHED.”"
principal_stated_goal_source: prompt
principal_stated_goal_signal: 2
principal_stated_goal_locked: 2026-09-23T22:40:00Z
context_sufficient: true
interview_invoked: false
context_log: context.md
---

<!-- SPEC — a derived view of ../../ISA.md (feature F32). Claim IDs belong to the master.
     Sync: Skill("Spec", "sync 004-refetch-original-ad"). Never edit the master from this file.
     principal_stated_goal is the one German string in this folder: the format keeps the principal's
     words byte for byte, and they carry no value the constitution keeps out of specs/. -->

# 004 — Fetching the original ad again

## Problem

When enrichment cannot read an advert, the offer stays in the pipeline with a note, and the detail
page's ad card shows what the source document carried plus one caption: *"From the source document.
The original ad was not fetched."* That is the right answer for the night it happened. It is the
wrong answer a week later, and today nothing can change it.

Two things hold the failure in place. `EnrichmentService` selects offers with `enriched_at IS NULL`
and stamps `enriched_at` on a failed fetch too, so a failed offer is never due again. And
`PageCache` keeps a 403 or a robots refusal for its whole TTL on purpose, because a refusal is
usually a fact about the page. So even a reset stamp would read the cached failure back. A page that
answered with a transient error, or that only became reachable later, stays unfetched for good. The
offer is then scored on the newsletter's few lines, without the text that decides most of its
factors.

The operator is looking at that offer and knows something the tool does not: the link opens fine in
a browser now. There is no way to tell the tool that.

## Vision

He opens an offer, sees the caption under a thin summary, and next to it a button: fetch the ad
again. He presses it, the button turns into a spinner for a few seconds, and then the card fills
with the advert, its hidden sections folded away the way every other fetched ad looks, the extracted
fields appear in their panel, and the score moves to what the full text says. If the page still
refuses, the card stays as it was and the refusal sits beside the button in one sentence, the one
the tool recorded, not a generic error.

## Out of Scope

- Refetching an ad that *was* fetched: re-reading a changed or badly extracted advert is a different
  question, and it was the reading not chosen at the goal lock.
- A bulk "fetch every incomplete offer again", on any screen.
- Bypassing robots.txt or the fetch rate limit. The cache bypass is the whole point of the button;
  the other two are the reason the tool may fetch at all (master § Out of Scope: "`robots.txt` is
  respected, and a portal that refuses is simply a portal without enrichment").
- Offers without a URL. Their only text is the source document, so there is nothing to fetch.
- Any change to what the nightly run does. Its due query and its cache behaviour stay as they are.

## Constraints

- Rules before model (`CLAUDE.md` § Repo-wide invariants): without a language model the button still
  stores the advert and the rule score still moves.
- The local provider answers, with no automatic fallback (`CLAUDE.md` § Repo-wide invariants). The
  rescore this action triggers uses the model the run would use, never a billed one the operator did
  not pick.
- A per-offer action answers the whole `ShortlistEntry` so the browser replaces its row with what
  the server stored. That is the precedent `POST /api/v1/offers/{id}/score` sets.
- API paths are `/api/v1/…` (constitution § Adaptations, BE-API-03).
- JDBC through `JdbcClient`, no JPA (BE-DB-04). If a schema change turns out to be needed it is a new
  Flyway migration, never an edit of an applied one (BE-DB-03).
- Every UI string is English and lives in `public/i18n/en.json`. Feedback after the action goes
  through the toast layer from spec `002-action-feedback-toasts`.
- The spec and the code name no portal, host or model (constitution § What a spec may contain).

## Goal

An offer whose original ad was not fetched and that has a URL shows a button in the ad card; it
fetches that one URL now, past the cached failure and still honouring robots.txt, and on success the
caption gives way to the ad and the offer's content, fields and score are brought up to date.

## Claims

- [x] ISC-243: The ad card of an offer that cleared the hard filter, is not archived, has a URL and has no `full_text` shows a "Fetch the ad again" button beside the caption; every other offer shows none.
- [x] ISC-244: `POST /api/v1/offers/{id}/fetch` requests that offer's URL even when `fetched_page` holds a cached failure for it, and stores the new answer in `fetched_page`.
- [x] ISC-245: Anti: the manual fetch never requests a path robots.txt disallows and never exceeds the configured fetch rate limit.
- [x] ISC-246: A successful manual fetch stores `full_text` and the extracted enrichment fields, clears `enrichment_note`, re-runs segmentation, field extraction and scoring for that offer, and answers the whole `ShortlistEntry`. (after: ISC-244)
- [x] ISC-247: A failed manual fetch keeps the offer, replaces `enrichment_note` with the new reason, and the detail page shows that reason beside the button without blanking. (after: ISC-244)
- [x] ISC-248: Anti: the manual fetch changes no other offer's row, no cache entry but its own URL's, and starts no pipeline run.
- [x] ISC-249: With no language model configured, the manual fetch still stores the ad and rule-scores it. (after: ISC-246)
- [x] ISC-294: Anti: a manual fetch never removes an ad already stored: an offer with `full_text` answers 409 and costs no request, and a failed fetch racing a successful one leaves the stored text in place.

## Test Strategy

| isc | type | check | threshold | tool | anchors_to |
|---|---|---|---|---|---|
| ISC-243 | bun-test | render the detail for a passed, unarchived offer with a URL and no `full_text`, then flip each of the four conditions in turn | button in the first case only, absent in the other four | Vitest | `offer-detail.spec.ts` |
| ISC-244 | bun-test | cache a 403 for the ad URL, stub it to answer 200, POST `/offers/{id}/fetch` | 1 request to the URL; `fetched_page` row holds 200 | JUnit, WireMock, Testcontainers | `OfferRefetchTest` |
| ISC-245 | bun-test | manual fetch of a robots-disallowed path; manual fetch while a run has spent the shared window | 0 requests to the path; no request beyond the limit, answer 429 | JUnit, WireMock | `OfferRefetchTest`, `RobotsPolicyTest` |
| ISC-246 | bun-test | manual fetch of a stubbed ad page | `full_text` set, note null, `content_at`, `fields_at` and the score newer than the call's start; body is the entry | JUnit, WireMock, Testcontainers | `OfferRefetchTest` |
| ISC-247 | bun-test | stub answers 500 and times out; the store receives the failure | offer row kept, note replaced; detail still rendered with the reason | JUnit, WireMock, Vitest | `OfferRefetchTest`, `offer-detail.spec.ts` |
| ISC-248 | bun-test | two unfetched offers, fetch one | the other row byte-identical; `pipeline_run` count unchanged | JUnit, Testcontainers | `OfferRefetchTest` |
| ISC-249 | bun-test | manual fetch with no scoring model configured | `full_text` stored; a rule score written | JUnit, WireMock, Testcontainers | `OfferRefetchTest` |
| ISC-294 | bun-test | seed an offer with `full_text`, stub its page 500, POST `/offers/{id}/fetch`; run the button's failed write against an offer whose text landed meanwhile | 409, 0 requests to the page, `full_text` unchanged; text unchanged | JUnit, WireMock, Testcontainers | `OfferRefetchTest` |

## Decisions

- **2026-09-24 — The button runs the night's code for one id, and so it appears only where the night would fetch.** Every stage query filters on a passed, unarchived offer, so the button carries the same condition instead of processing an offer no stage would otherwise touch. That resolves the archived-offer fog as *no*: restoring the offer is what puts it back on the path. ISC-243 was narrowed to say so before any code existed.
- **2026-09-24 — The rate window is shared, and the button never waits for it.** The window moves out of the per-pass fetcher into one instance the run and the button both draw from, which is what makes ISC-245 true against a parallel run and a second click. The run keeps waiting for a permit because nobody is watching it; the button answers 429 with the reason at once, and writes nothing, because a refused permit is a fact about the minute, not about the page. That resolves the second fog line.
- **2026-09-24 — The button takes its permit before it reads robots.txt.** The run reads robots.txt once per host per pass, so its order stays robots, then permit. The button builds a fetcher per press, and robots-first let every press send the portal one request that no window counted, even while the minute stood spent (second look on ISC-245, adopted). Permit-first, a spent minute sends nothing at all, and robots.txt reads are bounded by the same window. A permit spent on a path robots then refuses is the price, and it is the cautious side of the trade.
- **2026-09-24 — The server refuses an offer that already has its ad, not only the button.** A fetch that fails records its reason over the enrichment columns, the text among them, so a request for an offer whose page answers 500 today would throw away an ad read fine last week. `EnrichmentService` therefore selects only offers without `full_text` and answers 409 for the rest, the same condition ISC-243 puts on the button and § Out of Scope puts on the feature. Found by the builder of the ISC-246 to ISC-249 probes, not by a probe.

- **2026-09-24 — refined: F32 gains ISC-294, and ISC-248 says what the code can keep.** A builder noticed that a failed fetch records its reason over the enrichment columns, `full_text` among them, so a press on an offer that already had its ad could throw it away; the server now refuses such an offer (409) and the button's write is a no-op once text has landed, which a second look found unprobed and so became ISC-294 rather than a comment. ISC-248 read "writes no row but its own offer's and its URL's cache entry", which the shared LLM budget row and the per-portal block-label cache contradict on every model stage; it now reads "changes no other offer's row, no cache entry but its own URL's", which the whole-row probe does check.

## Verification

- ISC-244 — `OfferRefetchTest.fetchesPastACachedFailureAndRemembersTheNewAnswer` green (1 request to the ad URL, `fetched_page` row 200); red with `runFor` mutated to the cached `fetch()`; second look Max pass (2026-09-24)
- ISC-245 — `OfferRefetchTest.neverAsksForAPathRobotsTxtDisallows` and `.answers429WithoutAnyRequestWhenARunHasSpentTheWindow` green; red with robots skipped on the fresh path, with a private window, and with robots before the permit; second look Max concerns, all adopted (2026-09-24)
- ISC-246 — `OfferRefetchTest.storesTheAdAndDerivesEverythingAgainForThatOffer` green (stamps and score after a DB `now()` start, entry body); `offer-detail.spec` 'replaces the caption…' and `shortlist.store.spec` 'keeps a fetch that lands late…' green; red without REDERIVE, without `content.runFor`, with an unguarded `fetched` reducer; second look Max concerns adopted (2026-09-24)
- ISC-247 — `OfferRefetchTest` 500 and timeout probes green (row kept, note replaced, 200 with the entry); `offer-detail.spec` 'keeps the offer on screen…' and `shortlist.store.spec` 'does not carry a refused fetch…' green; red without `record`, without the refusal line, with `fetchError` kept across offers (2026-09-24)
- ISC-248 — `OfferRefetchTest.writesNothingButItsOwnOfferAndItsOwnCacheEntryAndStartsNoRun` green (whole-row compare of a derived second offer, `pipeline_run` count, `fetched_page` holds one URL); red with REDERIVE on `id <> ?` once the second offer carried stamps (2026-09-24)
- ISC-249 — `OfferRefetchWithoutAModelTest.storesTheAdAndRuleScoresItWithNoModelConfigured` green (keyless config, `core_skill_overlap` naming Spring Boot from the page); red with `scoreFor` throwing without a judge (2026-09-24)
- ISC-294 — `OfferRefetchTest.refusesAnOfferThatAlreadyHasItsAdAndAsksNobody` and `.aFailedFetchLeavesTextThatLandedWhileItWasOut` green, the race bracketed by a WireMock transformer, three runs green; red without the lookup guard and with the unconditional write, twice each (2026-09-24)
- ISC-243 — `offer-detail.spec` 'offers to fetch the ad again only where the run would fetch it' green (button for the passed, unarchived, unfetched offer with a URL, absent under each of the four flips); red with `canFetch` ignoring `archivedAt`; second look Max: predicate equals `FETCHABLE` (2026-09-24)
