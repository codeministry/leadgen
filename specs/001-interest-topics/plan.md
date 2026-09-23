---
spec: 001-interest-topics
type: feature
status: approved
updated: 2026-09-23
---

# Plan 001 — Interest topics steer the score and the shortlist

**Purpose:** how the claims in `spec.md` get built. The what lives there; this file holds no acceptance criterion.

Paths below are relative to the repo root. `J` = `backend/src/main/java/de/codeministry/leadgen`,
`F` = `frontend/src/app`.

## Approach

Score first, vector last, in five stages that each land green on their own. **Stage 1** puts the two topic lists into
the profile, refuses `anti_skills` by name, and adds the profile to the watcher. **Stage 2** teaches `RuleScorer` the
two factors as a `bonus` and a `penalty` with the matched topic on the reason row (migration V28), and adds the
rules-only rescore, which is the one genuinely new path in the scoring package. **Stage 3** builds everything that
reads the stored rows: the `topic=` predicate in `OfferQueryService`, the facet, the card exemption and the digest
section. **Stage 4** gives the judge its per-topic question. **Stage 5** runs the phrase-mode measurement and, only if
a floor separates the sample, adds the vector neighbourhood to the `topic=` predicate.

Why this order and not filter-first: the filter reads rows the scorer writes, so building the filter first would mean
building it against rows that do not exist and testing it against fixtures that the scorer later has to reproduce.
Why the vector last: it is the only stage whose claim can die (the fog line in the spec), and nothing above it depends
on it. The one thing deliberately *not* built is a second matcher on the read side; the filter is a column predicate
and nothing else.

The rules-only rescore (ISC-197.2) is the part that decides whether the feature feels dynamic. It is a new loop next to
`ScoringService.run` that selects the working set, re-runs `RuleScorer.score` per offer, reads the stored judged rows
(`factor` not in `RuleScorer.DETERMINISTIC`) back from `offer_score_reason`, and calls `Score.of` over the union with
`score_model` and `ruleset_version` unchanged. It runs at the top of every `SCORE` stage over the offers whose stored profile digest differs from the running profile's, before `DUE` is evaluated, so a topic edit is reflected on the
next run without a model call and without changing what `DUE` means.

## Affected Files and Modules

| Path                                                                                                                                           | Change                                                                                                                                                                                                                                                                            | Claim                                      |
|------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------|
| `J/config/model/SkillProfile.java`                                                                                                             | `interestTopics`, `disinterestTopics` as `List<@Valid Topic>`; `Topic(@NotBlank name, @Min(1) @Max(10) weight, List<String> aliases)`, empty aliases fall back to the name like `Industry`                                                                                        | ISC-191.1                                  |
| `J/config/model/MatchingRules.java`                                                                                                            | remove `antiSkills`; Javadoc at :81 goes                                                                                                                                                                                                                                          | ISC-191.2                                  |
| `J/config/ConfigLoader.java`                                                                                                                   | before `read()` of the rules file, scan the raw tree for `anti_skills` and throw `ConfigValidationException` naming `disinterest_topics`; `watchedFiles()` adds the profile; `checkConsistency()` gains the profile argument, the cross-list refusal and the shared-alias warning | ISC-191.2, ISC-197.1, ISC-199.1, ISC-199.2 |
| `J/config/ConfigWatcher.java`                                                                                                                  | Javadoc "three files" → four; no logic change if `watchedFiles()` is the source                                                                                                                                                                                                   | ISC-197.1                                  |
| `J/config/RulesView.java`, `J/web/ConfigController.java`                                                                                       | `antiSkills` out; `interestTopics` / `disinterestTopics` in, sourced from the profile snapshot                                                                                                                                                                                    | ISC-191.3                                  |
| `J/score/ScoreReason.java`                                                                                                                     | `topic` component (nullable); factories `interest(topic, points)`, `disinterest(topic, points)`; `label` stays "interest: <name>" for the digest and `meta.json`                                                                                                                  | ISC-194.1                                  |
| `J/score/RuleScorer.java`                                                                                                                      | `interestFit()` and `disinterestFit()` over `haystack()` via `matches()`; heaviest topic wins per list; `DETERMINISTIC` gains both factors                                                                                                                                        | ISC-192, ISC-193                           |
| `J/score/ScoreWriter.java`                                                                                                                     | `INSERT` carries `topic`                                                                                                                                                                                                                                                          | ISC-194.1                                  |
| `J/score/ScoringService.java`                                                                                                                  | `rescoreRules(snapshot)` loop described above; profile digest recorded per offer; invoked from the `SCORE` stage before `DUE`                                                                                                                                                       | ISC-197.2                                  |
| `J/score/ScoreBatchService.java`                                                                                                               | batch rows carry `topic` through `writeAll`                                                                                                                                                                                                                                       | ISC-194.1                                  |
| `J/score/Judge.java`, `J/score/ChatClientJudge.java`, `J/score/AnthropicJudge.java`                                                            | `JUDGED` gains `interest_fit` / `disinterest_fit`; `INSTRUCTIONS` lists the configured topics only when the weight table carries `interest_fit`; `reasonsOf` merges a judged topic with an alias row for the same topic by `max`, never sum; bound from `boundsOf`                | ISC-198.1, ISC-198.2                       |
| `backend/src/main/resources/db/migration/V28__score_reason_topic.sql`                                                                          | `ALTER TABLE offer_score_reason ADD COLUMN topic text;` plus a COMMENT in the V16 style; index `(topic, offer_id)`                                                                                                                                                                | ISC-194.1, ISC-195.1                       |
| `J/offer/ShortlistQuery.java`, `J/offer/OfferQueryService.java`, `J/web/OfferController.java`                                                  | `topic` request param; `where()` gains `EXISTS (SELECT 1 FROM offer_score_reason r WHERE r.offer_id = o.id AND r.topic = :topic)`; reasons `SELECT` gains `topic`                                                                                                                 | ISC-195.1                                  |
| `J/retrieval/SemanticFilter.java`, `J/config/model/PipelineConfig.java` | `topicNeighbourhood(topic)`: an `OR` onto the topic predicate for adverts within `retrieval.topic_floor` of the topic's name, the phrase embedded through the existing `QueryEmbedder` (cached per model, budget on a miss); no model, no floor or no budget → empty, never `RetrievalUnavailable`. `Retrieval` gains `topicFloor` | ISC-196.1, ISC-196.2, ISC-201.2 |
| `J/digest/DigestService.java`                                                                                                                  | fourth section after "For review": offers with `score_band = 'DISCARDED'` that carry an `interest_fit` row, gated like the others; text and HTML renderers                                                                                                                        | ISC-203                                    |
| `F/core/model/rules-view.ts`, `F/features/rules/rules.html`, `frontend/public/i18n/en.json`, `de.json`                                         | anti-skills panel becomes two topic lists; key `rules.antiSkills` replaced by `rules.interestTopics` / `rules.disinterestTopics` in both catalogs                                                                                                                                 | ISC-191.3                                  |
| `F/core/model/shortlist-page.ts`, `F/core/api/shortlist.api.ts`, `F/features/shortlist/shortlist-page.ts`, `F/features/shortlist/facet-panel/` | `topic` in `ShortlistFilters`, `HttpParams`, the query-param input, the active chip, and a facet fed by the rules view's topic names                                                                                                                                              | ISC-195.2                                  |
| `F/features/shortlist/offer-card/offer-card.ts`                                                                                                | positives cut-off skips rows whose `factor` is `interest_fit`; penalties already shown                                                                                                                                                                                            | ISC-194.2                                  |
| `backend/src/main/resources/leadgen/skill-profile.yaml`, `demo/skill-profile.yaml`                                                             | placeholder topic per list in the shipped file; fictional topics per list in the demo file                                                                                                                                                                                        | ISC-202.2                                  |
| `backend/src/main/resources/leadgen/matching-rules.yaml`, `demo/matching-rules.yaml`, `backend/src/test/resources/filter/matching-rules.yaml`  | `anti_skills` block and the `role:` comment referencing it removed; `interest_fit` under `weights`, `disinterest_fit` under `penalties`                                                                                                                                           | ISC-191.3, ISC-192, ISC-193                |
| `backend/src/test/java/.../filter/HardFilterTest.java`                                                                                         | positional `MatchingRules` constructor loses the list                                                                                                                                                                                                                             | ISC-191.3                                  |
| `docs/samples/measure_topic_floor.ts` | new: precision and recall per floor from a labelled Markdown sample | ISC-196.1 |
| `CHANGELOG.md`                                                                                                                                 | Unreleased: `anti_skills` removed, refused by name; the two lists; `topic=`; the digest section                                                                                                                                                                                   | ISC-191.3                                  |
| `docs/WRITING-RULES.md`, `docs/CONFIGURATION.md`, `docs/decisions/pipeline-scoring.md`, `CLAUDE.md`                                            | the two lists, the watched profile, the rules-only rescore; reasoning per decision row of the spec; one rule line                                                                                                                                                                 | ISC-204                                    |

## Data Model

| Table / file                                | Before                                                                                       | After                                                                                                    |
|---------------------------------------------|----------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------|
| `offer_score_reason`                        | `(id, offer_id, factor, label, points, max_points, position)`                                | `+ topic text NULL`, set on `interest_fit` and `disinterest_fit` rows only                               |
| `offer` | `ruleset_version`, `score_model` | `+ profile_digest text NULL` per offer (V28), the profile its deterministic half was computed against; a mismatch drives the rules-only re-total. Per offer rather than per run, so it heals itself the way `ruleset_version` does |
| `skill-profile.yaml`                        | `identity, core, strong, peripheral, industries, reference_projects, languages, cv_variants` | `+ interest_topics[], disinterest_topics[]`, each `{name, weight, aliases[]}`                            |
| `matching-rules.yaml`                       | `scoring.weights` six keys, `scoring.penalties` three keys, `anti_skills[]`                  | `weights.interest_fit`, `penalties.disinterest_fit`; `anti_skills` gone                                  |

## Interfaces

| Contract                                     | Before                                                              | After                                                                                                    | Who calls it                                                                               |
|----------------------------------------------|---------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------|
| `GET /api/v1/offers`                         | filters `q, band, source, minScore, maxScore, semantic, similar, …` | `+ topic=<name>` (single value, repeatable is out of scope)                                              | `shortlist.api.ts`, the MCP server's `leadgen_search_offers` (unchanged unless it opts in) |
| `GET /api/v1/rules`                          | `antiSkills: string[]`                                              | `interestTopics: [{name, weight}]`, `disinterestTopics: [...]`                                           | `config.api.ts`; the MCP server reads `/rules` too and must not bind `antiSkills`          |
| `offer` reasons in list and detail responses | `{factor, label, points, maxPoints}`                                | `+ topic`                                                                                                | offer card, offer detail                                                                   |
| judge reply JSON                             | `role_fit` + three penalties                                        | `+ interest_fit`, `disinterest_fit` as `{topic, points}` arrays, present only when the weight row exists | `ChatClientJudge.reasonsOf`                                                                |
| digest text and HTML                         | three sections                                                      | four                                                                                                     | `DigestService` consumers                                                                  |

The MCP repo is the fourth consumer the chart checklist does not cover: `/rules` losing `antiSkills` is the breaking
half and is called out in the changelog line.

## Migration and Rollback

Expand–contract, in three steps that ship as one PATCH:

1. **Expand.** V28 adds the nullable `topic` column and its index; every existing row keeps `NULL`. Reads tolerate
   `NULL` everywhere. This step is safe to deploy alone.
2. **Migrate.** Nothing to backfill: topic rows appear as the working set is rescored, which the rules-only rescore
   does on the first run after deploy (every offer's `profile_digest` is `NULL`, so every scored offer is re-totalled once).
3. **Contract.** `anti_skills` is refused at load. This is the only step that can stop a deployment: an operator who
   deploys without moving the entries gets a refusal naming `disinterest_topics` at startup, not a running instance
   with silently different scores.

Rollback: `git revert` of the release and `DELETE FROM flyway_schema_history WHERE version = '28'; ALTER TABLE
offer_score_reason DROP COLUMN topic;` run once against the instance. Lost by running it: the topic column's contents
(recomputed by the next rescore under the new release) and any `interest_fit` / `disinterest_fit` rows, which the
reverted scorer never wrote and never reads. The profile file keeps its two lists and is ignored by the reverted
loader only if `FAIL_ON_UNKNOWN_PROPERTIES` is satisfied, so the rollback note tells the operator to remove them or
the old jar refuses the profile.

## Risks

| Risk                                                                              | Blast radius                                | Early warning                                                                                       | Mitigation                                                                                                                                                            |
|-----------------------------------------------------------------------------------|---------------------------------------------|-----------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| The rules-only rescore re-totals with judged rows from an older `ruleset_version` | every scored offer                          | `ScoringWithAModelTest` for ISC-197.2 compares totals before and after with judged rows pinned      | the rescore never touches `ruleset_version` or `score_model`; the judged rows are the ones stored, whatever version wrote them, which is the contract the spec states |
| `ILIKE`-style matching sneaks into the read side during stage 3                   | filter and scorer disagree                  | `OfferQueryServiceTest` for ISC-195.1 builds fixtures from the scorer, never from hand-written rows | the predicate is `EXISTS` over `offer_score_reason.topic`; no text predicate is written                                                                               |
| A profile edit triggers a full re-judge through `DUE`                             | `llm.budget` for the day                    | `ScoringWithAModelTest` for ISC-197.2 counts judge calls                                            | `ruleset_version` stays derived from `rules.version()` only                                                                                                           |
| The judge learns topics before the weight row exists                              | two scales under one threshold              | `JudgeWireFormatTest` for ISC-198.2                                                                 | the topic block in `INSTRUCTIONS` is emitted only when `weights.interest_fit` is present                                                                              |
| Phrase-against-advert cosine does not separate                                    | ISC-196.1 dies                              | the phrase-mode run in stage 5                                                                      | the claim has a kill condition in the fog line; stages 1–4 do not depend on it                                                                                        |
| Native image drops the new nested record                                          | `interest_topics` unbound in the image only | `backend/smoke/smoke.sh` on the built image                                                         | `Topic` follows `Industry`; if `Industry` needed a hint, `LeadGenRuntimeHints` gets one                                                                               |
| `WorkingNotesStaySmallTest` goes red on the `CLAUDE.md` line                      | CI                                          | the test itself                                                                                     | the rule line is one sentence; the reasoning goes to `docs/decisions/pipeline-scoring.md`                                                                             |

## Open Points

- fog (spec `## Not yet specified`): the similarity floor for a topic phrase against an advert. Resolved by the stage 5
  measurement. Resolved 2026-09-23: 0.48 in the operator's `config/`, provisional, see spec.md § Decisions.

## Conformance Impact

| Baseline row                   | Effect                                                                                                                         |
|--------------------------------|--------------------------------------------------------------------------------------------------------------------------------|
| G-FE-01 browser tier           | leaves it: no browser spec is added, the facet and card changes are covered by Vitest                                          |
| G-FE-02 i18n parity            | leaves it: the two new keys land in `en.json` and `de.json` in the same change                                                 |
| FE-TST-05 coverage ratchet     | leaves it: new specs raise the number, no threshold is added                                                                   |
| BE-ARCH-01..03 vertical slices | leaves it: the neighbourhood sits in `retrieval/`, the rescore in `score/`, nothing crosses a package it did not already cross |
