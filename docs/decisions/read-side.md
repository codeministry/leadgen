# The read side

Every screen reads one of these, and none of them writes: the working-set predicate, keyset paging, the sort keys and
the filters.

These are working notes moved out of `CLAUDE.md` so the always-loaded file stays small.
Every paragraph here was paid for once; none of it is a summary.

## The read side

`backend/…/offer/OfferQueryService` plus `config/SourceQueryService` and `RulesView`. Every
screen reads one of these, and none of them writes.

- **Read-only and separate from the stages that write.** Each pipeline stage owns a narrow
  slice of the `offer` row; this owns the whole row as a person reads it.
- **The working set is `status = 'PASSED' AND duplicate_of_id IS NULL AND archived_at IS
  NULL`.** All three parts, at every site that counts survivors — see § *The archive* in
  `docs/decisions/pipeline-dedupe-filter.md`.
- **The shortlist is primaries only, and so is everything that counts against it.** A row
  with `duplicate_of_id` set is the same project through a second portal, and it belongs
  inside the entry rather than beside it. The funnel and the sources screen's *survived*
  column count the same set — measured: counting every rejection against a primaries-only
  total made the rail report **-45 survivors**, and counting duplicates as survivors made
  the sources screen say 104 where the shortlist showed 96.
- **The detail is not restricted to survivors.** It is also how somebody opens an offer the
  filter rejected and asks whether the rule was right, so `/api/v1/offers/{id}` serves any id
  and `hardPass` says which it is.
- **Reasons and duplicate clusters are two queries for the whole list, not two per entry.**
- **The shortlist is paged, and the filters go with the page.** The whole list used to come
  down and the browser filtered it; at 2,219 survivors that answer was 3 MB and it grows with
  every newsletter. The query string still holds the filters, so a filtered view survives a
  reload and is shareable as a link — only the deciding moved into SQL, where it now exists
  once instead of twice. A page of a browser-filtered list is not a page of anything.
- **Keyset, never `OFFSET`.** An offset re-reads and re-sorts everything before it on every
  page, and it skips or repeats a row whenever a run rewrites a score between two requests.
  The key is the whole sort tuple — `(coalesce(score_value, -1), ingested_at, id)` — because
  score alone is not unique: seven offers at 80 would let a page boundary fall inside a tie
  and the same row could arrive on both pages or on neither.
- **Every number printed beside the list is counted by the server, over the match.** The
  portal dropdown and the unscored count were both derived from the loaded entries, so they
  told a smaller truth the further you scrolled while sitting next to a sentence about the
  whole archive. The band boundaries moved for the same reason: they are the configured
  thresholds, and two literals in TypeScript deciding which offers a button shows is the
  second implementation this rule exists to prevent.
- **The shortlist opens its first offer by itself, and only where both columns fit.** An empty right column beside a
  full list is a page waiting for a click it does not need: the first entry is the highest-scoring one the current
  filters produced. Below the stylesheet's own `72rem` the detail *replaces* the list, so auto-selecting there would
  answer "show me the shortlist" with a single offer — the condition is `matchMedia`, which is a media query and not a
  rendering-lifecycle API and therefore answers correctly in a backgrounded tab, unlike a `ResizeObserver`. The
  breakpoint is stated once on each side and tied together by a comment; jsdom has no `matchMedia` at all, so the guard
  is also what keeps the existing specs unaffected. `replaceUrl`, because the two URLs are the same screen once both
  columns fit and a history entry between them makes the back button undo a selection nobody made. Only when nothing is
  selected, so a filter change never moves the reader off the offer they are reading.
- **The dashboard says how many offers the last run judged, and which judge answered.** The per-run count and not the
  standing shortlist: a run judges what is stale, so zero is the normal outcome of a pass that found nothing new, and
  the catalog says that in words rather than leaving a bare 0 to read as scoring having stopped working — the same
  reading
  `IngestReport.merged` had to be protected from. The model comes from the recorded row alone, because an `IngestReport`
  carries none; a run this browser started therefore shows the count without a scale until the recorded row catches up.
  Two catalog keys rather than one sentence with an optional tail, because "· model null" is worse than a sentence that
  does not mention one.

- **Sorting is six keys, and the order *is* the cursor.** `ShortlistSort` owns one SQL
  expression per key and derives both the `ORDER BY` and the page clause from it, so the two
  cannot disagree. An enum and never a validated string: the type is the allowlist, so
  nothing a caller sends reaches a statement — the same argument that keeps the band
  boundaries on the server.
- **A sentinel, not `NULLS LAST`, and this is not a style choice.** SQL row comparison
  yields NULL the moment any element is NULL, so a nullable key walked with `NULLS LAST`
  shows its unstated offers at the end of page one and loses every one of them on page two,
  while `matched` still counts them. `coalesce(score_value, -1)` was already this rule; the
  general form is that the sentinel is whatever value puts "not stated" last under that
  key's direction. `keepsTheUnstatedAtTheEndOfEverySortAndNeverDropsIt` is what fails the
  day somebody replaces it.
- **The sentinel sits on the sort constant and not on the kind, and `duration-asc` is why.**
  The two duration sorts read one column in two directions, so a `-1` held on `Key.NUMBER`
  puts the unstated last under DESC and *first* under ASC. Held there, adding the reverse
  would have silently inverted exactly the rows the sentinel design exists to protect, and
  each sort looks correct on its own — which is the shape in which that mistake is invisible.
  What stays on `Key` is how a carried value binds back, because that follows the column's
  type and never its direction. `putsTheShortestDurationFirstAndStillKeepsTheUnstatedLast`
  fails the day it moves back.
- **`fresh` is the one key with nothing to fold to the end.** `ingested_at` is NOT NULL from
  the baseline and the upsert writes it, so there is no `coalesce` and no sentinel; it is also
  already the tiebreaker of every other tuple, so this sort names the column twice.
  `(ingested_at, ingested_at, id)` compares exactly as the two-element tuple would, and
  keeping it three wide keeps one shape for the clause, the `ORDER BY` and the cursor instead
  of a special case in all three. It is excluded by name from the `@EnumSource` guard above —
  by name, so a nullable key added later is still in that test by default.
- **One direction per key, fixed, and no `dir` parameter.** A row comparison is a legal
  keyset walk only while the whole tuple moves one way, so the direction belongs to the
  tuple — which means **under an ascending key the tiebreaker is oldest-ingested first**.
  Direction and sentinel are also one decision: `coalesce(duration_months, -1)` puts "not
  stated" last under DESC and *first* under ASC, so a direction parameter would change two
  things while naming one. A reverse is a named key of its own, and **`duration-asc` is the
  only one that earned it**: `minMonths` is a floor with no matching ceiling, so "which of
  these fills a gap" is the question no other control can ask. Lowest score first answers
  nothing a shortlist asks — the band filter says "show me the weak ones" far more precisely —
  and latest start is what `startWindow=later` already partitions, inside which you still want
  soonest first. That is also why the browser offers no direction toggle: over the wire a
  direction is not a modifier, so a toggle would work on one of six orders and be a control
  that lies at rest.
- **The cursor names its sort, and a mismatch is a 400.** Without the name, a cursor minted
  under `score` with a leading 88 replayed under `start` reads as epoch day 88 and returns
  an arbitrary slice with no error anywhere. The filters need no such guard: they narrow the
  set without redefining the key. Not a silent fall back to page one either — the loader is
  an `IntersectionObserver` sentinel, so that appends page one underneath page one. The same
  guard turns two 500s into answers: a cursor from the old three-part form that a shared
  link still carries, and a component that is not a number. `cursorAfter` picks its key with
  an **exhaustive switch**, so a fifth sort added without a cursor component fails the build
  rather than a page boundary.
- **The free-text search reads the advert, and reads it de-furnitured.** It matched the title,
  the description and the tags — that is, the newsletter teaser and a portal's tag cloud — while
  the advert the enrichment stage had gone out and fetched sat unread in the same row. It now
  matches `ContentText.of`'s text as well, by the same rule the judge and the packager get it:
  the CONTENT blocks when the advert was segmented, `full_text` only when it was not. Searching
  `full_text` unconditionally is the version that looks simpler and is wrong — it puts back the
  portal furniture the content stage exists to remove, and a search for an agency's postal
  address or the word in its privacy link would then match every advert that agency ever posted.
  The visible consequence is that the match count rises against the same corpus. That is the
  fix working, not a regression.
- **The page clause is kept apart from the filters, and that was a live defect.** `where()`
  appended the cursor into the same clause `MATCHED` was formatted with, so on page two
  `matched` and `unscored` counted the rows *after* the cursor and the number beside the list
  shrank as the reader scrolled — precisely the defect that moved this count to the server.
  `Filters` therefore carries `page` and `pageParams` of its own, and the store's
  `moreLoaded` reducer no longer writes `matched`/`unscored` at all: a longer list is the
  same match.
- **The start window is four values that partition the set, and `unknown` is one of them.**
  The three dated clauses all carry `IS NOT NULL`, so without it their union is not the
  unfiltered list — and `starts_on` is set only where the advert named a resolvable day,
  which is a minority. Any window would then hide most of the shortlist invisibly, because a
  short list after filtering looks exactly like a filter that worked. It is
  `remote.accept_unknown` in another costume. Selectable, it is assertable: the four counts
  sum to the unfiltered match. The thirty days are a literal in the clause and the wire value
  is `soon`, so the window can be retuned without invalidating every saved link.
- **The score axis has three spellings and a request may carry one.** A band is a range
  whose boundaries are the configured thresholds, `minScore`/`maxScore` are the same shape
  with the numbers in the request, and `scoreState` asks whether there is a score at all.
  Carried together they do not contradict each other loudly: `band=shortlist` with
  `scoreState=unscored` is simply always empty, and a band inside a range is simply the
  narrower of the two. Both read as a quiet market from the screen, which is the failure this
  repository keeps finding. So `ScoreFilter`'s compact constructor refuses the pair with a
  sentence naming both, and the screen never produces one — the three are one control group
  with three modes, and each writer clears the other two.
- **A score range excludes what states nothing; `scoreState=unscored` is how that set is
  asked for.** The same null treatment `minMonths` has, one axis over, and it is what turns
  the `{count} unscored` figure beside the list from a number into an entry point.
- **`portal` repeats rather than becoming `portals`.** `?portal=a&portal=b` binds to a
  `List<String>`, and a link written while the filter took one still means what it meant. The
  clause is `p.portal IN (:portals)` and deliberately not `= ANY (:portals)`: a *named*
  JdbcClient parameter holding a collection is expanded into a `?, ?, ?` list, which turns
  `ANY` into a syntax error only a real Postgres reports. The two positional array bindings in
  that file are positional for exactly that reason and cannot be tidied into this one.
- **`minMonths` excludes what states nothing; `deadlineOpen` includes it.** Opposite null
  treatments one clause apart, on purpose: "at least six months" is a claim about the offer
  and an offer that says nothing does not make it, while "still open" is the absence of proof
  that it closed. Exactly the pair a later tidy-up harmonises into a bug, which is why both
  carry the reason and both are pinned by a test. `current_date` is the server's, never a
  date the browser sends: two readers in two timezones must not get two lists.
- **Loading more is a sentinel, not a button**, because the list is read by scrolling; its
  `IntersectionObserver` is armed only after the first render, since one attached before layout fires immediately
  against a zero-sized box and asks for page two before page one is drawn. It is measured against the pane it was given
  rather than the window — see `docs/decisions/frontend-split-views.md`. **It cannot be verified in a backgrounded
  tab** — Chrome suspends
  the observer there, and the measurement comes back as a confident "nothing loaded". Measured through the Interceptor
  skill's `Tools/VerifyViewport.ts`: 50 offers, then 100 after scrolling.
- **A run opens its `pipeline_run` row when it starts, not when it ends.** The row says
  `RUNNING` and carries zeros, so it claims nothing — which is what the old "written last"
  placement was protecting. What it buys: `source_run` has no run id, so its rows are addressed by time, and the
  reported run's window is closed by the **next run's
  `started_at`**. Without a row at the start there was no upper bound, and a pass in flight put its rows inside the last
  finished run's window — measured, every source listed twice. The bound is `started_at` and never `finished_at`,
  because the batch collector moves the latter forward. `lastRun()` reports finished runs only; a `RUNNING` row on the
  dashboard would be zeros under the heading "last run".
- **A run whose stage throws closes its row as `FAILED`, with the counts up to that stage and every timing.** Before
  this the exception left `runOnce` before `record`, so the row stayed `RUNNING` until the next start closed it as
  `ABANDONED` with zeros, and the timings went down with it — the one record of which stage it was. `V14` had promised
  the opposite. The status is stated by the caller, never read off the timings: a dead mailbox is caught per source
  and leaves a `FAILED` `INGEST` timing under a run that completes. `lastRun()` includes `FAILED`, unlike `ABANDONED`,
  because its counts are real and its stages say where it stopped; `pipeline_stage` is read for the first time for
  exactly that, into `LastRunView.stages`. The runs chart reads finished rows only, since an open row reached the
  browser as a pass with no date.
- **`source_run` exists because nothing else can answer the announced-versus-extracted
  question.** The number of documents and the count a document announces about itself leave
  no trace in the `offer` table, and that comparison is the one check nothing else can make.
  One row per source per run, because the interesting question is when the number changed.
- **The sources screen lists the configuration, not the database.** A source that has never
  run still appears, because a misconfigured source being invisible is exactly the failure
  somebody is looking for when they open that screen.
- **Nor a threshold.** `lg-score` had 70 and 50 as input defaults and not one of its three
  callers ever overrode them, so every score ring banded off a constant while the rules
  screen and the analytics histogram showed the configured numbers. The shortlist's band
  filters had the same two literals and *decided which offers were shown* with them. Both
  now take `SCORE_THRESHOLDS`, a token provided from `core` and reached through
  `shared/shared.ports.ts` — the same seam the chart palette uses, for the same reason.
- **Nothing in the browser names a weight, a stage or a source type.** `scoring.weights` is
  an open map, the stages are the `FilterStage` enum, a source's `type` is whatever the YAML
  declares. A union type in TypeScript for any of them disagrees with the server the first
  time one is added — and the symptom is a compile error in a component that has no
  business knowing the filter at all.
- **`/api/v1/prompts` renders what is sent, and never the template.** The Rules screen answered
  "why did this offer score what it scored" for the deterministic half only; the prompt was the
  one part of the decision with nowhere to look it up. Rendered, because the two things worth
  checking are exactly the two that get substituted in — that the configured bounds reached the
  text, and that the profile behind "this developer" is the one in `skill-profile.yaml`. Both
  have been wrong here before, and a template on screen would have shown neither. The example
  user message comes out of `describe(...)` itself with placeholder values, so it drifts only
  when the code does; written out by hand beside it, it would drift in silence. It needs no key
  to render: a prompt is a fact about the configuration, not about whether anybody can currently
  be asked it. `PromptView` lives in `web/` because it needs the two stages that own the
  prompts, and the configuration model deliberately depends on no stage.
- **The enum writes its stage descriptions as sentence fragments**, because that is how they
  read in a log line. The read side capitalises them; a chart label is not a log line.

## The sources screen, opened

`config/SourceDetailService` behind `GET /api/v1/sources/{id}?runs=30`, with
`config/YamlBlocks` and `config/YamlMask` under it, and `features/sources/source-panel/` on
the other end. The first thing in this application that shows a person their own
configuration file.

- **Two obvious actions are deliberately absent, and this is where that is written down.**
  *Run only this source* cannot be built honestly: a pass has ten stages and nine are global
  by construction — `FilterService.run()` reads `SELECT id, … FROM offer ORDER BY id` with no
  `WHERE`, because the rules are hot-reloadable and a partial re-judge would split the archive
  across two rule sets, and `DeduplicationService` carries the note that a pass scoped to one
  source would never see the pair it exists to collapse. So a per-source run is either a full
  run wearing a narrower label, or a run that ingests and stops, leaving its offers at
  `status = 'INGESTED'` with that source's *Survived* column unmoved. *Switch this source off*
  has no write path: `enabled` lives in `sources.yaml` alone, the `source.enabled` column has
  been in the baseline since V1 and no line of code reads it, and six places say the read-only
  stance is deliberate. Under Compose `/config` is mounted read-only on top of that.
- **The file's own bytes, never the bound snapshot**, and the password is only the first of
  three reasons. The snapshot has every `${IMAP_PASSWORD}` already resolved and this endpoint
  stands behind nothing. The snapshot has also dropped every comment, and in the shipped
  `sources.yaml` the newsletter block runs to 59 lines of which most are the comments
  explaining why progress is never read off seen/unseen and how `multipart/alternative` orders
  its parts — the best thing the panel has to show. And the file is the state: a re-serialised
  model is something that has never existed on disk.
- **Cut first, then mask.** The opposite order was written into the plan, tried, and is broken
  by YAML itself: the mask is `********`, a plain scalar beginning with `*` is an alias, and a
  masked document stops parsing, so every block lookup after it comes back empty. Found only by
  the fixture with a literal password in it. Cutting first narrows nothing, because the
  connection block is looked up separately and masked the same way.
- **`YamlMask` adds four rules to `Secrets` and adds them here rather than there.** Only
  `MASK`, never the `(not set)` and `(empty)` renderings — those describe a *resolved* value,
  and **a file view must not contain text the file does not contain**. A bare `${VAR}` survives
  because a variable's name is not a secret and "which variable do I have to set" is half the
  reason to open the panel, while `${VAR:literal}` loses everything past the colon. Flow
  mappings and block scalars are covered, because a line-and-colon masker misses
  `defaults: { token: abc }` entirely and the shipped file's whole `fields:` section is written
  that way. And `username` is masked in the view but not in `Secrets`: the banner's job is "is
  it set" on the operator's own terminal, where a mailbox address is the useful half of the
  line, and degrading that to protect a browser tab would be the wrong trade in the wrong
  place.
- **Masking protects credentials, not identity, and the screenshot recipe is the other half.**
  A real block names the operator's portal, their mailbox folder and their paths; no rule keyed
  on names can decide that `INBOX/Jobs` is private while `INBOX` is not. A published screenshot
  is therefore taken with **no** `config/sources.yaml`, where the classpath defaults are
  already the demo fixture — every value a `${PLACEHOLDER}` and every id `sample-*`.
- **The id selects, it never addresses.** It is looked up in the snapshot's own list and
  answers 404 when it names nothing; it never reaches a path, because a request parameter that
  does is the shape of every directory traversal. Pinned from both ends: the service refuses
  `../../etc/passwd`, and the controller test asserts the segment arrives as a name.
- **SnakeYAML's end mark is not the block's last line.** It points at the first token of
  whatever follows, which for a sequence of blocks is the *next* item — measured on the shipped
  file, the newsletter block reported its end on the line reading `- id: sample-portal-feed`.
  Checking the mark's column does not save it either, because that token starts at the dash's
  column and not at zero. The bound is the next item's start, which is exact.
- **One endpoint and not two**, and nothing new on `/api/v1/sources`. One disclosure is one click,
  so two loading states in one panel would be two failure modes for one decision; and the list
  is refetched on every finished run and every tab focus, which is no place for file text.
- **The layer left the row for the envelope.** It is one probe for the whole file — the two
  layers override each other file by file and never key by key — so a badge per row asserted
  what cannot differ between two rows, the same class as `remote.accept_unknown`. `/api/v1/sources`
  answers `{file, layer, sources}` now, which is a breaking change to a published endpoint and
  is named as one in the changelog.
- **The history answers "when did the number change", because that is what its table is for.**
  `source_run` is append-only with an index on `(source_id, ran_at DESC)`, and its migration
  says so in as many words. The comparison is a `lag()` window on the server; the panel gets
  data and the catalog picks the sentence, so the number lands where each language puts it.
  `written` reaches a screen here for the first time since V9.
- **Days and not instants.** The hour a mailbox is read is the operator's working hours, it is
  on a screen whose pictures get published, and nothing the panel answers needs it. Cut in SQL
  with `ran_at::date`, by the server's clock, like every other date comparison here.
