# Retrieval

What a vector is allowed to decide in this tool, and what it is not.

This document is a decision, not a report: at the time of writing nothing below has been
built. **All four measurements named in § *Before any of this is switched on* have now been
taken**, on 2026-09-17, and each carries its result inline. One of them refuted a prediction this
document made; the correction is in place and the struck-through bullet is left standing so the
next reader sees which argument did not survive contact with the corpus.

The tool already embeds. `dedupe/OfferEmbedder` has written `offer.embedding` since `V22`,
`dedupe/SimilarOffers` compares those vectors with pgvector's cosine operator, and the two
bands the comparison acts on were measured on 2222 real adverts. None of it is retrieval:
nothing on the read side reads a vector, no prompt in the repository is assembled from
retrieved material, and the cover letter is a Freemarker template rather than a generated
document. The question this file answers is which of those should change.

## Two vectors, because they answer two questions

**The dedupe vector is short on purpose, and that makes it the wrong vector for retrieval.**
`OfferEmbedder.text()` embeds the title, the location and the advert's opening capped at
`DESCRIPTION_CHARS`, and its own javadoc says why the cap is there: a page of boilerplate
about the client's culture makes two different projects from the same agency look alike
rather than less alike. Deduplication wants a short, discriminating text. Retrieval wants the
opposite — the whole de-furnitured advert, because the domain, the responsibilities and the
client's actual problem live in the part the cap removes. These are not two settings of one
knob. They are two embeddings of two different texts answering two different questions, and
the only reason it looks like one knob is that they happen to share a model.

So retrieval gets `offer.retrieval_embedding`, `retrieval_embedding_model` and
`retrieval_embedded_at`, written by a stage of its own, and `offer.embedding` is not read by
anything new.

**Re-embedding the existing column instead is the cheap-looking option that costs a
shortlist.** Three reasons, and the first is the one that cannot be defended against:

- **The `embedding_model` guard cannot catch it.** `SimilarOffers` compares two rows only when
  their `embedding_model` matches, which is what stops vectors from two models — numbers from
  two different spaces — being compared. After a re-embed some rows would carry the vector of
  the short text and some the vector of the whole advert **under the same model name**. The
  guard passes, and the cosine between two incomparable vectors is a number rather than an
  error. That is precisely the failure the guard exists to prevent, arriving through the one
  door it does not watch.
- **The ordering makes the mixture permanent.** DEDUPE runs at position 2 and CONTENT at 5, so
  a re-embed after CONTENT could only affect the next run's dedupe, and offers that never
  survive the hard filter never get enriched text at all. There is no run after which the
  population is homogeneous.
- ~~**The bands would move in the dangerous direction.**~~ **Measured 2026-09-17, and it is not
  true.** This argument said that shared agency boilerplate dominates a longer vector, that
  similarities therefore rise across the board, and that `0.97` would begin merging distinct
  projects. Run paired over 252 adverts — both texts, the same rows, 2000 dimensions — the bulk
  of the distribution moves *down* and only the extreme tail moves up:

  | | teaser | advert |
  |---|---|---|
  | p50 | 0.5380 | 0.5500 |
  | p90 | 0.6920 | 0.6860 |
  | p99 | 0.8220 | 0.8060 |
  | p99.9 | 0.9380 | 0.9440 |
  | p99.99 | 0.9760 | 0.9880 |
  | pairs at or above 0.8 | 488 | **347** |
  | pairs at or above 0.97 | 7 | 10 |

  Twenty-nine percent fewer pairs reach the floor, and the ones that do reach the merge band are
  read against their titles rather than counted: all ten are visibly one project posted twice —
  *"Fullstack Software Engineer AWS/Typescript"* against *"…AWS/Typescript/React"*, *"Bündel: 3
  CKA DevOps Engineers"* against *"CKA DevOps Engineers"*, *"Runtime Security Engineer (m/w/d)
  gesucht / Remote / 5 Monate++"* against *"Runtime Security Engineer (m/w/d) gesucht"*. The
  longer text separates the middle better and pulls genuine duplicates tighter. That is the
  opposite of the worry.

  **Why the worry was wrong, and where it is still right.** The dilution is real — a synthetic
  run the same day moved two texts that differ only in what the job is from 0.49 apart at teaser
  length to 0.91 alike once four thousand characters of shared boilerplate sat in front of them.
  What defeats it here is the content stage: the retrieval text is `ContentText.of`, the CONTENT
  blocks only, and on this corpus that strips 1631 furniture blocks against 2701 kept ones, about
  fourteen hundred characters an advert. **The dilution argument holds for `full_text` and is
  answered by segmentation, so it is an argument for embedding the de-furnitured text and not an
  argument against embedding the advert.**

  The decision below does not change, because it never rested on this bullet: the two reasons
  above it are structural and neither is affected. It is worth saying plainly that this one was a
  prediction, that it was wrong, and that measuring it cost one evening.

**A chunk table is the right shape for a question this tool is not asking.** One row per
content block is what passage-level retrieval and citations need. A search result here is an
offer, not a paragraph, and ranking offers by their best chunk is an aggregate over a joined
table — which the shortlist's keyset comparison has nowhere to stand on. A generic
`document_chunk` table remains the correct design for corpus-wide question answering, it is
additive later, and § *What was refused* says why that is not being built.

**The measured bands survive by construction, and that is the whole point of the second
column.** `0.97` and `0.95` are properties of the text `OfferEmbedder.text()` produces. That
text does not change, that column does not change, `SimilarOffers` is not touched. The new
column ships with **no threshold at all**.

**One consequence of the corrected bullet is worth writing down rather than discovering later.**
The de-furnitured advert is the better text for deduplication too — more true duplicates in the
merge band, far fewer pairs in the region where a person has to decide. It still cannot be used
for it, and the reason is ordering rather than quality: DEDUPE runs at position 2 and the advert
does not exist until ENRICH and CONTENT have run. Moving deduplication behind them would mean
enriching duplicates before collapsing them, which is the fetch budget spent on adverts that are
about to be merged away. So this stays a retrieval vector, and the note is here so the next
person who reads the tables above does not mistake them for permission.

## The search narrows; it does not rank

The obvious shape for semantic search is a seventh `ShortlistSort`. It is the expensive one,
and the expense is not the vectors:

- **The key expression would depend on a request parameter.** `ShortlistSort.expression()`
  composes a column name and a sentinel, and `orderBy()` and `pageClause()` are both derived
  from it so the two cannot disagree. A cosine distance against a query vector is not a column,
  and making `expression()` a function of the request is exactly the seam that design closes.
- **The key is a float and `Key` binds a `long`**, so a fourth kind carrying fixed-point
  distance, plus an `Unstated` sentinel for the rows that have no vector.
- **The exhaustive switch does not save it.** It guarantees that a new sort has a cursor
  component. It cannot guarantee that the cursor was minted under the same *query text*, and a
  relevance cursor replayed against a different `q` is the failure `Cursor`'s own javadoc
  describes for a mismatched sort: identical bytes, different meaning, silent, an arbitrary
  slice with no error anywhere. That is a fifth cursor component and a fourth `400`.

There is a second problem that is not mechanical. A ranking needs a floor or page one of two
thousand always looks plausible, and a floor is a threshold, and a threshold on this column
would have to be measured before it could act — which is the rule every other threshold in
this repository already follows.

`read-side.md` states the alternative itself: *"The filters need no such guard: they narrow
the set without redefining the key."* So the semantic search is a filter. `similar=<id>` or
`semantic=<text>` selects a bounded neighbourhood, and the existing sort, the existing keyset
page and the existing counts run over it unchanged:

```sql
AND o.id IN (
    SELECT s.id FROM offer s
    WHERE s.retrieval_embedding IS NOT NULL
      AND s.retrieval_embedding_model = :model
    ORDER BY s.retrieval_embedding <=> CAST(:queryVector AS vector)
    LIMIT :k
)
```

`k` is a count and deliberately not a similarity, which is why this can be switched on before
the new vector space has been measured — the ranked list is bounded by construction rather
than by a cutoff nobody has evidence for. The neighbourhood is stable for a fixed query, so
the cursor keeps walking one order and the match count keeps describing the match. **The
configured number is a count and the operator is still a distance**: `<=>` grows as things
get less alike, and every similarity in a configuration file is the other way round.

`similar=<id>` costs no model call at all, because both vectors are already in the database.

**Before any of this, the lexical search had to read the advert, and now does.**
`OfferQueryService` searched `title`, `description` and the tags and neither `full_text` nor
`content_blocks` — so the tool fetched the advert, stripped its portal furniture, stored it, and
then searched the newsletter teaser. It now reads the same text `ContentText.of` hands everybody
else. That was a deterministic fix with no migration, no key and no model, and it came first for
a second reason too: a semantic search measured against a lexical search that never read the
corpus is not measured against anything.

## Which projects the letter pitches

`referencesFor` counted how many of a project's `stack` tokens appeared in the advert, kept
`overlap > 0`, and took two. Three consequences, and the third is the one that cost something:
the pitches contributed nothing to the choice although they are the only fields saying what a
project *was*; a dozen Spring projects tied on "Java" and the order fell to whichever the YAML
listed first; and because the filter ran before the limit, **a letter could go out pitching one
project or none**, silently, since `meta.json` records the empty list and nobody reads it before
sending.

**The lexical rule still decides everything it can.** A project whose stack the advert names is a
project the advert asked for, and no similarity outranks that — the comparator sorts on overlap
first and on similarity only within it, so the guarantee is structural rather than a later
reader's care. The model speaks in exactly the two places the rule is silent: it breaks ties
among equal overlap, and it fills the slots the rule left empty. Without vectors the output is
byte for byte what it was, which is what makes the fallback real.

**Measured 2026-09-17** over 252 adverts and six reference projects, by
`docs/samples/measure_references.ts`:

| | adverts |
|---|---|
| fewer than two references under the old rule | **72 of 252** |
| fewer than two after the blend | **0** |
| where the choice changed at all | 137 |

The 137 break down as 37 fills, 19 pure reorders and 36 swaps among projects of equal overlap,
which are the three things the blend is supposed to do and nothing else. Read against the
titles, the swaps are the point: a Cloud Security / AWS advert now gets the Amazon market-data
API instead of a corporate portal relaunch, and a DevOps advert gets the self-operated
Kubernetes platform. The generic project used to win those on YAML order.

**One vector per project and language, not per project.** The pitches are content rather than
repository language, and an advert is compared against the pitch in its own language — the
language `PackagingService` already determined for the letter. A mixed-language blob is not the
comparison anyone wants. They are embedded once per process and cached by a digest of the text,
so an edited pitch re-embeds itself alone and hot reload stays free; a table would buy
persistence across restarts for a handful of vectors that cost one request to rebuild.

## The trap in `similar=`

**A scalar subselect for an offer with no vector returns k arbitrary offers, and nothing says
so.** `ORDER BY s.retrieval_embedding <=> (SELECT a.retrieval_embedding FROM offer a WHERE
a.id = :anchor)` reads as "nearest to this one". When the anchor has no vector the subselect is
NULL, the distance is NULL for every row, and `ORDER BY NULL … LIMIT k` hands back whichever k
rows the planner reached first — which the screen then presents, under a chip naming the
anchor, as the offers related to it.

Every part of that is plausible on inspection. There is no error, the count beside the list is
true about the set that came back, and the rows are real offers. It was found by wiring the
detail's button and asking what happens on an advert the index has not reached yet, not by a
test.

So the filter checks the anchor first and refuses by name: *"this offer has not been read for
meaning yet, so nothing can be found near it."* An empty list would be the honest version of the
same answer; a sentence is better, because the reader learns that this advert is not indexed
rather than that nothing resembles it. That distinction is the same one the coverage note makes
for the list as a whole.

## The stage sits after SCORE, and that is not where it reads

`RETRIEVAL` runs between `SCORE` and `PACKAGE` rather than straight after `CONTENT`, where its
input is ready. The reason is the budget, and it is worth the comment beside the
`stages.time` line because `IngestOrderTest` pins the order and only a comment says why.

`LlmBudget` is one allowance shared by every stage. The first run after this is switched on,
and every run after the embedding model changes, walks the whole `ttl_days` window: a few
thousand offers is over a hundred requests at a batch of thirty-two. In front of `SCORE` that
backfill spends the day and the shortlist goes unjudged — a new and unproven stage starving
the one the tool exists for. Behind it, the same backfill degrades only the search, back to
the lexical one, which is the correct order of losses. That it takes several nights to clear
is documented behaviour rather than a fault: the due query is self-healing, so each run
continues where the last one stopped.

**Folding the embedder into `ContentService`** the way `OfferEmbedder` is folded into
`DeduplicationService` would save the whole stage cost — no `GLOBAL_STAGES` bump, no order
test, no package, no report component. It is refused for three reasons: the due populations
differ, because this stage is also due when the model name changes and a full re-walk would
make CONTENT's counters lie; the stage does not want CONTENT's position, per the paragraph
above; and the calls would be invisible. A stage quietly eating thirty of three hundred calls
inside another stage's line is the same class of dishonest counter as a key nothing reads.

## What was refused

**Retrieved few-shot examples for the judge.** Four independent reasons, any one sufficient.

It breaks *two judges are two scales* through a door the rule does not cover: a per-offer
retrieved example set is a different judge for every offer, one offer's neighbours all won and
another's all lost, and both results are read against one `auto_shortlist` number while
`score_model` does not move and nothing is marked stale. Doing it honestly needs a
`score_context_digest` beside `score_model` and a re-judge whenever it moves — on a growing
corpus, the standing shortlist re-judged most nights against a budget of three hundred chat
calls.

The labels are selection-biased in the one direction that matters: an application status
exists only for offers that were packaged, so there are almost no labelled negatives from the
region where the judge actually has to discriminate. The label is also noisy with respect to
the text, because whether a project was won turns on rate, timing, competitors and pre-placed
candidates, none of which the advert contains.

And the deterministic alternative is strictly better and free: an offline script beside
`simulate_filter.py` joining the application events against `offer_score_reason`, asking which
deterministic factor correlates with a win, and retuning the weight table. Auditable, one
scale, zero calls at runtime, and it improves scoring for an installation with no model at
all. If the judge should see examples, a fixed hand-written block identical for every offer
keeps one scale and one staleness key — and is not retrieval.

**Question answering over the whole corpus.** The shortlist already answers most of these
deterministically: six sort keys, the facet panel, saved views, the archive axis, the bands,
the start window, the duration floor. "Which Java offers start in October" is a filter and
"which agency posts most" is `analytics/`. What is left are questions whose wrong answer is
expensive and unverifiable, over the one dataset money decisions come from. The honest
carve-out is single-offer question answering, which needs no retrieval at all: one
de-furnitured advert is a few thousand tokens and fits whole in any context window,
`ContentText.of` already produces it and `Answers.objectIn` already reads the reply. **Built
2026-09-17** as `ask/`, and the rest of this paragraph is the argument for its shape.

**Every claim carries a sentence from the advert, and one that is not in the advert is
dropped.** That is the whole design; the fixed question list, the absent storage and the shared
model key are arrangements around it. It is the same rule the measurement scripts follow — a
table of similarities nobody can check against the adverts behind them is a table nobody should
act on — applied to prose instead of numbers, and prose is where it matters more, because a
number that looks wrong invites a second look and a fluent paragraph does not. Without the
check this screen would be a confident, specific, unfalsifiable answer about a document the
reader is holding. With it, a wrong answer is a missing answer.

The check folds both sides before comparing, which is not a detail: measured on the deployed
instance the same question answered twice returned the quote once with the source's Markdown
emphasis and once without, and both are faithful. What folding cannot forgive is an invented
sentence.

**Three states and deliberately not one.** *Silent* is an answer — this advert does not say, and
that is the expected answer for most questions on most adverts. *Refused* is not an answer:
nobody could ask, because no model is configured, the advert was never fetched, or the day's
budget is spent. They are a 200 and a 409 respectively, because drawn the same way a reader
takes a spent budget for a quiet advert and fills the gap themselves. *Answered* carries the
sentence and its quote.

**The questions are an enum and not a text box.** The type is the allowlist, so nothing a caller
sends reaches a prompt, the cost per advert has a ceiling against a budget sized for a nightly
pass, and the five are the questions an advert is actually read for. Nothing is stored, so
nothing goes stale when the content stage rewrites the advert underneath it.

**The CV corpus.** Chunking the PDFs in `config/documents/` has no legal consumer here,
because *no CV tailoring* means nothing may be selected out of a CV in the first place. It
would also buy a PDF text dependency for a corpus of two files.

**Generation in the cover letter.** `llm.models.writing` stays read by nothing, and that stays
the honest state — the *selection* of reference projects is what retrieval improved, and it is
built. See § *Which projects the letter pitches*.

**Generation in the cover letter.** `llm.models.writing` stays read by nothing, and that stays
the honest state. A template cannot invent a project, a rate or a client name into a document
somebody is about to send under their own name, which is what makes the package folder safe to
produce unattended. Where retrieval helps there is *selection*: `referencesFor` ranks reference
projects by counting stack tokens in the advert, so `pitch_de`, `role` and `title` contribute
nothing, a dozen Spring projects tie on every Java advert, and — because the overlap filter
runs before the limit of two — a letter can go out with one reference project or none. The
model picks among sentences that were all written by hand.

## Before any of this is switched on

`measure_embeddings.ts` gains a text mode rather than a copy: a second `text()` mirroring the
retrieval text character for character, a TypeScript mirror of `ContentText.of`, and a cache
file named by model **and** mode, because one cache overwriting the other is the mistake this
change invites. Its documented `psql` line selects the enriched columns instead of the
description.

1. **Prove the dedupe bands did not move. — Taken 2026-09-17, green.** The unchanged teaser mode
   reproduced the existing band table byte for byte from the cache, with no request leaving the
   machine. The script has since grown a text mode; the same check was repeated afterwards and
   the band table and the four hundred listed pairs came back identical, so the change measures
   what it measured before.
2. **Measure the bands on advert-length text. — Taken 2026-09-17. The prediction was wrong.**
   The tables and the reading are in § *Two vectors*, under the struck-through bullet. In short:
   run paired over one corpus, the advert text produces 347 pairs above the floor against the
   teaser's 488, and the ten it puts in the merge band are all one project posted twice. The
   population had to be 252 adverts rather than the reference's 2222, because that is how many
   have been fetched and segmented — which is also why the comparison is paired over one file
   rather than held against the older table. Pair counts fall with the square of the row count,
   so two corpora of different sizes are not comparable and the percentiles of one corpus are.
3. **Re-check that truncating to 2000 still holds at this length. — Taken 2026-09-17, it holds.**
   The Matryoshka argument had been measured on inputs of a few hundred characters, and nothing
   said the leading dimensions carry the separation the same way on an advert ten times longer.
   On the advert text, 4096 against 2000:

   | | 4096 | 2000 |
   |---|---|---|
   | p50 | 0.5440 | 0.5500 |
   | p99 | 0.8020 | 0.8060 |
   | p99.9 | 0.9400 | 0.9440 |
   | pairs at or above 0.97 | 10 | 10 |
   | pairs at or above 0.95 | 24 | 24 |
   | pairs at or above 0.92 | 51 | 51 |

   Every acting band is identical and no percentile moves by more than 0.006. The column stays
   `vector(2000)` and `halfvec` is not needed.
4. **Measure the embedding server's effective context rather than reading it. — Taken
   2026-09-17.** Ollama applies a context length to embedding requests and truncates silently,
   and Spring AI does not report it, so a corpus of half-read adverts would look exactly like a
   corpus of read ones. The check observes rather than asks: two texts sharing every character
   but their last five hundred, at nine lengths. A server that truncated before the tail returns
   one vector twice, and the cosine is 1.

   **It does not truncate.** Against `qwen3-embedding:8b` the differing tail moved the vector at
   every length up to 24000 characters, so the character cap is not forced by the server. It is a
   choice about dilution instead, and the same run measured that:

   | shared prefix + differing tail | cosine |
   |---|---|
   | 500 characters | 0.488 |
   | 2000 | 0.870 |
   | 6000 | 0.907 |
   | 12000 | 0.933 |
   | 24000 | 0.933 |

   Two texts that differ in what the job actually is, and agree on everything around it, are
   0.49 apart at teaser length and 0.91 alike at advert length. **That is the second measurement's
   prediction, arriving early and from a different direction**: length does not add signal past a
   point, it adds shared boilerplate, and shared boilerplate is what a cosine reads. It is also
   the number that settles the first rule in this document with evidence rather than with
   reasoning — at advert length, two different projects from one agency's template sit close
   enough to the 0.95 flag to make pointing `SimilarOffers` at this column a way of losing
   offers. The synthetic figures do not replace the real band table; they do decide that the cap
   exists for dilution and not for a server limit.

Every output names real adverts and stays gitignored, like every other file derived from the
corpus.
