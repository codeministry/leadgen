---
spec: 001-interest-topics
plan: plan.md
updated: 2026-09-23
---

# Tasks 001 — Interest topics steer the score and the shortlist

**Purpose:** atomic, checkable steps. Each task hangs on exactly one claim ID from `spec.md`. This file defines
nothing, it decomposes.

## Legend

`[P]` = parallelizable, derived from the frontier on 2026-09-23 (takeable: ISC-191.1, ISC-200, ISC-201.1, ISC-201.2,
ISC-202.1) and from no other `[P]` task naming the same file. `(after: T…)` = must run after that task. Paths:
`J` = `backend/src/main/java/de/codeministry/leadgen`, `JT` = `backend/src/test/java/de/codeministry/leadgen`,
`F` = `frontend/src/app`.

## Tasks

### Stage 1 · the profile carries the topics

- [ ] T1 · ISC-191.1 · [P] — `Topic` record and the two lists on `SkillProfile`, name falls back as an alias ·
  `J/config/model/SkillProfile.java`
- [ ] T2 · ISC-191.1 · [P] — placeholder topic per list in the shipped profile ·
  `backend/src/main/resources/leadgen/skill-profile.yaml`
- [ ] T3 · ISC-191.1 — probe red then green: bind both lists, one entry without aliases (after: T1, T2) ·
  `JT/config/ConfigLoaderTest.java`
- [ ] T4 · ISC-191.2 — remove `antiSkills` from `MatchingRules` and its Javadoc (after: T1) ·
  `J/config/model/MatchingRules.java`
- [ ] T5 · ISC-191.2 — raw-tree pre-check in `ConfigLoader.read()` that refuses `anti_skills` with a message naming
  `disinterest_topics` (after: T4) · `J/config/ConfigLoader.java`
- [ ] T6 · ISC-191.2 — probe: a rules file still carrying the key is refused by name, not by Jackson (after: T5) ·
  `JT/config/ConfigLoaderTest.java`
- [ ] T7 · ISC-191.3 — drop the key from the shipped, demo and test rules files and the `role:` comment (after: T5) ·
  `backend/src/main/resources/leadgen/matching-rules.yaml`, `demo/matching-rules.yaml`,
  `backend/src/test/resources/filter/matching-rules.yaml`
- [ ] T8 · ISC-191.3 — `HardFilterTest` positional constructor loses the list (after: T4) ·
  `JT/filter/HardFilterTest.java`
- [ ] T9 · ISC-191.3 — `RulesView` and `/api/v1/rules` carry the two topic lists from the profile instead of
  `antiSkills` (after: T4) · `J/config/RulesView.java`, `J/web/ConfigController.java`
- [ ] T10 · ISC-191.3 — Rules screen panel shows the two lists; `rules-view.ts` model; both i18n catalogs (after: T9) ·
  `F/features/rules/rules.html`, `F/core/model/rules-view.ts`, `frontend/public/i18n/en.json`,
  `frontend/public/i18n/de.json`
- [ ] T11 · ISC-191.3 — `CHANGELOG.md` § Unreleased names the breaking change and the `/rules` shape (after: T9) ·
  `CHANGELOG.md`
- [ ] T12 · ISC-197.1 — `watchedFiles()` includes the profile; watcher Javadoc says four (after: T1) ·
  `J/config/ConfigLoader.java`, `J/config/ConfigWatcher.java`
- [ ] T13 · ISC-197.1 — probe: rewrite an alias, settle, poll, new snapshot (after: T12) ·
  `JT/config/ConfigWatcherTest.java`
- [ ] T14 · ISC-199.1 — `checkConsistency` takes the profile and refuses a topic named in both lists (after: T1) ·
  `J/config/ConfigLoader.java`
- [ ] T15 · ISC-199.2 — `checkConsistency` warns, with both names, when a topic alias equals a skill alias (after:
  T14) · `J/config/ConfigLoader.java`
- [ ] T16 · ISC-199.1 — probe: the cross-list shape is refused naming the topic (after: T14) ·
  `JT/config/ConfigLoaderTest.java`
- [ ] T17 · ISC-199.2 — probe: the shared-alias shape is accepted with one WARN line (after: T15) ·
  `JT/config/ConfigLoaderTest.java`

### Stage 2 · the score moves and the match is stored

- [ ] T18 · ISC-194.1 — migration V28: nullable `topic` on `offer_score_reason`, comment, index `(topic, offer_id)`
  (after: T3) · `backend/src/main/resources/db/migration/V28__score_reason_topic.sql`
- [ ] T19 · ISC-194.1 — `ScoreReason` gains `topic` and the `interest` / `disinterest` factories; writer and batch
  writer insert it; the reasons `SELECT` reads it (after: T18) · `J/score/ScoreReason.java`, `J/score/ScoreWriter.java`,
  `J/score/ScoreBatchService.java`, `J/offer/OfferQueryService.java`
- [ ] T20 · ISC-194.1 — probe: write a score with a topic row, read it back (after: T19) ·
  `JT/score/ScoringWithAModelTest.java`
- [ ] T21 · ISC-192 — `interest_fit` under `weights` in the shipped and demo rules files (after: T7, T20) ·
  `backend/src/main/resources/leadgen/matching-rules.yaml`, `demo/matching-rules.yaml`
- [ ] T22 · ISC-193 — `disinterest_fit` under `penalties` in the same two files (after: T21, T20) ·
  `backend/src/main/resources/leadgen/matching-rules.yaml`, `demo/matching-rules.yaml`
- [ ] T23 · ISC-192 — `RuleScorer.interestFit()` over `haystack()` via `matches()`, heaviest topic wins, `DETERMINISTIC`
  extended (after: T3, T19, T21) · `J/score/RuleScorer.java`
- [ ] T24 · ISC-193 — `RuleScorer.disinterestFit()`, one penalty per offer (after: T23, T22) · `J/score/RuleScorer.java`
- [ ] T25 · ISC-192 — probe red then green: same offer with and without an alias differs by exactly the bonus, reason
  names the topic (after: T23) · `JT/score/ScoringWithAModelTest.java`
- [ ] T26 · ISC-193 — probe: three aliases of one topic, one penalty row (after: T24) ·
  `JT/score/ScoringWithAModelTest.java`
- [ ] T27 · ISC-197.2 — profile digest recorded per run; `rescoreRules()` re-runs the deterministic half and re-totals
  with the stored judged rows, before `DUE` (after: T12, T20) · `J/score/ScoringService.java`
- [ ] T28 · ISC-197.2 — probe: alias change moves totals with zero judge calls; `version:` bump judges each passed offer
  once (after: T27) · `JT/score/ScoringWithAModelTest.java`
- [ ] T29 · ISC-201.1 — probe: judge disabled, alias topic rows identical (after: T24) ·
  `JT/score/ScoringWithoutAModelTest.java`
- [ ] T30 · ISC-200 — probe: corpus verdicts identical with and without topics (after: T3) ·
  `JT/filter/HardFilterCorpusTest.java`

### Stage 3 · everything that reads the stored rows

- [ ] T31 · ISC-195.1 — `topic` on `ShortlistQuery`, the controller param, and the `EXISTS` predicate in `where()`
  (after: T19) · `J/offer/ShortlistQuery.java`, `J/web/OfferController.java`, `J/offer/OfferQueryService.java`
- [ ] T32 · ISC-195.1 — probe: four bands returned, non-match not, cursor walks (after: T31) ·
  `JT/offer/OfferQueryServiceTest.java`
- [ ] T33 · ISC-201.2 — probe: `topic=` with no embedder configured answers 200 with the alias matches (after: T31) ·
  `JT/retrieval/SemanticSearchWithoutRetrievalTest.java`
- [ ] T34 · ISC-195.2 — `topic` in `ShortlistFilters`, `HttpParams`, the query-param input and the active chip (after:
  T31) · `F/core/model/shortlist-page.ts`, `F/core/api/shortlist.api.ts`, `F/features/shortlist/shortlist-page.ts`
- [ ] T35 · ISC-195.2 — facet fed by the rules view's topic names (after: T10, T34) ·
  `F/features/shortlist/facet-panel/facet-panel.ts`, `facet-panel.html`
- [ ] T36 · ISC-195.2 — probe: pick, save the view, reload, `topic=` both times (after: T35) ·
  `F/features/shortlist/shortlist-page.spec.ts`
- [ ] T37 · ISC-194.2 — positives cut-off skips `interest_fit` rows (after: T19) ·
  `F/features/shortlist/offer-card/offer-card.ts`
- [ ] T38 · ISC-194.2 — probe: four larger positives plus one topic row, topic rendered (after: T37) ·
  `F/features/shortlist/offer-card/offer-card.spec.ts`
- [ ] T39 · ISC-203 — fourth digest section, text and HTML, gated like the others (after: T19) ·
  `J/digest/DigestService.java`
- [ ] T40 · ISC-203 — probe: one sub-threshold offer with a topic row, one without (after: T39) ·
  `JT/digest/DigestServiceTest.java`

### Stage 4 · the judge is asked

- [ ] T41 · ISC-198.1 — `JUDGED` gains the two factors; `INSTRUCTIONS` lists the topics only when `weights.interest_fit`
  exists; `reasonsOf` merges by `max` (after: T23, T24) · `J/score/Judge.java`, `J/score/ChatClientJudge.java`,
  `J/score/AnthropicJudge.java`
- [ ] T42 · ISC-198.1 — probe: judged yes on an alias-matched topic yields one row, not two (after: T41) ·
  `JT/score/JudgeWireFormatTest.java`
- [ ] T43 · ISC-198.2 — probe: rules without the weight row, no topic question, no judged topic row (after: T41) ·
  `JT/score/JudgeWireFormatTest.java`

### Stage 5 · the paraphrase half, only if the floor exists

- [ ] T44 · ISC-196.1 — phrase mode in the measurement script with a labelled-sample summary (after: T3, T32) ·
  `docs/samples/measure_embeddings.ts`
- [ ] T45 · ISC-196.1 — run it on the real corpus; the floor lands in `config/` or the claim is dropped via Decisions
  (after: T44) · `config/` (operator-local), `spec.md` § Decisions
- [ ] T46 · ISC-196.1 — `TopicEmbeddings` cache keyed by model and text digest, copied from `ProfileEmbeddings` (after:
  T45) · `J/retrieval/TopicEmbeddings.java`
- [ ] T47 · ISC-196.1 — vector neighbourhood appended to the topic predicate when a model is configured, alias half
  alone otherwise (after: T46, T33) · `J/retrieval/SemanticFilter.java`, `J/offer/OfferQueryService.java`
- [ ] T48 · ISC-196.1 — probe: embedded paraphrase found, one embedding request for two requests (after: T47) ·
  `JT/retrieval/SemanticFilterTest.java`
- [ ] T49 · ISC-196.2 — probe: score identical before and after RETRIEVAL indexes the offer (after: T47) ·
  `JT/score/ScoringWithAModelTest.java`, `JT/retrieval/RetrievalIndexServiceTest.java`

### Cross-cutting

- [ ] T50 · ISC-202.2 — fictional topic per list in the demo profile (after: T2) · `demo/skill-profile.yaml`
- [ ] T51 · ISC-202.1 · [P] — operator-local grep of every configured name and alias over the tracked tree · (no repo
  file)
- [ ] T52 · ISC-204 — `WRITING-RULES.md` and `CONFIGURATION.md`: the two lists, the watched profile, the rescore; the
  `anti_skills` lines repointed (after: T11, T27) · `docs/WRITING-RULES.md`, `docs/CONFIGURATION.md`
- [ ] T53 · ISC-204 — reasoning per spec decision row in the scoring decision record; one rule line in `CLAUDE.md`
  (after: T52) · `docs/decisions/pipeline-scoring.md`, `CLAUDE.md`

## Probe Mapping

| Task                 | Claim     | Probe (from `spec.md` § Test Strategy)                                                                        |
|----------------------|-----------|---------------------------------------------------------------------------------------------------------------|
| T3                   | ISC-191.1 | bind a profile with both lists, one entry without aliases → bound; the name matches as an alias               |
| T6                   | ISC-191.2 | load a rules file still carrying `anti_skills` → refused, message names `disinterest_topics`                  |
| T7, T8, T9, T10, T11 | ISC-191.3 | `rg -n 'anti_skills\|antiSkills'` over the tracked tree → 0 hits outside `CHANGELOG.md` and `docs/decisions/` |
| T25                  | ISC-192   | same offer with and without an interest alias → totals differ by exactly the bonus                            |
| T26                  | ISC-193   | three aliases of one disinterest topic → one penalty row                                                      |
| T20                  | ISC-194.1 | write a score with a topic row and read it back → topic column populated                                      |
| T38                  | ISC-194.2 | four larger positives and one topic row → topic row rendered                                                  |
| T32                  | ISC-195.1 | `topic=` over four bands plus a non-match → four returned, cursor walks                                       |
| T36                  | ISC-195.2 | pick, save the view, reload → `topic=` both times                                                             |
| T48                  | ISC-196.1 | embedded paraphrase, no alias, twice → found; one embedding request                                           |
| T49                  | ISC-196.2 | rescore before and after RETRIEVAL indexes → identical `score_value`                                          |
| T13                  | ISC-197.1 | rewrite an alias, settle, poll → new snapshot has it                                                          |
| T28                  | ISC-197.2 | alias change then `version:` bump → 0 judge calls, then one per passed offer                                  |
| T42                  | ISC-198.1 | judged yes on an alias-matched topic → one row per topic                                                      |
| T43                  | ISC-198.2 | rules without `interest_fit` → no topic question, no judged topic row                                         |
| T16                  | ISC-199.1 | cross-list shape → refused naming the topic                                                                   |
| T17                  | ISC-199.2 | shared-alias shape → accepted, one WARN line                                                                  |
| T30                  | ISC-200   | corpus with and without topics → identical verdicts                                                           |
| T29                  | ISC-201.1 | judge disabled → alias topic rows byte-identical                                                              |
| T33                  | ISC-201.2 | embedder disabled → alias matches identical, 200                                                              |
| T51                  | ISC-202.1 | `git grep -wi` per configured name and alias → 0 hits (operator-local)                                        |
| T2, T50              | ISC-202.2 | `rg -n 'Example topic'` shipped profile; demo carries both lists                                              |
| T40                  | ISC-203   | one sub-threshold offer with a topic row, one without → one section, one entry                                |
| T52, T53             | ISC-204   | `WorkingNotesStaySmallTest` green; `rg interest_topics docs/WRITING-RULES.md` ≥ 1                             |
