# Deduplication, the hard filter and the archive

What collapses one project advertised twice, what the six deterministic stages reject, and what falls off the working
list without being a verdict.

These are working notes moved out of `CLAUDE.md` so the always-loaded file stays small.
Every paragraph here was paid for once; none of it is a summary.

## Deduplication

`backend/…/dedupe/`. One pass after every ingest run, over every offer inside
`deduplication.ttl_days`.

- **This is not the upsert in `OfferStore`.** That one collapses a *listing* seen twice,
  which is what re-reading a newsletter produces. This one collapses a *project* several
  portals advertise at once, which is 14.0 % of the measured corpus.
- **The fingerprint is the normalized title and nothing else, and that is measured.** The
  configured field list names `city`, `start_date`, `duration_months` and `top_skills`;
  all four come from enrichment, which runs *after* this stage. Adding the one field that
  does exist — the stated location — collapses 127 instead of 180, and the 53 it gives up
  are overwhelmingly correct merges lost to the same ad writing "Nürnberg" in one portal
  and "Remote und Nürnberg" in the next. A location must be parsed before it can be
  compared. **A field that is present is not the same as a field that is comparable.**
- **The consequence is accepted, not hidden:** two genuinely different projects that share
  a title do merge. ISC-40 states the limit rather than claiming the opposite.
- **It runs after every source, never per source.** A pass scoped to one source would
  never see the pair it exists to collapse.
- **Idempotent by construction.** The primary of a group is recomputed from the group
  every run — `first_value(id) OVER (PARTITION BY fingerprint ORDER BY ingested_at, id)` —
  so a second run assigns exactly what the first did and a listing arriving later attaches
  to the primary already there instead of starting a rival cluster. The update is
  restricted to rows whose assignment actually changes, which is what makes the "moved"
  count mean moved rather than seen.
- **`IngestReport.merged` is the standing total, not the rows this run moved.** A second
  run moves nothing, and a zero there would read as "deduplication stopped working".
- **All three strategies the shipped file lists are implemented**, and the order is the
  point: the exact one costs nothing, so it runs first and the similarity pass has less to
  ask about. A strategy type nobody wrote is still logged and skipped rather than fatal —
  failing at load would break a shipped default. A `merge_policy` other than
  `keep_first_seen_as_primary` *is* fatal at load, because that one would be read, ignored,
  and quietly do the first-seen thing anyway.

## The two similarity strategies

`backend/…/dedupe/OfferEmbedder` fills the vector, `dedupe/SimilarOffers` compares it, and
`V22`/`V23` hold the column and the flag.

- **pgvector, not an array and a loop.** The comparison is a nearest-neighbour search over a
  window of offers, which is the one thing a database index is for. The cost is the image:
  `pgvector/pgvector:pg17` in place of `postgres:17-alpine`, in Compose and in nineteen test
  classes — which is why the image is now named once, in `Databases`.
- **2000 is in the column because it is the widest vector pgvector will index.** An index
  cannot be built on a vector of unstated width, so the width is part of the schema and a
  model that returns fewer is refused at the seam with both numbers in the sentence. The
  ceiling is not a preference: measured against pgvector 0.8.6, `vector` refuses an HNSW
  index above 2000 dimensions and `halfvec` above 4000. `V22` shipped 768 for
  `nomic-embed-text`, which is what a local Ollama offers; `V25` widened it, and why is the
  next paragraph.
- **The thresholds were measured before they were allowed to act, and both shipped numbers
  were wrong.** 2222 real adverts, the non-duplicate PASSED rows inside `ttl_days`, embedded
  outside the application by `docs/samples/measure_embeddings.ts` and compared pair by pair.

  | band | nomic-embed-text 768 | qwen3-embedding:8b 2000 | qwen3-embedding:8b 4096 |
  |---|---|---|---|
  | >= 0.97 | 27 | 18 | 18 |
  | >= 0.95 | 67 | 63 | 59 |
  | >= 0.92 | 322 | 270 | 264 |
  | >= 0.85 | 12147 | 3470 | 3397 |

  At the shipped `0.85` flag, `nomic-embed-text` paired 12147 adverts out of 2222 — about
  eleven flags per offer, which is not a signal but a second inbox. At the shipped `0.92`
  merge it put "Smalltalk Visual Works Entwickler" and "Fullstack Entwickler", two different
  projects sharing one agency's title template, at 0.9346, and it treats the advert ID that
  distinguishes `ID02836` from `ID02864` as noise. It is trained on English and this market
  writes German. `qwen3-embedding:8b` keeps the genuine duplicates in a tail above 0.96 and
  pairs a quarter as many at 0.85, so the numbers are now **0.97 to merge and 0.95 to flag**:
  18 merges and 63 flags on that population, and the 0.95 band reads exactly as a flag should,
  "the same posting in English, or with the start date moved on".
- **A wider model is truncated at the seam, and that is sound rather than convenient.**
  `qwen3-embedding:8b` returns 4096 dimensions and cannot be indexed at all. It is trained with
  Matryoshka representation learning, so its leading dimensions carry the separation: cut to
  2000 it pairs 3470 above 0.85 against 3397 at full width, two percent apart, and cut to 768
  it still pairs only 4508 against `nomic-embed-text`'s 12147 in the same column width. The cut
  vector is not renormalised, because `<=>` divides by both lengths. A model trained without
  MRL degrades instead of shortening, which is why the truncation is announced once at the
  seam rather than done silently.
- **The measurement is a script in `docs/samples/`, not a paragraph.** `measure_embeddings.ts`
  takes the working list as JSON and a model name, caches the vectors so a second cut is free,
  and writes the bands with **both titles of every pair**. A table of similarities nobody can
  check against the adverts behind them is a table nobody should act on, which is the same
  reason `content_block_label` keeps a 200-character sample. Its output names real adverts and
  is gitignored with everything else derived from the corpus. Re-measure before changing a
  threshold, and re-measure before changing the model: the bands are a property of the pair,
  not of the number.
- **The similarity strategies are scoped to the working list and the exact fingerprint is
  not.** `OfferEmbedder` skips archived offers, and a row with no vector is invisible to both
  strategies, because they compare only rows embedded by the same model. The asymmetry is
  deliberate twice over. Attaching a fresh offer to an archived primary would hide it behind
  something that is already history, since `keep_first_seen_as_primary` makes the older row the
  primary — the exact pass does that and gets away with it because a shared title is a much
  stronger claim than a cosine. And the cost is otherwise the whole stage: measured on 13240
  offers of which 13232 were archived, the window held 11437 rows to embed and 8 of them were
  still on the working list, so 357 of 358 requests and a full day of
  `llm.budget.max_calls_per_day` would have gone to adverts nobody will see again. In a nightly
  run the filter changes nothing at all, because archiving happens after this stage.
- **`embedding_model` sits beside the vector**, because two vectors from two models are not
  far apart or close together; they are numbers from different spaces, and the cosine
  between them is a number rather than an error. A row embedded by another model is
  re-embedded rather than compared.
- **`llm.models.embedding` has no fallback.** The ingest fallback takes the scoring model
  when its own key is empty; this one must not, because a chat model is not an embedding
  model. Unset means the similarity strategies do not run, which is what a fresh clone has
  always done.
- **What is embedded is the title, the location and the advert's opening.** Those three are
  what exists before enrichment. The location is deliberately in: it is the field that cost
  the exact fingerprint 53 correct merges, because "Nürnberg" and "Remote und Nürnberg" are
  one place written twice and two strings compared once. The opening rather than the whole
  advert, because a page of boilerplate about the client's culture makes two different
  projects from the same agency look alike rather than less alike.
- **The configured number is a similarity and the operator is a distance.** `<=>` is cosine *distance*, so 0.97 is a
  limit of 0.03. Reading one as the other does not fail; it merges
  everything or nothing, and both look like a plausible day.
- **The older of a pair is always the primary**, which is what makes the pass idempotent: the
  relation is antisymmetric, so a second run assigns what the first did.
- **Similarity is not transitive, so there is no equivalence class to compute** the way the
  exact pass does with `first_value`. One statement can leave A attached to B while B goes to
  C, and the chain is shortened afterwards in up to five passes.
- **`flag_possible_duplicate` writes its own column.** `duplicate_of_id` decides what the
  working list shows, and a maybe written into it would silently hide an offer. The shortlist
  badges the flag and can filter on it; nothing is hidden, and the row stays its own offer.
- **The transaction around the whole pass is gone.** Computing a vector is an HTTP call, and a
  transaction held open across a few hundred of them is held open for minutes. Every statement
  is atomic on its own and the pass is idempotent, so a run that dies halfway is repaired by
  the next one rather than by a rollback.
- **An UPDATE cannot reference its own target table from a LATERAL item in its FROM clause**,
  which is why both similarity statements are a CTE and not the shorter thing they look like
  they should be. The target is not part of the from_list, so
  `UPDATE offer a SET … FROM LATERAL (SELECT … WHERE b.x = a.x)` fails with "invalid reference
  to FROM-clause entry for table a" — a message that reads like a typo in an alias that is
  plainly there. The pairs are computed in a `WITH` and the update reads `FROM` that instead.

## The hard filter

`backend/…/filter/`. Six stages in a fixed order, applied after deduplication, with no
model and no network. It removes four offers in five for free, and only what survives
costs a language-model call.

- **Not one keyword is written in Java.** The lists come from `matching-rules.yaml` and
  the core skills from `skill-profile.yaml` — the same reason no CSS selector is written
  in Java. `docs/samples/simulate_filter.py` mirrors them and ISC-41 proves the two still
  agree over the corpus.
- **The order is the meaning.** abroad → remote share → out of reach → role or stack → no
  core skill → contract form. An offer stops at the first rejection, which is the only
  reason the per-stage counts sum to the total (ISC-42).
- **Nothing here reads a date, and `STALE` used to be the seventh stage.** "Too old" is not
  a verdict about an advert — an old advert is a good advert nobody will answer any more —
  and a verdict is what the funnel reports and what somebody reads when they ask why an
  offer is missing. The rule kept its name and moved to the archive.
- **The rate rule is deliberately absent.** It is configured `apply_after: enrichment` and
  the loader refuses any other value, because the sources state a rate in 0.0 % of offers.
- **Fold, then match on word boundaries.** `TextFold` is the one place text and patterns
  are normalised, and it exists because the reference got this wrong three separate ways:
  an umlaut fold that leaves `ko ln` and loses every Köln and Düsseldorf offer; substring
  matching where `ch` rejects Aachen and `ANÜ` hits Planung; and unfolded patterns
  compared against folded text, where `.net` and `c#` match nothing at all. All three were
  silent and all three moved the survivor count by hundreds.
- **`onsite_cities` is a list, not a radius.** An offer states its location as free text —
  "Remote und Nürnberg", "DE 7XXXX" — so a kilometre figure would need a dataset, a parser
  and a network call this stage must not need. A `onsite_max_km` key used to sit in the
  schema and nothing read it. An empty list is logged at load: it means only remote offers
  can pass, which otherwise looks exactly like a quiet market.
- **`role.rejected_title_keywords` is not `anti_skills`.** The latter is documented as a
  scoring penalty worth -30; reading it as a knockout as well would mean tuning the score
  silently changes what reaches the shortlist. The lists differ too — this one rejects
  roles, not only stacks.
- **Core skills are read with their aliases.** An ad asking for "Springboot", "Spring
  Data" or "k8s" names a core skill, and eight bare names would answer no. Worth twelve
  offers over the corpus.
- **`remote.accept_unknown` decides what happens to an unstated share, and it is paired
  with the minimum.** It spent a long time rendered, validated and read by nobody — the
  same class as the `onsite_max_km` key that sat in the schema with no reader — and
  `HardFilter` now consults it. `true`, the default, lets an offer that states no remote
  share fall through to the next stage; `false` rejects it as `REMOTE_SHARE` with "no
  remote share stated". The default stays `true` because the sources state a share in
  8.8 % of offers, so rejecting the rest by default would empty the shortlist. It is
  paired with `min_remote_percent > 0` for the same reason `OUT_OF_REACH` is: where no
  share is required at all, an unstated one cannot be a reason to reject. One divergence
  worth knowing — `simulate_filter.py` does not implement the `false` branch, so the
  reference implementation and the Java disagree the moment the flag is switched off.
- **`min_remote_percent: 0` switches the reach rule off, and that is the point.** Zero
  required remote share means being on site is acceptable, and then it is acceptable
  anywhere — the hand-written city list stops applying. Without the condition the two
  settings pulled against each other: the share rule said "on site is fine" and the reach
  rule still rejected every town not on the list. Measured on the archive: 145 of 254
  offers died there while the share was already at zero, and not one of them stated a
  remote share at all. Above zero the list is back, because needing 40 % remote means being
  on site for the other 60 % and that part has to be somewhere reachable.
- **`accept_unknown` and `OUT_OF_REACH` still answer different questions.** An unstated
  share is not a rejection *for its share*; above a zero minimum the offer then meets the
  reach rule, which asks whether the location is near or the text says remote.
- **The verdict is written on the offer**, stage and reason both. A rejection without its
  reason is a number nobody trusts a week later.

## The archive

`backend/…/archive/`. What is no longer on the working list, and the only thing about an
offer a person owns.

- **It is an axis, not a verdict.** The filter says whether an advert is worth answering;
  the archive says whether it is on today's list. Two different questions, and the second
  one is why `FilterStage.STALE` no longer exists: age used to be reported as a rejection,
  which is what somebody reads when they ask why an offer is missing.
- **It cannot be a value in `offer.status`.** `FilterService.run()` reads the whole table
  with no `WHERE` and writes a verdict onto every row, because the rules are hot-reloadable
  and a partial re-judge would split the archive across two rule sets. An `ARCHIVED` status
  would be overwritten by `PASSED` on the next run — silently, and only for the offers that
  still pass.
- **Two columns, because there are four states.** `archived_at` with `AGE` or `MANUAL` is
  off the list; both null is on it; and `archived_at` null with `RESTORED` is on it *deliberately*, which is what stops
  the age pass taking it back off the next morning. A
  restore the next run undoes is a button that lies.
- **The age pass reconciles, it does not seal.** While the rule lived in the filter,
  staleness was recomputed every run, so widening `max_age_days` brought offers back. Rows
  the pass archived itself still come back; rows a person archived never do.
- **It runs between the filter and enrichment.** After the filter so a restored offer
  carries a current verdict, before enrichment because that is the stage that leaves the
  machine and scoring is the one that costs money. An offer off the list pays for neither.
- **An offer somebody is working on is never archived by age.** The exemption is
  `ApplicationStatus.isLive()` — not closed, and not `PACKAGED`, which is the state the
  packager opens with. Treating that one as "in progress" would exempt every offer that
  ever reached the shortlist, which is the whole shortlist.
- **A null `published_on` is never archived** and is counted in the report rather than
  passed over. It is the one way an offer can sit on the list forever.
- **`archived_at IS NULL` is the third part of the working-set predicate**, beside
  `status = 'PASSED'` and `duplicate_of_id IS NULL`, at all twelve sites. **The funnel needs
  it on both sides of the subtraction** or `survived` goes negative — the same defect
  duplicates once produced, and after a week the archive is the larger half of the table.
  `survived` equals the shortlist's own total, and that is the invariant to check when
  either number looks wrong.
- **The analytics deliberately do not get the predicate.** They are the record of what the
  market did, not the working list; excluding the archive would empty every chart older
  than the window. That their numbers differ from the shortlist's is correct.
- **The archive is a side, not a band.** A band is a range of scores; this decides which set
  the bands apply to, so it composes with them and with the search. `total` and the portal
  dropdown are counted over the side being read, or the filter offers a portal that
  produces an empty list and no reason.
- **The board shows the working list, not every application ever opened.** `ApplicationService.BOARD`
  carried no `WHERE` at all, so an archived offer kept its card and kept counting towards the dashboard's follow-up
  tile. Age never puts a live application there — `ApplicationStatus.isLive()`
  exempts it — so what the predicate hides is something a person archived by hand, which is the clearest statement
  available that they are done with it. **`find(id)` deliberately does not get the predicate:** it is what `update`
  reads before and after a write and the offer detail is reachable for any offer, so filtered there too, archiving an
  offer would make its own status uncorrectable. It also stopped scanning the whole board to find one row. The accepted
  consequence is that an archived offer's detail shows no status panel, because the browser looks the application up in
  the board list it already holds.
- **A row archived from the list is dropped from it rather than replaced.** It is no longer
  part of the side being read, and leaving it there shows the working list carrying
  something that is not on it until somebody reloads.
- **The bulk endpoint answers a report, and that is the one place the plural breaks the singular's rule.**
  `PATCH /api/offers/{id}` returns the whole `ShortlistEntry` because the browser replaces its row with what the server
  stored. `POST /api/offers/archive` cannot:
  the reducer drops those rows rather than replacing them, so entries would be fetched only to be discarded — measured
  here at ~1.4 KB each, so 200 offers is ~280 KB of representation nobody reads. `requested` against `archived` is what
  an id that named no offer costs, and it is why the endpoint answers 200 where the single PATCH answers 404: refusing
  fifty decisions because one member vanished loses forty-nine for a reason nobody can act on.
- **No lock against a running pass, and the data is the reason.** `ARCHIVE_AGED_OUT` requires
  `archive_source IS NULL` and `RESTORE_INSIDE_WINDOW` requires `archive_source = 'AGE'`, so a row stamped `MANUAL` is
  outside both predicates and a concurrent pass cannot undo the write. That is the four-state design earning its keep
  rather than a guard doing it. `IngestService`'s
  `tryLock` exists because a second *pass* is the same work twice; one statement over at most 500 rows is not that, and
  a 409 here would refuse the operator's own decision because a machine is busy.
- **`SET_BY_HAND` binds positionally, and it must stay that way.** `JdbcClient` is named or positional per statement,
  never both, and a *named* parameter holding a `Long[]` is expanded into a `?, ?, ?` list — which turns `= ANY (:ids)`
  into `= ANY (?, ?, ?)`, a syntax error only a real Postgres reports. The two other array bindings,
  `OfferQueryService.reasonsFor`
  and `clustersFor`, are positional for the same reason. Tidying this back into named parameters "like the rest of the
  file" is the edit that breaks it.
- **The multi-selection lives in the store while the routed selection is a route, and the two rules do not contradict
  each other.** Which offer is *open* is the URL, because a deep link, the back button and a click all have to agree on
  it. Which offers are *ticked* is transient:
  fifty ids in a query string is not a link anybody sends, and it would make the back button undo a checkbox. It sits in
  `ShortlistStore` because `opened` already fires on every filter change and already clears `entries` — so the picks
  clear in the one place they cannot drift from the list they point into. `moreLoaded` deliberately writes nothing,
  which is what keeps a selection across paging and lets a Shift-range span a page boundary; the anchor is an **id**
  for the same reason, since an index points at a different offer after every load-more.
- **The archive side has no special case, and a reader will look for one.** `archived` is one of the four filters, so
  crossing to it dispatches `opened` and empties the selection through the mechanism that already exists. The card takes
  `pickable` and is simply told; it never learns what an archive is. Only the endpoint is indifferent — it archives what
  it is given, because the scope is a screen decision and not a data one.
- **The checkbox is the first control this card has ever carried, and the column got wider to hold it.** It is a third
  flex column with `align-self: flex-start`, so the score ring, the title's first line and the box read as one row —
  stretched, it would centre itself down a four-row card and stop reading as a row at all. Three placements were
  measured on the way there, and the complaint that started it ("it takes height") was a width problem: on either side
  the box costs the body 34px on every line — a 19px box plus the card's 1rem gap — which is what pushed long titles
  onto an extra line. Floated inside the body it cost only the line it occupied but then sat below the score rather than
  beside it; absolutely positioned it costs nothing and lets a long title run underneath it, and the only fix for *that*
  is an inline padding on the title, which is the 34px back on every line of it. So the width is paid once, in
  `--lg-list-w`: 34rem → 36rem. Measured at 1440 after the change: score 56px at x=46, body 388px at x=117, checkbox at
  x=520 — against 358px of body had the column stayed at 34rem, so the two rem buy back 30 of the 34, and the reading
  column is 548px.
- **`.pick` still carries `position: relative; z-index: 1`,** or `.title a::after { inset: 0 }`
  lies on top of it and a click opens the offer instead of ticking it, silently — the rule
  `offer-card.css` had already written down for the day a control arrived, and which
  `.card-status` on the board implements. A float is still in flow, so the lift works there exactly as it does on a flex
  child. The wrapper is not called `.checkbox`: DaisyUI ships a component under that name, the trap
  `.status` sprang in the header. The input's own `preventDefault` is the other half — `[checked]`
  writes only on a change, the browser has already flipped the DOM, and a Shift-click on a ticked card would otherwise
  show the opposite of the truth.
- **The confirmation is a native `<dialog>` opened with `showModal()`, never `show()` and never
  `[open]`.** Only `showModal()` gives the top layer, the backdrop, the inert page and the focus trap, which is why
  there is no roving tabindex and no hand-written trap here — the mirror image of the header's popover, which chose a
  popover *because* a modal traps focus. With DaisyUI's `.modal` the three are visually identical, since DaisyUI keys
  its visibility off
  `[open]` that `show()` also sets, so a screenshot cannot tell you which one shipped. No local
  `.modal` or `.dialog` class either: an author `display` beats the UA's unimportant
  `dialog:not([open]) { display: none }` and the panel then stands open forever while every API reports it closed. jsdom
  implements `HTMLDialogElement` as a bare `HTMLElement` with `open`
  and nothing else, so every call is optional-chained and no spec opens the dialog.
