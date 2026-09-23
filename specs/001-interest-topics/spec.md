---
task: "Interest topics steer the score and the shortlist"
slug: 001-interest-topics
spec_type: feature
isa_master: ../../ISA.md
isa_feature: F29
constitution: ../constitution.md
phase: complete
progress: 24/24
started: 2026-09-22T13:00:00Z
updated: 2026-09-23T12:00:00Z
context_sufficient: true
interview_invoked: false
context_log: context.md
---

<!-- SPEC — a derived view of ../../ISA.md (feature F29). Claim IDs belong to the master.
     Sync: Skill("Spec", "sync 001-interest-topics"). Never edit the master from this file.
     principal_stated_goal is deliberately absent: the principal's words name fields of interest,
     which the constitution keeps out of specs/. They are verbatim in the master's ## Decisions. -->

# 001 — Interest topics steer the score and the shortlist

## Problem

The score is built from skill overlap, rate, seniority, project setup and industry, plus the judge's reading of role
fit. None of them knows what the operator actually wants to work on. Two consequences follow. An offer in a field he is
actively seeking lands below the review threshold, next to offers he would never take, and nothing on the shortlist
tells the two apart. And the configuration already has a list of skills that should sink an offer, `anti_skills`, but
it is loaded, shown on the Rules screen and read by no scorer. The one lever that exists does nothing.

## Vision

He names a field he wants more of and a field he wants none of, once, in the profile. The next morning the offers that
touch the first have moved up, and each one says why: "+12 interest: <topic>". The ones that touch the second have
dropped. The digest carries a short block of offers that are still under the line but touch a topic, because being
interesting and scoring high are not the same claim. And when he edits an alias, the shortlist reflects it on the next
run without a single model call.

## Out of Scope

- **Getting past the hard filter.** A topic raises or lowers a score. It never lets an offer through a knockout,
  `NO_CORE_SKILL` included. That was decided on 2026-09-22, and an offer outside the core stack stays out.
- **A package on a topic alone.** A bonus of the shipped size moves an offer into the review band, not into
  `auto_shortlist`. "Possibly interesting" is the review band, and the package stays a decision.
- **Editing topics in the browser.** That is spec 002. Here the topics live in the profile file, which joins the hot
  reload.
- **Learning from the operator's decisions.** Archive, status and won/lost feeding back into the score is a later spec.
  It needs decided applications as data, and `docs/decisions/retrieval.md` § What was refused already explains why
  retrieved examples do not belong in the judge.
- **A hard-filter-rejected offer.** It is not in the working set, so neither the score nor the filter can reach it.
- **Topic values in the repository.** Every real topic lives in `config/`; the shipped profile carries a placeholder
  per list, the way it carries a placeholder industry, and the demo profile a fictional one.

## Constraints

- Rules before model. The alias half scores and filters with no language model at all.
- The weight table decides and the answer does not. Every topic effect, deterministic or judged, is bounded by
  `matching-rules.yaml` § scoring and clamped in `Score`.
- **A topic effect is additive, never a share.** Interest is a `ScoreReason.bonus`, disinterest a `ScoreReason.penalty`;
  neither enters the attainable pool, for the reason `project_setup` left it (`docs/decisions/pipeline-scoring.md`).
- **One matcher.** The scorer decides which topics an offer names and stores the answer; the filter reads what was
  stored and matches nothing itself. A second matcher on the read side would be a second answer.
- **A vector narrows and never ranks** (`docs/decisions/retrieval.md` § The search narrows; it does not rank). The
  retrieval vector is written *after* `SCORE`, so a score cannot depend on it. The paraphrase half acts on the filter
  only, and it costs one embedding per topic per process, cached by model and text digest like the reference pitches,
  taking from `llm.budget` on a miss. A spent budget leaves the alias half standing.
- A similarity floor is measured before it acts (`CLAUDE.md` invariant on vector thresholds).
- **One scale per judged offer.** The judge sees topics only through a weight-table row, and a weight-table row is a
  rules change, which the operator ships with a `version:` bump. No offer is judged on a prompt its `ruleset_version`
  does not describe.
- A hot profile changes more than topics: the hard filter's core-skill list and the judge's profile summary follow the
  file between runs. That is accepted, and stated here so nobody is surprised.
- Removing `anti_skills` is a breaking configuration change and, pre-1.0, a PATCH with a line in `CHANGELOG.md`.
- Anything read by a name computed at runtime gets a hint in `LeadGenRuntimeHints`; a new nested profile record
  follows whatever `Industry` does for the native image.

## Goal

An offer that names a configured interest topic carries a bonus that lifts it, one that names a disinterest topic
carries a penalty that sinks it, the reason names the topic, and the shortlist can be filtered by topic across every
band, so an offer the operator wants to see reaches the review band on the score or the screen on the filter, and never
a package on either.

## Features

### F29 · Interest topics steer the score and the shortlist

**Why:** An offer in a field the operator is seeking and an offer he would never take currently look the same below the
line; a topic is the smallest statement that tells them apart.

- [x] ISC-191.1: `skill-profile.yaml` carries an `interest_topics` list and a `disinterest_topics` list; each entry has
  a name, optional aliases and a weight 1..10, and the name is always tried as an alias, as an industry's is.
- [x] ISC-191.2: `matching-rules.yaml` no longer carries `anti_skills`, and a file that still does is refused at load
  with a message naming `disinterest_topics` in the profile, never with Jackson's "unrecognized field". (after:
  ISC-191.1)
- [x] ISC-191.3: Every reader of `anti_skills` is gone or repointed: the Rules screen's anti-skills panel shows the
  profile's two topic lists instead, its i18n keys follow, the demo and test rules files and the `role:` comment in the
  shipped rules file no longer name it, and `CHANGELOG.md` § Unreleased names the breaking change. (after: ISC-191.2)
- [x] ISC-192: An offer whose haystack, the same title, description, advert text and tags the skill overlap reads, names
  an interest topic carries an `interest_fit` bonus of `scoring.weights.interest_fit × weight/10` for the heaviest
  matching topic, so its total is higher by exactly that bonus than the same offer without the match. (after: ISC-191.1,
  ISC-194.1)
- [x] ISC-193: An offer that names a disinterest topic carries one `disinterest_fit` penalty of
  `scoring.penalties.disinterest_fit × weight/10` for the heaviest matching topic, once per offer whatever the number of
  aliases hit. (after: ISC-191.1, ISC-194.1)
- [x] ISC-194.1: The bonus and the penalty rows each carry the matched topic's name in a structured column of
  `offer_score_reason`, not only in the label, so a filter can read it. (after: ISC-191.1)
- [x] ISC-194.2: The shortlist card shows a topic reason whatever its rank among the positives, the way it already shows
  every penalty. (after: ISC-194.1)
- [x] ISC-195.1: `GET /api/v1/offers?topic=<name>` narrows the working set to offers whose stored topic rows name that
  topic, in every band including UNSCORED and DISCARDED, and composes with the other filters and the keyset page
  unchanged. (after: ISC-194.1)
- [x] ISC-195.2: The shortlist's facet panel offers the configured topics as a filter, and a saved view keeps it.
  (after: ISC-195.1)
- [x] ISC-196.1: `topic=` also returns an offer whose retrieval vector sits within the measured floor of the topic's
  embedding and whose text names no alias; the topic vector is embedded once per model and text digest. (after:
  ISC-195.1)
- [x] ISC-196.2: Anti: indexing an offer's retrieval vector, or changing the floor, changes a score value. (after:
  ISC-196.1)
- [x] ISC-197.1: `skill-profile.yaml` is in `ConfigLoader.watchedFiles`, and a saved change to it is picked up without a
  restart under the same settle-and-reload rules as the rules file. (after: ISC-191.1)
- [x] ISC-197.2: A profile change re-runs the deterministic half over the working set on the next run and re-totals each
  offer with its stored judged rows, with no model call and `score_model` unchanged; only a `version:` bump in the rules
  file makes an offer due for the judge again. (after: ISC-194.1, ISC-197.1)
- [x] ISC-198.1: The judge is asked, per configured topic, whether the advert is about it; a judged yes yields the same
  `interest_fit` bonus or `disinterest_fit` penalty as an alias match for that topic, and an offer matched both ways
  carries the effect once, never twice. (after: ISC-192, ISC-193)
- [x] ISC-198.2: Anti: an offer carries a judged topic row under a `ruleset_version` whose weight table has no
  `interest_fit` row. (after: ISC-198.1)
- [x] ISC-199.1: A profile naming one topic in both lists is refused at load with a message naming the topic. (after:
  ISC-191.1)
- [x] ISC-199.2: A topic alias that is also a skill alias is warned about at load, with both names, and accepted.
  (after: ISC-191.1)
- [x] ISC-200: Anti: adding topics to the profile changes any `FilterStage` verdict on the sample corpus.
- [x] ISC-201.1: Anti: with no chat model configured, the `interest_fit` and `disinterest_fit` rows written for an offer
  differ from the alias rows written with one.
- [x] ISC-201.2: Anti: with no embedding model configured, `topic=` returns fewer alias-matched offers than with one, or
  answers anything but 200.
- [x] ISC-202.1: Anti: a topic name or alias from the operator's `config/` profile appears in a tracked file.
- [x] ISC-202.2: The shipped profile carries one placeholder topic per list, named as its placeholder industry is, and
  the demo profile one fictional topic per list, so the structure is documented where the other structures are. (after:
  ISC-191.1)
- [x] ISC-203: The digest carries a section of offers below the review threshold that carry an `interest_fit` row, each
  with its topic, and an offer below the threshold with no topic row still gets no section. (after: ISC-194.1)
- [x] ISC-204: `docs/WRITING-RULES.md` and `docs/CONFIGURATION.md` describe both lists, the watched profile and the
  rules-only rescore, the reasoning lands in `docs/decisions/pipeline-scoring.md`, the root `CLAUDE.md` gains at most
  one rule line, and `WorkingNotesStaySmallTest` stays green. (after: ISC-191.3)

## Test Strategy

| isc       | type     | check                                                                                                 | threshold                                                                                    | tool      | anchors_to                                                            |
|-----------|----------|-------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------|-----------|-----------------------------------------------------------------------|
| ISC-191.1 | bun-test | bind a profile with both lists, one entry without aliases                                             | bound; the name matches as an alias                                                          | JUnit     | `ConfigLoaderTest`, `SkillProfile`                                    |
| ISC-191.2 | bun-test | load a rules file still carrying `anti_skills`                                                        | refused, message names `disinterest_topics`                                                  | JUnit     | `ConfigLoaderTest`                                                    |
| ISC-191.3 | bash     | `rg -n 'anti_skills\|antiSkills'` over the tracked tree                                               | 0 hits outside `CHANGELOG.md` and `docs/decisions/`                                          | rg        | `RulesView`, `rules.html`, `demo/matching-rules.yaml`, `CHANGELOG.md` |
| ISC-192 | bun-test | score one offer with and without an interest alias, judged rows fixed | totals differ by exactly the bonus; reason `interest_fit` names the topic | JUnit | `ScoringWithTopicsTest`, `RuleScorer` |
| ISC-193 | bun-test | score an offer naming three aliases of one disinterest topic | one penalty row, points = bound × weight/10 | JUnit | `ScoringWithTopicsTest`, `RuleScorer` |
| ISC-194.1 | bun-test | write a score with a topic row and read it back | topic column populated | JUnit | `ScoringWithTopicsTest`, `ScoreWriter`, migration `V28` |
| ISC-194.2 | bun-test | render a card with four larger positives and one topic row                                            | topic row rendered                                                                           | Vitest    | `offer-card.spec.ts`                                                  |
| ISC-195.1 | bun-test | page `topic=` over a DISCARDED, an UNSCORED, a REVIEW and a SHORTLISTED match plus a non-match        | four returned, one not; the cursor walks                                                     | JUnit     | `OfferQueryServiceTest`                                               |
| ISC-195.2 | bun-test | pick a topic in the facet panel, save the view, reload                                                | `topic=` in the query string both times                                                      | Vitest    | `shortlist-page.spec.ts`, `filter-views`                              |
| ISC-196.1 | bun-test | filter by a topic over an offer within the floor naming no alias, and with the phrase unavailable | found beside the alias match; alias matches alone when the phrase cannot be embedded | JUnit | `SemanticFilterTest`, `QueryEmbedder`, `docs/samples/measure_topic_floor.ts` |
| ISC-196.2 | bun-test | score an offer, index its retrieval vector, force a re-total | identical `score_value` | JUnit | `ScoringWithTopicsTest` |
| ISC-197.1 | bun-test | rewrite a topic alias, settle, poll                                                                   | the new snapshot has the alias                                                               | JUnit     | `ConfigWatcherTest`                                                   |
| ISC-197.2 | bun-test | change an alias, run; then bump `version:`, run | first run: totals move, judge called 0 times; second run: judge called once per passed offer | JUnit | `ScoringWithTopicsTest`, `ScoringService` |
| ISC-198.1 | bun-test | judge answers a topic for an alias-matched offer and for one with no alias | one topic row each, never two for one topic | WireMock | `ScoringWithTopicsTest`, `ChatClientJudge` |
| ISC-198.2 | bun-test | judge an offer under a rules file without the `interest_fit` weight | no topic question in the prompt, no judged topic row | WireMock | `ScoringWithTopicsTest` |
| ISC-199.1 | bun-test | load a profile naming one topic in both lists                                                         | refused, message names the topic                                                             | JUnit     | `ConfigLoaderTest`                                                    |
| ISC-199.2 | bun-test | load a profile whose topic alias equals a core-skill alias                                            | accepted, one WARN line naming both                                                          | JUnit     | `ConfigLoaderTest`                                                    |
| ISC-200 | bun-test | judge a no-core-skill offer naming an interest topic and a passing offer naming a disinterest topic, with and without topics | identical verdicts, still `NO_CORE_SKILL` | JUnit | `HardFilterTest` |
| ISC-201.1 | bun-test | score the same offers with the judge disabled                                                         | alias topic rows byte-identical to the judged run                                            | JUnit     | `ScoringWithoutAModelTest`                                            |
| ISC-201.2 | bun-test | `topic=` with the embedder disabled                                                                   | alias matches identical, 200                                                                 | JUnit     | `SemanticSearchWithoutRetrievalTest`, `RetrievalWithoutAModelTest`    |
| ISC-202.1 | bash | for each name and alias in the operator's profile: `git grep -wi` over `specs/` | 0 hits (operator-local; vacuous in CI). Generic stack names the shipped files carried before this feature are not operator values and are not searched for outside `specs/` | git grep | `config/`, `specs/` |
| ISC-202.2 | bash     | `rg -n 'Example topic' backend/src/main/resources/leadgen/skill-profile.yaml demo/skill-profile.yaml` | 2 files, both lists                                                                          | rg        | shipped and demo profile                                              |
| ISC-203   | bun-test | render the digest over one sub-threshold offer with a topic row and one without                       | one section with one entry naming the topic                                                  | JUnit     | `DigestService`, `DigestServiceTest`                                  |
| ISC-204   | bun-test | `./gradlew :backend:test --tests WorkingNotesStaySmallTest` after the docs change                     | green; `rg interest_topics docs/WRITING-RULES.md` ≥ 1                                        | JUnit, rg | `WorkingNotesStaySmallTest`, `docs/WRITING-RULES.md`                  |

## Decisions

- **2026-09-23 — The floor was measured from labels and is provisional, and the fog is closed.** The phrase-against-advert comparison was run on the deployed instance: the topic's name embedded as `QueryEmbedder` does, ranked against the 122 stored retrieval vectors (of 2471 offers), the 20 nearest without an alias labelled by the operator per topic. For the first topic a floor of 0.484 keeps 4 of 5 right and finds 4 of the 6 labelled positives, one of them a discarded offer at 39 — the case this spec exists for; the second topic adds nothing above that floor. The configured value is 0.48, between the last wrong offer (0.474) and the last right one (0.484), in the operator's `config/`; the shipped default leaves it unset. The sample is small, so the number is re-measured after the retrieval backfill with the same labels as a start. A new `TopicEmbeddings` cache was not needed: `QueryEmbedder` already caches a phrase per model and takes from the budget only on a miss.
- **2026-09-22 — The semantic half narrows the filter and never moves a score.** The operator chose aliases plus the
  retrieval vector. The vector is written after `SCORE` and `retrieval.md` refuses to let a vector rank, so the
  paraphrase match lands in the topic filter (ISC-196.1) while the score moves on aliases and the judge alone.
- **2026-09-22 — Topics live in the profile, and the profile joins the hot reload.** They describe what the operator
  wants, which is what `skill-profile.yaml` holds next to `core`, `strong` and `industries`. `matching-rules.yaml` was
  the alternative because it is already watched. It was declined because it would file a preference under rules.
  `ConfigLoader.watchedFiles()` gains the profile instead (ISC-197.1).
- **2026-09-22 — A topic is a bonus or a penalty, never a share.** Inside the attainable pool a matched topic moves
  the score by (w/ (A+w))· (p/w − E/A): it lifts a weak offer most, lifts a strong one barely, and lowers an offer whose
  existing share exceeds the topic's weight; measured on the shipped table, a 60 drops to 54 on a weight-3 match.
  `project_setup` left the pool for the same shape. Additive, the effect is the number on the card, and with the
  shipped `review: 50` a +15 bonus moves a 47 into the review band and never into a package, which is what "possibly
  interesting" asks for. (Review finding, 2026-09-22.)
- **2026-09-22 — The scorer stores the match and the filter reads it.** Two matchers, the folded word-boundary pattern
  in `RuleScorer` and `ILIKE` on the read side, give two answers for one alias, and "the alias half filters as it
  scores" cannot then be tested. Stored at scoring time, the filter is a column predicate: any band, no model call, and
  the digest and `meta.json` carry the topic for free. The price is a migration and that a new topic reaches the filter
  only after the working set is rescored, which ISC-197.2 makes free.
- **2026-09-22 — A profile change rescores without the judge.** Nothing in the hot reload moved `ruleset_version`,
  and a full re-judge per topic edit would cost one model call per offer in the working set. The judged rows are
  already stored per offer, so the deterministic half is re-run and re-totalled against them at zero calls; the
  alternative, `ruleset_version = rules.version.profile.version` with a deliberate bump, was declined because nothing
  would move without the bump and everything would be paid for with it. The judged half stays as it is until the rules
  file's `version:` moves, which is the contract `docs/WRITING-RULES.md` already states.
- **2026-09-22 — The judge keeps a topic question, under three guards.** The review argued to drop it: a changed
  prompt sits on no staleness key, and a judge told about topics with no factor to answer in can only bend `role_fit`.
  The operator's request names "matching über die KI", so the factor stays and the objections are met one by one: the
  judge gets its own per-topic answer rather than a hint, the answer is worth exactly what an alias match is worth and
  never adds to it, and the question exists only when the weight table carries the row, which is a rules change
  shipped with a `version:` bump that re-judges the working set on one scale.
- **2026-09-22 — `anti_skills` is refused by name, not read as an alias.** Reading the old key as the disinterest
  list would silently activate a list that never applied and charge a body mention of a foreign stack twice, next to
  the judge's `stack_mismatch_dominant`. Refusing the file with a message naming the new home makes the operator move
  the entries once, on purpose, and changes no score behind his back. Twelve consumers hang on the key; ISC-191.3 names
  them.
- **2026-09-22 — Multiplicity: heaviest topic wins, once per list.** The industries precedent (best by weight) rather
  than the skills precedent (saturating sum); both lists may fire on one offer; a penalty is charged once whatever the
  number of aliases hit. That keeps "the reason names the topic" singular.
- **2026-09-22 — `principal_stated_goal_source` dropped from the frontmatter.** The value `master` is not in the
  format's enum and the literal itself is absent by constitution; the header comment says where the words are.

## Verification

- ISC-191.1 — ConfigLoaderTest.bindsBothTopicListsAndTriesANameWithoutAliasesAsItsOwnSpelling — ./gradlew check green 2026-09-23
- ISC-191.2 — ConfigLoaderTest.refusesTheRetiredAntiSkillsKeyByNamingWhereItWent — message names disinterest_topics, not Jackson
- ISC-191.3 — rg 'anti_skills|antiSkills' over the tracked tree: hits only in CHANGELOG.md, specs/, docs/decisions/ and the retired-key refusal (ConfigLoader.RETIRED + its test)
- ISC-197.1 — ConfigWatcherTest.picksUpAChangedTopicInTheProfileWithoutARestart
- ISC-199.1 — ConfigLoaderTest.refusesATopicThatIsBothWantedAndUnwanted
- ISC-199.2 — ConfigLoaderTest.acceptsATopicSpelledLikeASkillAndSaysSo — one WARN naming topic and skill
- ISC-202.2 — rg 'Example topic' shipped skill-profile.yaml (both lists); demo/skill-profile.yaml carries one fictional topic per list
- ISC-192 — ScoringWithTopicsTest.anInterestTopicLiftsTheTotalByExactlyItsBonusAndNamesItself — +12, reason interest_fit
- ISC-193 — ScoringWithTopicsTest.aDisinterestTopicIsChargedOnceHoweverOftenTheAdvertNamesIt — one row, -12
- ISC-194.1 — V28 offer_score_reason.topic; ScoringWithTopicsTest reads the topic back from the column
- ISC-197.2 — ScoringWithTopicsTest.aChangedProfileMovesTheScoreOnTheNextRunWithoutAskingTheJudge — 0 judge calls, +12; version bump → 1 call
- ISC-200 — HardFilterTest.aTopicNeverChangesAVerdict — verdicts identical with and without topics
- ISC-201.1 — ScoringWithoutAModelTest.writesTheTopicRowsWithNoModelAsItWouldWithOne — same rows, total withheld
- ISC-194.2 — offer-card.spec 'shows a topic reason even when four larger positives outrank it'
- ISC-195.1 — OfferQueryServiceTest.narrowsToOffersWhoseStoredReasonsNameTheTopicInEveryBand — four bands, cursor walks
- ISC-195.2 — shortlist-page.spec 'the topic filter' — facet offers configured topics, topic= sent, kept through a saved view
- ISC-201.2 — SemanticSearchWithoutRetrievalTest.answersATopicWithTheAliasMatchesRatherThanRefusingIt
- ISC-203 — DigestServiceTest.listsADiscardedOfferThatNamesAnInterestTopicAndNoOtherDiscardedOne (+ no section when none)
- ISC-198.1 — ScoringWithTopicsTest.aTopicTheJudgeFindsIsWorthWhatAnAliasIsWorthAndNeverBoth — judged row +12, alias+judged counts once
- ISC-198.2 — ScoringWithTopicsTest.asksNoTopicQuestionWhenTheWeightTableHasNoRowForIt — no question in the prompt, no topic row
- ISC-202.1 — operator-local git grep -wi of all 24 configured topic words, the two interest topics included, over specs/: 0 hits (2026-09-23)
- ISC-204 — WorkingNotesStaySmallTest green (CLAUDE.md 16,883/18,000); rg interest_topics docs/WRITING-RULES.md = 4; pipeline-scoring.md carries the five topic decisions
- ISC-196.1 — SemanticFilterTest.findsAParaphraseWithinTheTopicFloorBesideTheStoredAliasMatches (+ alias-only when the phrase cannot be embedded); floor measured with docs/samples/measure_topic_floor.ts
- ISC-196.2 — ScoringWithTopicsTest.aRetrievalVectorNeverMovesAScore — identical total after indexing and a forced re-total
