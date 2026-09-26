<img src="../brand/leadgen.png" alt="LEADgen / AI" height="28">

# Enrichment, content segmentation and the three fields

The only stage that leaves the machine, the split of a fetched advert into what is actually the advert, and the
start/duration/deadline pair extraction.

These are working notes moved out of `CLAUDE.md` so the always-loaded file stays small.
Every paragraph here was paid for once; none of it is a summary.

## Enrichment

`backend/…/enrich/`. The only stage that leaves the machine, run after the hard filter
and only on what survived — fetching a thousand ads to then discard eight hundred would
be rude to the portals and slow for nothing.

- **It never discards.** A fetch that is forbidden, rate-limited, unreachable or
  unreadable leaves the offer in the pipeline with a note saying why. Scoring then judges
  an incomplete offer as incomplete, which someone can review; an offer that quietly
  stopped existing cannot be.
- **Four gates, cheapest first:** cache, `robots.txt`, rate limit, network. A cached page
  costs nothing and consumes no rate-limit token, which is what makes a daily run one
  request per ad per week instead of one per ad per day.
- **A refusal from the rate limiter is deferred, never recorded.** The limiter refuses
  rather than waits, so a backlog larger than the limit is the normal case on a first full
  pass. Recorded like a failed fetch it stamps `enriched_at`, and the due query is
  `enriched_at IS NULL` — the offer is then never fetched again and is scored on the
  newsletter summary alone. Measured: 480 due, 20 fetched, 460 written off, 0 left due.
  `FetchResult.deferred` writes nothing at all, and `EnrichmentReport` counts it apart from
  `incomplete` because the difference between the two is whether the offer comes back.
- **`max_per_run` is how long a pass waits, and the window is untouched by it.** Refusing rather than waiting is right
  for the limiter and wrong for the pass on top of it: a run did one minute's worth and deferred everything else, so a
  backlog needed one run per
  `rate_limit_per_minute` offers to clear. It never did — measured on the live database, **2,537 offers carried
  `enrichment_note = 'rate limit reached'` with a stamped
  `enriched_at`** and only 23 rows in the whole table had a `full_text`. `AdFetcher` now waits for a permit the window
  would have granted anyway, up to the run's budget, and refuses beyond it. Unset means the old behaviour, so a
  configuration written before this key behaves as it did.
- **`max_per_run` is a hard cap on what a pass sends, so the unit is reserved before the wait.** The budget used to be
  asked only when the window was full, so a pass ran past it whenever the window happened to have room — measured, a
  budget of 25 over 30 adverts at 20 a minute sent 30. Reserving first also keeps it a cap under
  `enrichment.fetch.concurrency`: counted after a permit was granted, four workers could each see one unit left and all
  four take a permit for it. Only an interrupted wait gives its unit back, because it sent nothing.
- **A window permit is taken per request attempt, retries included; the budget per advert.** The retry template's two
  extra attempts on a 5xx are requests, and one permit per advert let a permit buy three of them — a pass against a
  portal answering 503 could send three times `rate_limit_per_minute`. Each retry now takes a permit inside the
  retryable body: the run waits for it like the first, the button asks once and on a refusal stops with the 5xx it
  already had. `max_per_run` stays a number of adverts, because that is what a pass promises to look at.
- **The stage is therefore deliberately not `@Transactional`**, the same shape
  `ScoringService` documents. Each result is one statement and nothing needs atomicity across offers; held as one
  transaction, a pass that now waits for minutes by design would hold a write lock on every offer it had touched, and
  any concurrent filter stage — which writes a verdict on every row — would sit behind it.
- **The `pause` seam is why the waiting is testable.** The wait is computed from the injectable clock and served by the
  real one, so a frozen clock would mean a minute of actual sleeping and then a window that never frees. Overridden, a
  test moves the clock by the same amount instead.
- **Failures are cached, timeouts are not.** A 403 or a disallowed path is a fact about
  the page; a timeout is a fact about the moment, and remembering one bad minute for a
  week is worse than asking again tomorrow.
- **A cached failure reports itself as cached.** `FetchResult.cachedFailure` exists
  because the interesting property of a cached result is not that it failed but that *no
  request was made* — a cached 403 reporting itself as fresh makes the request count a lie.
- **The rate limit is a sliding window.** Twenty a minute has to mean twenty in any sixty
  seconds, not twenty at the top of each minute and forty across the boundary. That is also
  why it is not Resilience4j's: its `RateLimiter` resets permits at fixed cycle boundaries, so
  adopting it would be a documented regression rather than a simplification.
- **Retry is Framework 7's `RetryTemplate`, and it wraps the network call alone.** Two
  attempts with backoff, on a transport failure or a 5xx and never on a 4xx. Around `fetch`
  it would retry past the cache and past the rate limiter, spending tokens the limiter had
  already refused. `@Retryable` is not usable here: `AdFetcher` is built per run from the
  hot-reloadable settings, so there is no bean and no proxy.
- **An unreachable `robots.txt` means allowed.** That is the convention, and the
  alternative is worse: a host whose robots.txt times out would silently stop being
  enriched and its offers would look merely incomplete.
- **No selector and no pattern is written in Java.** `enrichment.extract.fields` is a
  field-to-rule map in YAML, exactly like `sources.yaml`, because every portal renders an
  ad differently and a new one has to be a block and not a release. The seven field names
  — `rate`, `duration`, `workload`, `remote_percent`, `start_date`, `contact`,
  `full_text` — are the contract; a field spelled differently is read and then ignored, in
  silence. `strategy: readability` used to sit in the schema with nothing implementing it.
- **`contact` holds a person's name or nothing, decided in Java after the rule has run.** The rule
  reads the contact by position — the text in front of a phone label, or a portal's contact element —
  and on the collapsed page the shipped pattern runs back to the last digit, so what it captures is
  the tail of the previous sentence with the role label in tow: ". Ansprechpartnerin Frau Meier",
  "Monate. Wir freuen uns auf Ihre Bewerbung per". Measured on the local corpus, 0 of 165 enriched
  offers held a person. Tightening the pattern would not fix it, because the pattern is the portal's
  markup and belongs in YAML, while what a name looks like is the same on every portal. `ContactName`
  therefore keeps the captured value only when it reads as a name and stores null otherwise, with
  the letter's rule: an honorific anywhere in the capture starts the name and the first word that is
  not a name word ends it; without one, the whole capture has to be two to four name words, a role
  label in front allowed, because two capitalised words in running German are a noun pair as often as
  a person. Deterministic and free — no model is asked. The cover letter is not affected: its
  salutation reads the advert's text, never this column.
- **Regexes in YAML need single quotes.** A double-quoted scalar only allows a fixed set
  of escapes, and `\-` is not among them; the file fails to parse with "while scanning a
  double-quoted scalar" and nothing points at the regex. Single quotes pass backslashes
  through untouched.
- **Every enriched column is nullable and null means "not stated", never zero.** The whole
  reason this stage exists is that the newsletter states a rate in 0.0 % of offers, so a
  missing value has to stay distinguishable from a low one.
- **`full_text` is what the page contained, not what the advert says.** The difference is read by the next stage — see §
  *Content segmentation* — and this one deliberately does not narrow it: a fetch is the record of what was there.
- **The page cache lives in Postgres.** The TTL is a week, the container has no volume for
  a scratch directory, and a cache that does not survive a restart turns a rate limit into
  a promise nobody keeps.
- **The one way past the cache is a person, one offer at a time.** A failed fetch stamps `enriched_at` and the
  cache keeps the refusal for its TTL, so the run never asks that page again, which is right for a 403 and wrong for a
  page that only answered badly for a minute. `POST /api/v1/offers/{id}/fetch` skips the cache read and nothing
  else: robots.txt is still asked and its refusal still remembered, and the answer is stored like any other. It is the
  run's own code narrowed to one id, stage by stage, so the button cannot drift from the night (spec
  `004-refetch-original-ad`).
- **The rate window is one instance, shared by the run and the button.** It lived in `AdFetcher`, which is built per
  pass, so every pass and every press had a window of its own and a click during a run could exceed the limit it
  promises the portal. `FetchWindow` is a singleton; the run waits for a permit because nobody is watching it, the
  button asks once and answers 429 with the reason, writing nothing. The budget stays per pass.
- **The button takes its permit before robots.txt, the run after it.** The run reads robots.txt once per host per
  pass, so its order costs nothing; the button builds a fetcher per press, and robots-first let every press send one
  request no window counted, even with the minute spent. A permit spent on a path robots then refuses is the price.
- **The button never fetches an offer that already has its ad.** A failed fetch records its reason over the enrichment
  columns, `full_text` among them, so a press on an offer whose page answers 500 today would throw away an ad read
  last week. The lookup refuses such an offer with 409, and the button's write is a no-op once text has landed, which
  closes the window between two presses.

## Content segmentation

`backend/…/content/`, between enrichment and scoring. Which parts of a fetched advert are the advert.

- **It is a fix to the score before it is a fix to the screen.** `RuleScorer` folds
  `title + description + <the ad>` into one haystack and the judge is handed the same text, so a portal's own tag
  cloud — sixty technology names taken from the site's taxonomy rather than from the client's requirements — counted as
  skill overlap and moved offers onto the shortlist. Measured on offer 13690. The screen being three times too long is
  the visible half of the same defect.
- **The model is the authority; determinism is a cache in front of it, not a filter above it.** The obvious arrangement
  is the wrong way round here and the corpus says why:
  freelancermap is 11,689 of 13,240 offers and 100 % of what is enriched, so a per-portal selector table is a
  maintenance bet on one site's markup — and a selector that stops matching fails *silently*, which reads as a cleaner
  advert. Every block is normalised and hashed instead; a known digest is free, an unknown one costs one model call and
  is free from then on. **New furniture is noticed by construction rather than mislabelled in silence.**
- **The part that costs the most is unreachable by any selector.** The recruiter's own signature — postal address,
  `Amtsgericht … HRB …`, the privacy link, "Weitere interessante Projekte finden Sie in unserer LinkedIn Gruppe" — sits
  *inside* the description container and differs per agency. That is what the model is for; the portal chrome around it
  is what the rules and the cache are for.
- **Fail open, always.** A block no rule matched, that the cache does not know and that no model answered about stays
  `CONTENT` and stays on the screen. `content_undecided` counts those, and it is the number to watch: it is what a
  changed markup looks like from here.
- **A narrower `full_text` selector was measured and not taken.** `.project-body-description`
  exists on the sample source and would remove the whole header structurally — but
  `AdExtractor` uses `document.selectFirst`, and a comma-separated selector is a union whose first match is decided by
  **document order, not by selector order**. Adding the narrow one to the existing list therefore changes nothing while
  looking like it should, and replacing the list outright makes every portal without that class yield no `full_text` at
  all. One mechanism was the better answer than one and a half.
- **`content_block_label` is scoped by portal and deliberately not keyed by the model.** A score is a scale, so two
  judges are two scales and a model change makes every score stale. A label is a fact about a paragraph: once decided it
  stands, and re-labelling is a deliberate act — truncate the table, null `content_at` — never something a configuration
  edit triggers. The `sample` column is the first 200 characters, because a table of hashes nobody can audit is a table
  nobody trusts. That holds for a switched `llm.models.content` too: the adverts come due again, but a rule or a cached
  label still answers first, so a new key re-walks blocks, not requests, and only the blocks nobody has a label for go
  to the new model.
- **Above width 1 a shared, uncached block may be asked up to `width` times in one run.** The cache is consulted per
  advert when that advert starts, so adverts of one portal that share a block no run has labelled yet may each ask the
  model for it before the first answer is remembered. The upsert settles the label either way, the budget still bounds
  the total, and from the next wave on the block is free. Holding a lock per digest across a model call would buy back
  a few requests on the first night of a new portal at the price of serialising exactly the waits the width exists to
  overlap.
- **`content_at` is stamped only when the pass is finished with the offer.** A model that was configured and did not
  answer leaves it null, so the offer comes back; no model configured stamps it, because rules-only is a legitimate
  terminal state and not a failure to retry. The second half of the due query is `content_model IS NULL` — the same
  self-healing shape
  `score_model IS NULL` already has, so configuring a key at five in the afternoon makes the standing backlog due with
  no migration.
- **Scoring is made to re-read by nulling `score_model`,** and only when what scoring reads actually moved. That is
  the mechanism already documented as self-healing rather than a fourth staleness criterion invented for the scoring
  stage. Segmented for the first time, the comparison is against the whole page, so the answer is "something was taken
  out" and an advert that is all advert costs no re-judge. Segmented before — a refetch, or a switched
  `llm.models.content` — it is the texts of the blocks kept as the advert against the row's previous ones, so a new
  model that agrees with the old reading costs no re-judge either; a block's reason and decider are not compared,
  because scoring reads neither, and neither is a change between two furniture kinds. The comparison over-nulls rather
  than under-nulls: two block splits that join to the same text still read as a move. The price of the first pass is one full re-judge of
  the standing shortlist, which is what fixing a corrupted score costs.
- **The blocks carry their text inline in `offer.content_blocks`, and `full_text` is never edited.** A second copy per
  offer buys three things: the browser needs no splitter of its own, so there is no second implementation to drift; the
  indices cannot slip; and a later change of mind in the shared cache cannot rewrite what was decided for an advert
  somebody has already read. `offer-archive.ftl` still prints `full_text` — the archive is the original as fetched, and
  that is the point of it.
- **The blocks are on `ShortlistEntry` and populated by the detail query alone.** The list uses the same row mapper, and
  a block list riding along would put a second copy of every advert into a response this repository already measures in
  megabytes.
- **A block is a Markdown block, and two boundaries are forced that Markdown does not force.**
  A heading always starts one, and so does a list's first item — without the second rule the sample corpus yields the
  company link, the contact, the meta row and both buttons as a single paragraph, because flexmark writes those as hard
  breaks inside one.
- **The honest limit: a block that is nine parts boilerplate and one part per-offer text never repeats and never gets a
  cache hit.** On the sample source that is the report dialog with the advert's taxonomy line glued to its end. It costs
  one model call per advert, which is the budget anyway.
- **`content.rules` are an optimisation, not the mechanism**, and every one of them is anchored or specific on purpose.
  A loose pattern here does not produce a wrong label, it hides a paragraph of somebody's advert — a bare `datenschutz`
  would delete exactly the offers this tool is looking for. `apply now\s+save to watchlist` and not either word alone,
  for the same reason and because a block arrives as one line.
- **The classifier reads `llm.models.content`, and empty means `llm.models.scoring`.** It used to read `scoring`
  alone, on the argument that a second key would mean a second allowlist, a second history entry and a second select;
  those costs belong to a per-run choice, which this stage does not have. The reversal and what it cost are in
  [pipeline-scoring.md § A model per bounded question](pipeline-scoring.md#a-model-per-bounded-question).
- **Which classifier answers is not a parameter of the run**, unlike the judge. Two judges are two scales and comparing
  them is the point; a label is a fact about a paragraph, so there is nothing to compare and nothing worth letting a
  request decide.
- **`llm/ChatModels` and `llm/Answers` are the seam both stages share.** The sync/async client pair, the `textOf` that
  joins *every* generation (a thinking block is its own generation) and the brace-scan `objectIn` were each learned once
  at the cost of a silently empty result; a second copy would fail exactly as quietly.
- **Deliberately not `@Transactional`**, the shape `EnrichmentService` and `ScoringService`
  both document.
- **Not wired into `pipeline_run`.** The stage logs its counters and `offer.content_undecided`
  is per-offer queryable; three parameter lists in `PipelineRunRecorder` plus a migration for a number no screen yet
  reads was the first thing on the cut list.

## Start, duration and deadline

`backend/…/fields/`, between content segmentation and scoring. The three facts a person
actually sorts adverts by.

- **It replaces three regexes that fail silently.** `start_date` in
  `enrichment.extract.fields` is one pattern for one German date format, so "ab sofort",
  "Q4/2026" and "Start: KW 42" all yield nothing; `duration` captures the bare number, so
  the `TEXT` column holds `"6"` rather than what the advert said; and no pattern covers a
  deadline at all, because the *newsletter* never states one and every agency phrases it
  differently in the fetched ad. **The fetched ad does state one, often** — measured on the
  deployed corpus, 9 of 21 offers on the working list, one of them already expired. So the
  deadline is the field with the most to gain from this stage and the one the sample corpus
  is least able to show. An unmatched pattern is indistinguishable from an advert
  that said nothing, which is the failure this stage exists to end.
- **Each fact is a pair, and that is the design.** `start_text` beside `starts_on`,
  `duration` beside `duration_months`, `apply_by_text` beside `apply_by`. The phrase is what
  a person reads and is often the whole truth; the normalised value is what a sort key and a
  filter can compare. Keeping only one loses either the reading or the ordering.
- **It runs after `CONTENT` and before `SCORE`.** After content because it reads the advert
  through `ContentText` — a deadline found in a portal footer is the same class of error as
  a tag cloud counted as skill overlap. Before scoring because what it writes feeds
  `RuleScorer`'s `project_setup` and `ChatClientJudge.describe`, and because it nulls
  `score_model` on the offers whose values actually moved. `IngestOrderTest` pins both
  halves.
- **The prompt carries what the application already knows**, exactly as the judge's does:
  the regex findings are in the message, and the model is asked to confirm or correct them.
  A model told an ad is vague while the row beside it states a date knows less than the
  application does.
- **Every bound is on this side.** A phrase is cut to 200 characters, a date has to be ISO
  and inside 2000–2100, and a month count outside 1–120 is dropped. `9999-12-31` is inside
  no window on purpose: it is `ShortlistSort`'s "not stated" sentinel, and a stored one
  would sort among the offers that said nothing.
- **A value it cannot quote the advert for is discarded.** A date with no phrase is a date
  the model inferred, and kept it would be the one line on the panel nobody can check
  against the ad beside it.
- **The quote is the value, not the row.** An advert writes "Start: 01.10.2026" and
  "Laufzeit: 12 Monate", and a model asked for a short quote returns exactly that — while the
  card and the field row print their own label in front of it, so the screen read
  "Start Start: 01.10.2026". Measured on the live data: 2 of 6 start phrases and 2 of 6
  durations carried the advert's own label. The rule belongs in the prompt and not in the
  browser, where it would be a pattern guessing at somebody else's prose. Rows written before
  the rule keep their phrasing until something makes them due again.
- **`months` is the committed minimum, never the optimistic maximum.** "6 Monate mit Option
  auf Verlängerung" is 6 and the phrase carries the rest. Written into the prompt, because
  it is the rule a model otherwise decides differently every run.
- **`fields_at` is stamped only when the pass is settled and `fields_model IS NULL` is the
  second half of the due query** — the self-healing shape `content_model IS NULL` already
  has. An empty `Optional` from the extractor means "did not answer", which is a different
  thing from "the advert states none of the three"; one leaves the offer due, the other is a
  finished decision.
- **No model configured means the stage is skipped**, and the columns keep whatever the
  regexes wrote. *Rules before model* held: there is no deterministic half here that could
  decide anything for free, unlike content where a pattern can label a block.
- **It reads `llm.models.fields`, and empty means `llm.models.scoring`**, for the
  classifier's reason — see [pipeline-scoring.md § A model per bounded question](pipeline-scoring.md#a-model-per-bounded-question).
  Which extractor answers is still not a parameter of the run: a fact about an advert is not
  a scale.
- **`score_model` is nulled when any of the six columns moved, compared against the row.**
  It used to be "the answer states something", which was right while every advert was read
  once; a switched `fields` key reads every advert again, and a new model that states what
  the old one did must not buy a re-judge for a score that cannot move. So the answer is
  compared with `start_text`, `starts_on`, `duration`, `duration_months`, `apply_by_text` and
  `apply_by` as the row holds them — whatever an earlier model or the patterns left. Scoring
  reads fewer than six, so this over-nulls on a changed phrase with the same date, and never
  under-nulls.
- **Deliberately not `@Transactional`**, the shape `EnrichmentService`, `ContentService` and
  `ScoringService` all document.
- **One meaning changed under an existing column.** `duration` held the regex's capture
  group — digits — and now holds the sentence. Every row comes due on the first pass, so the
  two spellings coexist only until then.
