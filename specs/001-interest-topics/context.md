---
spec: 001-interest-topics
created: 2026-09-22T13:00:00Z
updated: 2026-09-23T00:30:00Z
rounds: 2
---

<!-- CONTEXT LOG — a record, not an authority. Nothing here gates anything and nothing
     reads it back. Every answer that changes the build lives in spec.md or plan.md. -->

# Context 001 — Interest topics steer the score and the shortlist

## Goal — confirmed 2026-09-22T13:00:00Z

An offer that matches a configured interest topic scores higher than the same offer without it, one that matches a
configured disinterest scores lower, the reason names the topic that moved it, and the shortlist can be filtered by
topic so that a match below the review threshold can still be found.

Principal's words: not reproduced here. They name concrete fields of interest, which the constitution's
`## What a spec may contain` keeps out of `specs/`. They are recorded verbatim in the master's `## Decisions`, and the
master is untracked.

## Round 0: shaping the idea, before the folder existed, 2026-09-22

### Q1 · What does "more dynamic" matching mean?

- Offered: interest topics (recommended) | learning from feedback | both, staged
- Chosen: both. Topics now in this spec, learning from the operator's own decisions as a later spec
- Landed in: this spec's scope and `## Out of Scope`

### Q2 · How does a topic treat the hard filter?

- Offered: a topic rescues an offer from `NO_CORE_SKILL` (recommended) | score boost only | its own band
- Chosen: score boost only
- Landed in: ISC-200

### Q3 · What should become editable in the UI?

- Offered: topics, thresholds, weights (recommended) | all of the rules file | a raw YAML editor
- Chosen: topics, thresholds, weights
- Landed in: spec 002, not this one

### Q4 · One spec or two?

- Offered: two (recommended) | one
- Chosen: two. This one covers topics; editing in the UI follows as 002

## Round 1: before the spec, 2026-09-22

### Q1 · Which goal sentence? (the goal lock)

- Offered: boost and sink with a reason (recommended) | boost only | boost and hard sink | boost, sink and a topic
  filter
- Chosen: boost, sink and a topic filter
- Landed in: `## Goal`, ISC-195

### Q2 · How does a topic recognise an offer?

- Offered: aliases plus the retrieval vector (recommended) | aliases only | aliases plus the judge
- Chosen: aliases plus the retrieval vector
- Landed in: ISC-192, ISC-193, ISC-196

## Still open

- fog: the similarity floor for a topic paraphrase. It has to be measured with `docs/samples/measure_embeddings.ts`
  before it can act.

## Round 1b: draft marks, 2026-09-22

### Q1 · Where do the topics live? (the profile is not hot-reloaded today)

- Offered: `skill-profile.yaml` plus the watch list (recommended) | `matching-rules.yaml`
- Chosen: `skill-profile.yaml` plus the watch list
- Landed in: ISC-197, spec `## Decisions`

## Round 1c: review questions, 2026-09-22

### Q1 · How far may one interest topic move the score?

- Offered: an absolute bonus on the 0..100 scale, like the penalties (recommended) | a weighted factor in the table | a
  guaranteed minimum band
- Chosen: an absolute bonus, capped, symmetric to the sink
- Landed in: ISC-192, ISC-193 (rewritten)

### Q2 · Do topic matches below the review threshold reach the digest?

- Offered: a section of their own (recommended) | the shortlist filter only
- Chosen: a section of their own
- Landed in: a new claim under F29

### Q3 · Does the judge get a topic factor of its own, so a paraphrase can reach the score?

- Offered: yes, one bounded answer per topic, points from the same bonus table, never counted twice (recommended) | no,
  aliases only
- Chosen: yes
- Landed in: ISC-198 (rewritten)

### Q4 · What becomes of `anti_skills`?

- Offered: it becomes the disinterest list, old key still readable (recommended) | stays stack-only beside a second list
- Chosen: it becomes the disinterest list
- Landed in: ISC-191

## Round 1d: after the cross-model review, 2026-09-22

### Q1 · Keep the judge's topic question, which the review argued to drop?

- Offered: keep under three guards (recommended) | drop, tombstone ISC-198
- Chosen: keep under three guards
- Landed in: ISC-198.1, ISC-198.2, `## Constraints` (one scale per judged offer)

### Q2 · What happens to existing scores after a topic change?

- Offered: a rules-only rescore at zero model calls (recommended) | `ruleset_version` gains the profile version and the
  operator bumps it
- Chosen: the rules-only rescore
- Landed in: ISC-197.2

### Q3 · `anti_skills`: refuse by name, or read the old key as the disinterest list?

- Offered: refuse at load with a message naming the new home (recommended) | read as an alias with a default weight
- Chosen: refuse by name
- Landed in: ISC-191.2, ISC-191.3

Taken from the review without a question: bonus instead of a share (ISC-192), the stored match as the filter's seam
(ISC-194.1, ISC-195.1), heaviest topic wins once per list (ISC-192, ISC-193), the card exemption (ISC-194.2),
name-as-alias (ISC-191.1, ISC-199.x), placeholder topics in the shipped and demo profile (ISC-202.2), real test anchors,
and the phrase-mode measurement with a kill condition in the fog line.

## Round 2: before the plan, 2026-09-23

### Q1 · In which order is 001 built?

- Offered: score first, vector last, five stages (recommended) | filter first | the measurement first
- Chosen: score first, vector last
- Landed in: plan.md § Approach

The seam map read from the repo, not asked: no scoring code ever read `anti_skills`, so `disinterest_fit` is new rather
than a replacement; the digest has no template, so no runtime hint; `SemanticFilter.narrow` throws 400 without a model,
so the topic predicate degrades to the alias half instead of calling it; the working-set base is `status = 'PASSED'`, so
"DISCARDED" in ISC-195.1 is the score band, not the filter verdict; the demo profile has no placeholder industry, so
ISC-202.2 now says "fictional" for the demo file.
