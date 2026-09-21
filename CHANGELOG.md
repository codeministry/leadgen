# Changelog

All notable changes to this project are documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this
project intends to follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html) —
"intends", because while the version is below `1.0.0` the configuration schema and the API
may change in any release. See the status note in the README.

## [Unreleased]

### Added

- **`extraction.strategy: llm` works, for a document that never had a structure to
  select.** A direct enquiry somebody typed becomes one offer: no block splitting, because
  a mail a person wrote is not a list. It is not a fallback and does not fire when
  deterministic extraction failed — a source chooses it because it never had rules, and a
  source with structure keeps `html-blocks`. The link a model reads goes through the same
  proxy unwrap as every other path, because an address found by a model is still an
  address. Without a reachable model the source yields nothing and the pass carries on.
  Noted while wiring it: `sample-portal-feed` in the shipped example names `strategy:
  single` and `type: rss`, and this build dispatches on neither, so that block cannot run.
  It is now named in a test rather than left to be discovered.

- **The tool can start a pass by itself.** `INGEST_CRON` takes a Spring cron expression in
  the JVM's timezone and defaults to `-`, which is no schedule at all, so the shipped image
  reads nobody's mailbox until it is asked to. A deployment that already schedules the run
  from outside, with a Kubernetes CronJob or with cron on the host, leaves the key alone. It
  is a cron expression rather than an interval because only cron carries a disabled marker,
  and the bean is unconditional rather than `@ConditionalOnProperty`, because Spring AOT
  evaluates a bean condition at build time and the image would then ignore the variable in
  the running container without saying so. A pass that is already running is skipped and
  logged rather than queued.

### Changed

- **An IMAP selector now has to say which messages are its own, and three arrangements are
  refused when the configuration is read.** `match_all: true` short-circuits
  `subject_matches` and never `from`, because the senders are part of the server-side
  `SEARCH` and removing them there is exactly what lets one source mark another's mail as
  taken. So the flag beside `from` is refused, because the two say opposite things and the
  sender quietly wins; a selector naming no filter at all is refused, because reading the
  whole folder is both a legitimate intention and an accident that looks identical to it
  until a second sort of mail arrives; and `match_all` in a folder a second *enabled* source
  reads is refused, because the dedicated source flags that source's mail before it runs and
  the run then reports zero with no error anywhere. **Breaking:** all three were accepted
  before. A configuration using one of them fails at startup with the source named, and the
  fix is a filter, a `match_all: true`, or a folder of its own. `docs/ADDING-A-SOURCE.md`
  documented the `from` short-circuit that never existed and is corrected.

## [0.4.0] — 2026-09-21

The release that changes how the artifact runs: the image ships the jar unpacked with Spring
AOT switched on, and three things break underneath it — the API prefix, the Postgres major,
and what `PACKAGED` means. Everything written up as `0.4.0` on 2026-09-16 and never tagged is
part of it. The upgrade notes are not optional this time; one of them takes a database dump *before* the deploy.

### Added

- **The shortlist can be searched by meaning, over a vector of the whole advert.** A second
  column, `offer.retrieval_embedding`, a stage that fills it after `SCORE`, and a filter that
  reads it. It is a second column and not the one deduplication uses, because the two answer
  different questions: `offer.embedding` holds the title, the location and 600 characters, and
  that is the text the merge band was measured against. Re-using one column cannot be made
  safe — after a re-embed some rows would carry each text under the same model name, the guard
  in `SimilarOffers` would pass, and the cosine between two incomparable vectors is a number
  rather than an error. **The search narrows; it never reorders.** A relevance order would make
  the sort key a function of the request and the cursor a function of the query text, which is
  what `ShortlistSort` and `Cursor` exist to prevent, so `retrieval.neighbours` is a count and
  there is no threshold to measure. The consequence is stated rather than hidden: with the
  filter on, the count reads "6 of 9 · closest matches only". Measured 2026-09-17 over 252
  fetched and segmented adverts, the advert text produces 347 pairs above 0.8 against the
  teaser's 488, and the ten in the merge band are one project posted twice — which refutes a
  prediction this repository made, left standing struck through in
  `docs/decisions/retrieval.md`.

- **Five questions an advert can be asked on the detail screen** — the rate, the client, being
  on site, onboarding, an extension — answered from that advert and nothing else. **Every claim
  carries a sentence from the advert, and a claim whose sentence is not in the advert is
  dropped.** That is the whole design, and the rest are arrangements around it: without the
  check this screen would be a confident, specific, unfalsifiable answer about a document the
  reader is holding; with it, a wrong answer is a missing answer. There is no retrieval, and
  that is the point — one de-furnitured advert fits whole in any context window worth
  configuring, so choosing what to leave out would only be a way to leave something out. The
  questions are an enum, so nothing a caller sends reaches a prompt and the cost per advert has
  a ceiling; the model is `llm.models.scoring`, which three stages already share; nothing is
  stored, so nothing goes stale when the content stage rewrites the advert underneath it.
  Silent is an answer and comes back 200, refused is not one and comes back 409 — no model, no
  fetched text and a spent budget all mean nobody could ask, and drawn the same way a reader
  takes a spent budget for a quiet advert.

- **A card can be dragged into its next state on the pipeline board**, through the same `PATCH`
  the eleven-entry picker already made. **A state is the drop target, never a lane**: four of
  the five lanes hold more than one state and `closed` begins at `WON`, so a lane-wide target
  would have marked a dropped card won on the strength of the enum's declaration order. At rest
  a lane stays one list; the zones and their headings appear while a card is being picked up,
  on `pointerdown` and not on `cdkDragStarted` — a defect rather than a preference, because the
  CDK caches every container's rectangle at the first move past the threshold and never asks
  again, so a zone revealed after it is measured collapsed and a drop over it produces no
  request at all. Measured in the browser, both ways. The card moves before the answer is back
  and goes back where it was when the write fails.

- **A document with no frontmatter can now be read by a language model** — the `fallback: llm`
  that the shipped `sources.yaml` has declared since the beginning and nothing implemented. A
  pasted advert dropped into the review queue comes back with its title, url, location, portal,
  agency, publication date and tags filled in; the description stays the document itself, because
  a summary is what every later stage would otherwise read instead of the advert. Two of those
  fields are checked against the document before they are kept: a `url` that is not in it
  character for character is discarded, and a `published` date needs a quote from the document
  behind it and has to fall between 2000 and today. Rules before model still holds — the model is
  asked only where the deterministic rule found nothing, and without a reachable model the
  document is left where it is, exactly as before.

- **The review screen marks which fields a model read.** Only those get the badge, and it sits
  inside the label so it is part of the input's accessible name. Nothing of it is written into the
  file on confirm.

- **The Rules screen shows the extraction prompt**, first of the three, in the order the pipeline
  asks them.

- **Deduplication compares adverts that are not identical.** The two `embedding_cosine`
  strategies the shipped `matching-rules.yaml` has listed since the beginning now run: above a
  cosine similarity of 0.97 an offer is attached to the older one it duplicates, above 0.95 it
  is marked and left alone. The shortlist badges the mark and can filter on it; nothing is
  hidden, because the working list is what gets trusted instead of the mailbox. Both numbers
  were measured rather than assumed — the population and the method are under *Changed*.
  Without `llm.models.embedding` only `exact_fingerprint` runs, exactly as before.

- **`llm.budget.max_calls_per_day` is a ceiling now and not a number in a file.** Every request
  that leaves for a model counts once — judge, classifier, field extraction, a document with no
  frontmatter, and an embedding of thirty-two adverts alike. Collecting an already-submitted
  batch does not count, because those answers are paid for. When the day is spent each stage
  stops asking and leaves its work due, so the next run continues and nothing is written as
  answered that was not. The count is a row per day in the database, so a restart does not hand
  the allowance out twice.

- **`llm.timeout` bounds a model request in time**, read by `ChatModels` and `EmbeddingModels`,
  default `PT120S`. The hard-coded 30 s was comfortable for a hosted endpoint and not for a
  local one loading a 20B model first: measured on the deployed instance, content, fields and
  scoring each gave up at the ceiling in one run and reported "without a usable answer" for an
  answer that had never arrived. The value is set in the per-call options and not only on the
  client, because Spring AI's own 60 s otherwise wins and the configured number is read by
  nobody.

- **The offer carries two more facts about itself: `sourceName` and `ingestedAt`.** The first is
  provenance rather than content — `portal` is who advertises the project, `sourceName` is which
  configured input delivered it here, as `sources.yaml` names it and as the sources screen lists
  it. The second is the counterpart of `publishedOn`: that one is what the advert says about
  itself, this one is when this tool first saw it. It is written once at ingest and no later
  stage touches it, which is also why the `fresh` sort key can rest on it.

- **`backend/smoke/smoke.sh` checks a finished image, and CI runs it.** Twenty-four checks in
  seven groups against the built container rather than against the code: the context boots and
  is not a GraalVM fallback, all 27 migrations ran and `vector` exists, all four configuration
  files bound (one off disk and three off the classpath), a cover letter rendered through
  FreeMarker, IMAP read a mailbox, and a stubbed model answer came back through the vendor SDK.
  Each one asserts on a result, because the failure this exists for is an empty answer from a
  service that reports healthy. It brings up its own Compose project on its own volume and
  tears it down on exit. `backend/smoke/measure.sh` beside it compares two images and is run by
  hand.

- **Two search-agent portals can be read beside the aggregator, and both are a block of YAML.**
  The selectors and the senders name a portal, so they live in the operator's `config/` and not
  in the repository, and `SearchAgentCorpusTest` checks them against the saved mails and skips
  itself when either is absent — the arrangement the aggregator's corpus test already has.
  Measured on five real mails: 26 cards out of three, and 1 out of each of the other two.

- **`docs/README.md` is the map of the documentation**, and `docs/WRITING-RULES.md` documents
  every `matching-rules.yaml` key beside the existing worked example for `sources.yaml`. The
  keys bound and rendered but read by nothing are marked as such in both, rather than left to
  be discovered.

### Changed

- **Every endpoint moved from `/api` to `/api/v1`, and this is a breaking change.** The paths
  were the one part of this tool with no version in them, on a service whose own changelog
  header says the API may change in any release while the version is below `1.0.0` — so the
  next shape change would have had nowhere to go but on top of the old path. There is no alias
  and no redirect: the prefix is unversioned exactly once, and carrying it for a release would
  be the second implementation of the same route that this repository avoids everywhere else.
  `SECURITY.md` names the four write endpoints at their new paths, the frontend calls them, and
  `backend/smoke/smoke.sh` keeps the prefix in one variable rather than ten strings.

- **A package is built when a person asks for one, not when a run ends.** Reaching the
  shortlist opened an application directly at `PACKAGED` and built its folder; measured on
  the deployed instance on 2026-09-17, that was **93 applications at `PACKAGED` against 2
  ever sent**, and 76 directories nothing ever deleted. The run now opens the application at
  `NEW` in a stage of its own, `OPEN`, and the folder is built when somebody moves it to
  `PACKAGED` — after that write commits, in the background, with the `PACKAGE` stage left in
  the run as the retry. On a healthy instance that stage reports zero from now on.

- **`PACKAGED` cannot be skipped, and it is the only transition that cannot.** `NEW` and
  `SHORTLISTED` reach each other, `PACKAGED`, `REJECTED` and `EXPIRED`; anything else answers
  409. Everything past the package stays as free as it was, correction in any direction
       included. The rule is served as `GET /api/v1/applications/transitions` rather than copied
       into the browser, and the picker greys out what the endpoint would refuse. Moving an
       application back now also clears `sentOn`, `followUpOn` and `outcome`, so a corrected row
       does not keep the dates of an attempt it no longer claims.

- **Archiving an offer now discards its package, unless the application was ever sent**, and
  restoring one puts it back at `NEW`. Both halves exist to keep "`PACKAGED`" and "there is a
  folder" the same fact. The age pass does neither: it reconciles, and a pass that reverses
  itself must not delete files. A new `OrphanSweep` removes, at every start, any direct child
  of the packaging output directory that carries a `meta.json` and that no `package_dir`
  names.

- **`ApplicationStatus.isLive()` exempts from `NEW` upwards instead of everything but
  `PACKAGED`.** The old wording was correct for the old meaning and is exactly inverted for
  the new one: left alone it would have archived the offers somebody is preparing and exempted
  every offer nobody has looked at, which is the whole shortlist.

- **`V27` brings the existing corpus to the same meaning**: every application that neither
  stands at nor was ever moved to `SENT`, `REPLIED`, `INTERVIEW` or `OFFER` goes back to
  `NEW`, loses the dates of that attempt and gives up its `package_dir`. What was genuinely
  sent keeps its status and its folder. **Take a dump before deploying it** — it is not
  reversible, and the directories it orphans are removed by `OrphanSweep` at the next start.

- **The database image is `pgvector/pgvector:0.8.6-pg18`, and the tag is pinned.** It was
  `postgres:17-alpine`, which carries no `vector` extension at all, so `V22` needs the image as
  much as the image needs `V22`. Both numbers are values in the diff rather than the date
  somebody last pulled: 0.8.6 still refuses an HNSW index above 2000 dimensions, so the
  columns and the indexes stay exactly as they are, and a floating tag would swap the extension
  binary under a live data directory with nothing in the diff to show for it. It is Debian-based
  rather than Alpine, so it is a larger first pull.

- **Compose mounts the database volume at `/var/lib/postgresql`, not at `…/postgresql/data`.**
  Postgres 18 scoped `PGDATA` by major version and moved the declared VOLUME up one level. The
  old target is not rejected, it is ignored, and that is the reason this line exists: the
  container initdbs an empty cluster elsewhere, Flyway applies everything green, and the API
  serves an empty working list with nothing in the log to explain it. `PGDATA` is now also
  stated in the service, beside the mount that has to match it.

- **The embedding column is 2000 wide and the similarity thresholds are 0.97 and 0.95**, all
  three measured rather than assumed. 2222 real adverts were embedded outside the application
  and compared pair by pair: at the `0.85` this file shipped with, `nomic-embed-text` paired
  12147 of them, about eleven flags per offer, and at `0.92` it merged two different projects
  that shared one agency's title template. It is trained on English and the adverts are German.
  `qwen3-embedding:8b` pairs 3470 at the same 0.85 and keeps the real duplicates above 0.96,
  so the numbers moved to where the pairs actually are: **18 merges and 63 flags** on that
  population instead of 322 and 12147.

- **A model wider than the column is truncated to its leading 2000 dimensions** instead of
  being refused. pgvector builds no HNSW index above 2000 for `vector` or 4000 for `halfvec`,
  both measured against 0.8.6, so a 4096-dimensional model is otherwise unusable. Cutting
  `qwen3-embedding:8b` to 2000 moves the 0.85 band by two percent, because it is trained with
  Matryoshka representation learning and its leading dimensions carry the separation. The cut
  is announced once per process; a model narrower than the column is still refused by name.

- **The embedding pass skips archived offers**, which scopes the two similarity strategies to
  the working list while the exact fingerprint keeps running across the whole window. A fresh
  offer no longer disappears behind an archived primary, and a standing backlog no longer costs
  a day's call budget to embed adverts nobody will see again: on 13240 offers of which 13232
  were archived, the window held 11437 rows to embed and 8 of them were on the working list.
  In a nightly run nothing changes, because archiving happens after this stage.

- **`docs/samples/measure_embeddings.ts` is how a threshold is changed.** It takes the working
  list and a model, caches the vectors so a second cut costs nothing, and writes every pair
  with both titles beside the number. Its output names real adverts and is gitignored with
  everything else derived from the corpus.

- **A letter pitches the projects the advert is about, not the ones the YAML listed first.**
  `referencesFor` counted how many of a project's `stack` tokens the advert named, kept
  `overlap > 0` and took two, with three consequences: the pitches contributed nothing although
  they are the only fields saying what a project *was*, a dozen Spring projects tied on "Java"
  and the order fell to whichever the profile listed first, and — because the filter ran before
  the limit — a letter could go out pitching one project or none, silently. **The lexical rule
  still decides everything it can**: the comparator sorts on overlap first and on similarity
  only within it, so "a real match is never outranked" is structural rather than a later
  reader's care. The model speaks in the two places the rule is silent, breaking ties among
  equal overlap and filling the slots the rule left empty. Measured over 252 adverts and six
  reference projects by `docs/samples/measure_references.ts`: **72 of 252** got fewer than two
  references under the old rule and **0** after the blend; of the 137 selections that changed,
  37 are fills, 19 are pure reorders and 36 are swaps among projects of equal overlap. One
  vector per project **and language**, cached by a digest of the text, so an edited pitch
  re-embeds itself alone. Without an embedding model, a spent budget or a vector, the output is
  byte for byte what it was.

- **A reference project states its title twice and its period as two months.**
  `reference_projects[].title` and `.period` are replaced by `title_de`, `title_en`, `from`
  and `to`. One title was one language, so a German advert was answered with an English
  project title, and `period` was free text, so `"since 2024-01"` put an English word in a
  German letter. `from`/`to` are months (`"2024-01"`); an absent `to` means the work is still
  running, and the letter renders `seit 01/2024` or `since 01/2024` itself. One of each title
  and pitch pair is enough — the other language falls back to it, in both directions, which
  the English letter previously did for the pitch only and the German one not at all.

- **The backend image ships the jar unpacked, with Spring AOT switched on.** The entry point
  is `java -Dspring.aot.enabled=true -jar /app/app.jar` over an extracted layout instead of a
  fat jar. Measured on linux/arm64, five rounds, medians: time-to-healthy 6.43 s to 3.06 s,
  `Started … in` 5.39 s to 2.55 s, idle memory 271 MiB to 237 MiB, image size unchanged at
  350 MB. Most of it is the unpacking rather than the AOT half — Boot's nested-jar class loader
  reads every dependency through a jar inside a jar — which matters because only the AOT half
  carries risk: `processAot` starts the real context at build time and freezes condition
  outcomes into the artifact. Build in Docker or CI, never from a tree with a filled-in `.env`.
  The numbers, the method and the noise floor are in `docs/decisions/native-image.md`.

- **`selector.from` is part of the IMAP search, not only of the post-filter.** The progress flag
  is one name for every source and the receiver writes it to whatever its search returned, so two
  sources sharing a folder used to race: the first flagged all of it, the second read zero
  documents with no error and no counter. Each source now asks the server only for its own
  senders. Widening `from` reaches the mails behind it again as a consequence. **`subject_matches` cannot join it** — a
  Java regex is not an IMAP SEARCH — so two sources told
  apart by subject alone still need separate folders, and `match_all: true` in a shared folder
  turns the check off entirely.

- **`llm.models.embedding` is read**, by the two similarity strategies, by the retrieval stage
  and by the reference blend, and by nothing else. Unlike the extraction fallback it has no
  fallback to `scoring`: a chat model is not an embedding model. A model narrower than the
  2000-wide column is refused with both numbers named.

- **`llm.models.extraction` is read.** It shipped with `# not read yet` beside it; the fallback
  now reads it, and takes `llm.models.scoring` when it is empty, so a single-model installation
  has nothing new to fill in. The startup log says which of the two was taken.

- **The README no longer says the embedding strategies are skipped**, which the two entries
  above make untrue, and `llm.models.writing` is named as the one key still read by nothing.

### Removed

- **`llm.budget.cache_by_message_id`.** It came from a concept in which extraction was itself a
  model call, and named a cache keyed by a mail. Extraction is deterministic now and no model is
  ever asked about a mail — it is asked about an offer, a block or a document. What the key
  promised is already true five times over: the IMAP user flag, the block-label cache, the fetch
  cache, the upload's reading cache and scoring's staleness predicate.

- **The dead `rss` stubs are gone from the shipped example's neighbourhood.** There is no rss
  connector; a source of that type was logged and skipped, which is the same class of lie as a
  configuration key nothing reads.

### Fixed

- **The cover letter named the agency as the place the advert was found.** `agency` is the
  company writing the advert, so `"über Constaff GmbH bin ich auf Ihre Ausschreibung
  gestoßen (freelancermap)"` told the recruiter that we came across their own advert through
  them. The sentence now names `portal` and drops the agency, which the letter is addressed
  to anyway; without a portal it opens `"Ich bin auf …"`.

- **Every paragraph of both letters and of the archived advert reached the client indented by
  four spaces.** Freemarker strips a line holding nothing but a directive; it does not strip
  the indentation of a text line inside an `<#if>` or a `<#list>`, and every paragraph of
  these templates sits in one. The bodies are flush left now and
  `PackagingServiceTest.rendersEveryLineFlushLeft` holds them there.

- **The German letter opened with a lowercase word**, because the sentence began with a
  conditional whose first branch was `über`.

- **A start date rendered as `2026-10-01` inside a German sentence.** Freemarker prints a
  `LocalDate` as ISO when no format is set; the letter now writes `01.10.2026` and the
  English one `1 October 2026`. The archived advert keeps ISO on purpose.

- **`<mark>` no longer survives into the fingerprint as the word "mark".** Some sources wrap
  the subscriber's own search terms in it and the tag reaches the title as text; the
  normalizer removed the angle brackets and left the tag's own name standing, twice, so
  `<mark>DevOps</mark> Engineer` fingerprinted as `mark devops mark engineer` and never met its
  twin. Measured on 13240 live offers: 402 titles carried it, 384 of them unmerged, at least
  170 matching an existing fingerprint once it is gone. **The fingerprint is written at ingest,
  so this takes effect for offers read from now on**; an existing database keeps the
  fingerprints it has.

- **The analytics counted archived offers**, so the funnel on the dashboard described the
  window and not the working list the rest of the tool talks about.

- **The free-text search read the newsletter teaser** where the advert had been fetched and
  segmented. It now reads the advert, through the CONTENT blocks rather than the whole page.

### Upgrade notes

- **Take a `pg_dump -Fc` before this deploy, and keep it until the offer count matches.** Two
  of the notes below are irreversible on their own, and they arrive together.

- **The Postgres major changes from 17 to 18, and the volume cannot be carried over.** The data
  directory moved, the existing volume cannot be handed to the new image, and `docker compose
  up` on it starts an empty database that looks entirely healthy: Flyway migrates it green and
  the API serves zero offers with nothing in the log to explain it. The migration is
  `pg_dump -Fc` out of the running 17, a fresh volume, `pg_restore` into 18, and the old volume
  kept until the counts match — written out step by step in `docs/DEVELOPMENT.md`. A deployment
  that only bumps the image tag loses sight of its data without a single error. The image also
  has to be one carrying `vector`: `V22` creates the extension and fails by name on a plain
  postgres image.

- **`V22` to `V27` add an extension, four columns and one rewrite.** The rewrite is `V27`:
  every application that neither stands at nor was ever moved to `SENT`, `REPLIED`,
  `INTERVIEW` or `OFFER` goes back to `NEW` and gives up its `package_dir`, and `OrphanSweep`
  removes the directories it orphans at the next start. That one is not reversible.

- **Anything outside this repository that calls the API has to be moved in the same deploy as
  the image.** The rewrite is mechanical — `s#/api/#/api/v1/#` over the caller — and the
  failure mode if it is missed is a `404` on every call rather than a wrong answer, so it is
  loud. `/actuator/**` is unaffected. A reverse proxy that matches on the `/api/` prefix needs
  no change; one that rewrites the path does.

- **`config/skill-profile.yaml` has to be migrated in the same deploy as the image.**
  `ConfigLoader` parses with `FAIL_ON_UNKNOWN_PROPERTIES`, so an old `title:` or `period:`
  stops the application at startup with a message naming the key. Per project: rename `title`
  to `title_de` or `title_en`, add the other language or leave it out, and replace `period`
  with `from` and `to` as `"YYYY-MM"`. The shipped default in
  `backend/src/main/resources/leadgen/skill-profile.yaml` is the worked example.

- **`cache_by_message_id` is gone, and a configuration carrying it will not start.** Unknown
  keys are fatal by design, so a `config/pipeline.yaml` copied from an older shipped file has
  to lose that one line. Nothing read it, and nothing is lost by removing it.

- **`max_calls_per_day` starts applying.** The shipped value is 300 and was read by nothing
  until now; an installation that judges more than 300 offers on one day will see the rest stay
  due until the next. **`0` means no calls at all** — no ceiling is the `budget:` block being
  absent, not a zero.

- **`LLM_MODEL_EMBEDDING` must name a model of at least 2000 dimensions.** `nomic-embed-text`
  at 768 does not fit and is refused by name. Left empty, only `exact_fingerprint` runs and
  neither the search by meaning nor the reference blend does, exactly as before. Filling the
  two vector columns for the first time costs roughly an hour per ten thousand adverts over a
  local Ollama with a 4096-dimensional model, and every 32 of them count once against
  `llm.budget.max_calls_per_day`.

- **`llm.timeout` defaults to `PT120S`** where the hard-coded value was 30 s. An installation
  in front of a hosted endpoint that wants the old behaviour has to say so.

## [0.3.2] — 2026-09-16

A release about three things that were written down as true and were not.

### Changed

- **`remote.accept_unknown` finally does something.** It was rendered on the rules screen,
  validated at load and read by nobody: setting it to `false` changed nothing at all. An offer
  that states no remote share is now rejected at the `REMOTE_SHARE` stage when the flag is off *and* a minimum above
  zero is required — at `min_remote_percent: 0` nothing is required, so
  silence cannot be a reason. The default is `true` and stays true, so nothing moves for anyone
  who has not asked for it. Its sibling under `rate:` is still read by nothing, and the shipped
  file now says why: the rate rule itself does not run in the hard filter.
- **`lg-page-header` has a third heading level.** A panel that opens inside a screen sits under
  that screen's own `h2`, so it needed an `h3` — and until now two screens had worked around
  not having one by setting the type scale on a bare heading.

### Removed

- **`ingest_cursor`, `IngestCursor` and `IngestCursorStore`** (`V21`). Progress has been a user
  flag in the mailbox since the connector moved to Spring Integration; the cursor was read by
  nobody, and two ways to remember the same thing is one too many. **The migration drops the
  table**, so a rollback to an older jar finds no cursor to resume from — nothing reads it, so
  what is lost is a resumption point nobody consults.

### Fixed

- **Nothing enforced the SPDX header on a new Java file.** Spotless has been off since the
  formatting fallout, and the header is the part of a file nobody reads in review — on an
  Apache-2.0 repository that is a licence statement quietly going missing. CI greps for it now,
  which is one rule and names the file that lacks it. Re-enabling Spotless replaces the step.

### Upgrade notes

- **`V21` drops `ingest_cursor`.** Nothing has read it since the IMAP connector moved to a
  user flag in the mailbox, so what is lost is a resumption point nobody consults — but a
  rollback to an older jar will not find the table again.
- **`remote.accept_unknown: false` now rejects.** It did nothing before. If you set it to
  `false` at some point and kept it there because the shortlist looked fine, it will start
  removing every offer that states no remote share. The shipped default is `true`.

## [0.3.1] — 2026-09-16

A release about the one screen that exists to explain a source, and could not.

### Added

- **A source can be opened, and it shows the block of `sources.yaml` that defines it.** The
  screen was a table and a footnote: it answered "what is configured and what did it last
  yield" and stopped there, so the question somebody arrives with — *why does this source
  behave like that, and since when* — had no answer on the screen that exists for it.
  `GET /api/sources/{id}` serves that block beside the connection it names and the runs it has
  had, and `/sources/:id` is a link somebody can paste into an issue.

  **The file's own bytes, never the bound configuration.** The snapshot has every
  `${IMAP_PASSWORD}` already resolved, and this endpoint stands behind nothing. It has also
  dropped every comment, and in the shipped file the newsletter block is 59 lines of which most
  are the comments that explain why progress is never read off seen/unseen — the best thing
  the panel has to show.

  **Masked anyway, by key name, through the `Secrets` the startup banner already uses**, with
  four rules that only matter when the subject is a file: only the mask and never the "(not
  set)" and "(empty)" renderings, because *a file view must not contain text the file does not
  contain*; a bare `${VAR}` survives while `${VAR:literal}` does not; flow mappings and block
  scalars are covered, because the shipped file's whole `fields:` section is written as flow
  mappings; and `username` is masked in the view but not in `Secrets`, whose job is "is it set"
  on the operator's own terminal.

  Masking protects credentials, not identity. A real block still names the operator's portal,
  their mailbox folder and their paths, so a published screenshot of this screen is taken
  against **no** `config/sources.yaml`, where the classpath defaults are already the demo
  fixture.

- **Every run a source has had, and when its numbers last moved.** `source_run` has been
  append-only since `V9` with an index on `(source_id, ran_at DESC)` and a migration comment
  saying the interesting question is when the number changed. Nothing asked it until now. The
  comparison is a `lag()` window on the server; the panel receives data and the catalog picks
  the sentence. **`written` reaches a screen for the first time** — the gap between extracted
  and written is what re-reading a newsletter looks like, measured here at 1289 extracted
  against 1280 rows.

### Changed

- **`GET /api/sources` answers an envelope, and this is a breaking change.**
  `{file, layer, sources}` instead of a bare array, and `layer` is gone from the row. It was
  one probe for the whole file — the two configuration layers override each other file by file
  and never key by key — stamped onto every row, where a badge asserted per source what cannot
  differ between two rows. Same class as `remote.accept_unknown`: rendered, validated, and
  changing nothing. The table lost a column with it, which is the only structural relief a
  seven-column table has on a phone.
- **The date on the sources table follows the chosen language.** It was a slice of the ISO
  string, so `2026-09-15` was printed to both, beside a column of prose, while a pipe that
  writes a day the way a language writes it sat in `shared/`.

### Fixed

- **A failed `GET /api/sources` said "No sources configured".** The table was hidden and the
  empty state shown, so a network fault wore the face of an empty configuration — on the one
  screen whose whole job is to make a misconfigured source visible. The error is rendered now,
  and Sources and Rules no longer share one `loading`/`error` pair: an error raised on one used
  to appear on the other after a navigation.
- **The docs placed the Markdown upload on the Sources screen.** It moved to Review with the
  split views and the endpoint kept its path, which is what let the two sentences drift apart.

### Upgrade notes

- **`GET /api/sources` changed shape.** It answers `{file, layer, sources}` instead of a bare
  array, and `layer` has left the row. Anything reading that endpoint by hand needs the one
  extra hop; the browser in this repository was changed with it.
- **No migration.** The run history reads `source_run`, which has been append-only since `V9`,
  and the block comes from the file on disk. Nothing is written by any of this.
- **A source's block is visible in the browser now**, so it is worth knowing what that shows:
  the file as written, with placeholders unresolved and values under secret-looking keys
  masked. Masking is by key name and covers credentials, not identity — a real block still
  names your portal, your mailbox folder and your paths. `security.auth` still has one
  implemented value, so what stands in front of that endpoint is `server.address`, which the
  container overrides.

## [0.3.0] — 2026-09-15

A release about the three facts a person actually sorts adverts by, about the bar that asks
for them, and about an application that stops pretending nothing is happening while it works.

### Added

- **Start, duration and application deadline, read out of the advert by a model.** The three
  regexes that were supposed to cover this fail silently: `start_date` is one pattern for one
  German date format, so "ab sofort", "Q4/2026" and "Start: KW 42" all yield nothing;
  `duration` captures the bare number, so the `TEXT` column held `"6"` rather than what the
  advert said; and nothing covered a deadline at all. An unmatched pattern is
  indistinguishable from an advert that said nothing, which is the failure this ends.

  A new stage between `CONTENT` and `SCORE` — after content because it reads the advert the
  content stage left rather than the page around it, before scoring because what it writes
  feeds `project_setup` and the judge's description of an offer. It reuses
  `llm.models.scoring`, the third stage to do so, for the reason `Classifiers` already gives:
  a `models.fields` key would be a third allowlist for a bounded question answered in three
  lines of JSON.

  **Each fact is a pair** — the phrase the advert used and a normalised value. `start_text`
  beside `starts_on`, `duration` beside `duration_months`, `apply_by_text` beside `apply_by`.
  The phrase is what a person reads and is often the whole truth; the normalised value is
  what a sort key and a filter can compare. Measured on the deployed corpus after the first
  pass: **19 of 21 offers stated at least one of the three, 9 of them a deadline** — dates
  from 08.09. to 30.09., one already expired. A start is stated as a phrase far more often
  than as a day: 18 phrases, 4 resolvable to a calendar day, because "Oktober 2026" is a
  month and a month is not a day.

  The sample corpus under `docs/samples/` states no deadline at all, and that is a property
  of the newsletter rather than of the market — the deadline only appears in the ad the
  enrichment stage fetches.

- **Six sort keys on the shortlist, with the keyset intact.** Score (unchanged, and byte for
  byte what shipped before), newest first, earliest start, nearest deadline, longest duration
  and shortest duration. `ShortlistSort` owns one SQL expression per key and derives both the
  `ORDER BY` and the page clause from it, so the two cannot disagree; an enum and never a
  validated string, because the type is the allowlist.

  Every key is wrapped in a `coalesce` whose sentinel puts "not stated" last, and **not
  `NULLS LAST`**: SQL row comparison yields NULL the moment any element is NULL, so a nullable
  key walked with `NULLS LAST` shows its unstated offers at the end of page one and loses
  every one of them on page two, while the match count still counts them.

  **The sentinel moved from the kind onto the sort constant, and `duration-asc` is why.** The
  two duration sorts read one column in two directions, so a `-1` held on `Key.NUMBER` puts
  the unstated last under DESC and *first* under ASC — a silent inversion of exactly the rows
  the sentinel design exists to protect, and each sort looks correct on its own. `fresh` is
  the other new key and the only one with nothing to fold to the end: `ingested_at` is
  NOT NULL and is already the tiebreaker of every tuple, so it names the column twice.
  Measured on the demo corpus: `duration-asc` walks 6, 6, 6, 9 | 12, 12, 12, not-stated across
  a page boundary, and there is still no `dir` parameter — a reverse is a named key, because
  over the wire the direction belongs to the whole tuple.

- **Three filters**: a start window of four values that partition the working set, a minimum
  duration in months, and "deadline still open". `unknown` is one of the four windows on
  purpose — the three dated ones all carry `IS NOT NULL`, and `starts_on` is set only where a
  day could be resolved, so a window without it would hide most of the shortlist while
  looking exactly like a filter that worked.

- **Several portals at once, a free score range, and "unscored only".** `portal` keeps its
  singular name and repeats — `?portal=a&portal=b` — so every link written while it took one
  still means what it meant; the clause is `IN (:portals)` and deliberately not
  `= ANY (:portals)`, which a named JdbcClient parameter expands into a syntax error only a
  real Postgres reports. `minScore`/`maxScore` are the band's shape with the numbers in the
  request, and they **exclude offers nobody judged**, the same null treatment `minMonths` has
  and for the same reason. `scoreState` is how that excluded set is asked for instead, which
  turns the `{count} unscored` figure beside the list from a number into an entry point.

  **The three are one axis and a request may carry one of them.** A band inside a range is
  simply the narrower of the two, and `band=shortlist` with `scoreState=unscored` is simply
  always empty — both read as a quiet market from the screen, which is the failure this
  repository keeps finding. So the combination is refused with a sentence naming both
  spellings, and the screen never produces one.

- **Saved views.** A view is a name and a query string, kept in this browser. The URL stays
  the truth: applying one replaces the query string, and nothing records "which view is
  showing", because the reader changes a filter a second later and any such flag would then
  be a lie. Every storage access is wrapped, and one corrupt entry costs one view rather than
  the list.

- **A pass in flight is visible.** `GET /api/ingest/current` answers the open `pipeline_run`
  row or `204`, carrying the start time, the model and the stage — and no counts at all, so
  there is nothing on it to mistake for a result. The header shows `ENRICH · step 4 of 11`
  beside a run button that now refuses with a reason instead of a 409 nobody saw, and the
  dashboard carries the same line above last night's numbers. A pass takes eleven minutes on
  the deployed corpus, which is long enough for "is anything happening at all" to be the only
  question worth answering.

- **The application notices that its data has changed.** One signal, two triggers: a pass
  ending anywhere, and the tab coming back to the front after half a minute away. Five stores
  already reloaded on a run finishing and the machinery was never the problem — that event
  fires only for a pass *this browser* started, so a nightly CronJob, another tab or a second
  machine left every screen showing what it had read once.

  The shortlist is the one screen that is **flagged rather than reloaded**: it is keyset-paged,
  so re-reading it starts again at page one, and a reader forty offers down would be returned
  to the top for news they did not ask about. A line above the list says so and a click does
  the rest.

- **The board's reading column can be closed.** A close control on the offer's own title line
  and `Escape` from anywhere on the screen, both the same navigation — the selection is the
  URL. Measured at 1440px: the lanes go from 769px back to 1388px. Which detail is closable is
  route data, because the shortlist opens its first entry by itself and a close there would be
  undone on the next tick.

### Changed

- **The shortlist's filter bar is three kinds of control instead of ten controls in four equal
  rows.** Nothing in it said which control did what kind of thing: a *query*, an *order* — not
  a filter at all — and five *facets*, all at one visual weight. The query takes its own row,
  the order became a trigger showing the order it is in, and four facets moved behind one
  trigger and show as removable chips when they are on. Every row of filters is a row of list,
  because that column is sticky with a scroller of its own: measured at 1440, the bar is 68px
  at rest against four rows before, and 105px at 390 with two chips, with no horizontal page
  scroll at either width.

  Chips exist for exactly what the popover hides, which is what keeps the badge honest — the
  count on the trigger is the chip list's own length. Both popovers are placed from the
  trigger's measured rect rather than from arithmetic, because a popover resolves against the
  viewport whatever `position` says; below 48rem both become bottom sheets. Nothing in any of
  it is ochre: ochre means "this survived the filter", not "a filter is on".

- **Typing in the search box is one navigation per word, not one per keystroke.** Every
  character used to be a navigation, a request and a history entry, nine in ten thrown away by
  the `switchMap` behind them, and the back button then walked back through the word one letter
  at a time. 250ms and `replaceUrl` — which is also what makes the result count a live region
  at all, since announced per keystroke it would chatter over the typing it reports on.

- **The band buttons are radios.** Exactly one of the three is always on, and `aria-pressed`
  states that they are independent toggles; native radios bring arrow-key selection and a
  roving tabindex with them. The appearance is unchanged.

- **`offer.duration` means the advert's phrase now, not the regex's digits.** Every row comes
  due on the first pass of the new stage, so the two spellings coexist only until then.

- **The shortlist cursor changed shape and is not compatible with the old one.** It is now
  `sort|key|ingested_at|id` and names the sort it was minted under, because without that a
  cursor minted under `score` with a leading value of 88 replayed under `start` reads as epoch
  day 88 and returns an arbitrary slice with no error anywhere. A cursor of the old three-part
  form is refused with `400` and a sentence — which is also two shapes that used to be `500`s.

- **The run button shows the sentence the server wrote.** A refused pass answered "The ingest
  run did not answer", while the server had written "an ingest run is already in progress;
  this one was not started" — the one message a reader could act on, replaced by one they
  could not.

- **The field extractor is asked for the value, not the row.** An advert writes "Start:
  01.10.2026" and "Laufzeit: 12 Monate", and the card prints its own label in front of
  whatever it is given, so the screen read "Start Start: 01.10.2026". Measured: 4 of 12
  phrases carried the advert's own label before the rule and 1 of 14 after, and that one
  advert puts the label in its headline.

- **`field.published` and the two new date rows are written the way the chosen language writes
  a date**, through one pipe rather than three ad-hoc approaches.

### Fixed

- **The ✕ beside the search took a reader out of the archive.** It cleared the whole query
  string, `archived` included, so pressing it while reading the archive answered with the
  working list. It clears the search alone now, and *Clear all* sits with the chips — where
  what is being cleared can be seen — and leaves the sort and the archive side standing,
  because an order is not a filter and a set is not one either. The browser's own clear ✕ on
  the search field, which sat directly beside it, is suppressed: two adjacent controls, one
  glyph, two meanings.

- **Nothing above the shortlist threshold was being packaged, and the screen said so
  politely.** `PackagingService` was the only one of the four `content_blocks` readers that
  loaded its rows with `listOfRows()` instead of a `RowMapper`, so the driver handed it a
  `PGobject` and the cast threw for every advert that had been segmented. The per-offer catch
  turned that into a counter, `package_dir` was never written, and every offer reported "a
  package is built for everything above the shortlist threshold; this offer has none yet".
  Ten packages were built on the first pass after the fix. The `tags` array beside it was the
  same defect one column over and never threw at all — it simply reached Freemarker as a
  wrapper around a JDBC array.

- **The count beside the shortlist shrank as the reader scrolled.** The cursor clause was
  appended into the same clause the match count was read with, so on page two `matched` and
  `unscored` counted the rows *after* the cursor. Precisely the defect that moved this count
  to the server in the first place, reappearing on the other side of the wire.

- **A board card stayed put after its offer was archived.** The server already leaves an
  archived offer off the board; the archive button writes through `ShortlistStore` and the
  board lives in `ApplicationsStore`, which never heard about it — so the card stood there,
  and kept counting towards the dashboard's follow-up tile, until somebody reloaded.

- **A run whose process was killed left its row open forever.** From the read side that is
  indistinguishable from a pass still going, so the button would have refused every click from
  then on. Startup is the exact moment to say so and needs no heuristic: this process is the
  only thing that runs a pass, so a row still open when it starts belongs to a process that no
  longer exists. Two such rows were found and closed on the deployed instance the first time
  this shipped.

### Upgrade notes

- **Add a `fields:` block to your own `pipeline.yaml`.** The configuration directory overrides
  the shipped file *file by file*, so an existing `pipeline.yaml` without one means the stage
  never runs — and the symptom is empty start, duration and deadline values, which looks
  exactly like adverts that state nothing. `fields: { enabled: true }` is the whole of it.

- **Old shortlist links carrying a `cursor` parameter stop working** and answer `400` with a
  sentence. Links carrying only filters are unaffected.

- **A shortlist link carrying `portal` keeps working**, because the parameter kept its name
  and only learned to repeat. A hand-written request carrying a band *and* a score range, or
  either with `scoreState`, is refused with `400` and a sentence.

- Three migrations, `V18`, `V19` and `V20`. None rewrites existing data — `V20` adds the two
  indexes the new sort keys read — and `offer.duration` changes meaning as the new stage
  reaches each row.

## [0.2.1] — 2026-09-07

### Added

- **Archiving in bulk, from the shortlist.** Taking twenty offers off the working list cost twenty round trips and
  twenty confirmations. A card now carries a checkbox — the first control it has ever had — a Shift-click spans a range,
  and one action archives the selection behind a native `<dialog>`. `POST /api/offers/archive` is the endpoint, and it
  is the one place in this application where the plural breaks the singular's rule: `PATCH /api/offers/{id}` answers
  with the whole `ShortlistEntry` because the browser replaces its row with what the server stored, and the list does
  not replace an archived row, it drops it. Entries would be fetched for the sole purpose of being discarded, at roughly
  1.4 KB each. So the answer is a report — `requested`, `archived`, `unscored` — and the endpoint answers `200` where
  the single `PATCH` answers `404`: refusing fifty decisions because one id named no offer loses forty-nine for a reason
  nobody can act on.

  The selection lives in `ShortlistStore` and not in the query string, unlike every filter. Fifty ids in a URL is not a
  link anybody sends, and it would make the back button undo a checkbox. It clears on `opened`, which already fires on
  every filter change, so the picks cannot drift from the list they point into; `moreLoaded` deliberately writes
  nothing, which is what keeps a selection across paging and lets a Shift-range cross a page boundary. The anchor is an
  **id** rather than an index, because an index points at a different offer after every load-more.

  There is no bulk restore, which is why `ArchiveRequest` names no direction. A `boolean` with exactly one legal value
  is the class of thing this repository already removed twice — after `remote.accept_unknown` and the `onsite_max_km`
  key nothing read.

- **Two more content rules in the shipped `pipeline.yaml`**, both anchored to a whole block: the portal's two row
  actions, which arrive as exactly `Print Report`, and the report dialog's heading. Neither word is safe on its own — a
  bare `report` matches an advert asking for reporting experience, and `print` one about print media.

### Changed

- **The build is on Java 25.** It is the current LTS and the version Spring Boot 4.1 states support for. JaCoCo moved to
  0.8.13 with it, because a JaCoCo that cannot read the class-file version reports coverage of nothing rather than
  failing. See **Upgrading** — this one is not free for anybody building from source.

### Upgrading

**Building from source now needs a JDK 25 on the machine.** `settings.gradle.kts` has no toolchain resolver, so Gradle
cannot download one, and the failure is `No matching toolchains found` rather than anything naming a version. The
published images already carry it; nothing about a running deployment changes, and there is no migration.

The two new content rules ship in the classpath `pipeline.yaml`. The two configuration layers override **file by file**,
so a `pipeline.yaml` of your own does not receive them — copy the two `content.rules` entries across, or the portal's
report dialog stays in the advert and stays in what the judge is asked about.

## [0.2.0] — 2026-09-06

The scoring half changed shape here: a total is a share of what an advert made attainable rather than a sum over every
weight, so a number written by 0.1.x and a number written by 0.2.0 are not the same measurement. See **Upgrading**.

### Added

- **Split views.** Reading an offer used to cost the list — the shortlist, the board and the review queue each replaced
  their list with the thing being read. All three now keep the list on the left and open what is selected on the right,
  in two columns that scroll independently under a screen bounded to the viewport. The shortlist and the board open a
  child route on a real component; the review holds the file name in a query parameter, because its document is already
  in the store the screen reads. `/offers/:id` redirects into the shortlist's split view, so there is one detail view in
  the code rather than two.
- **Keyboard navigation in the shortlist.** `j`/`k` and the arrow keys move between entries, and past the last loaded
  one the key asks for the next page and stays put. The handler is bound to the list pane rather than to the document,
  so `j` stays a letter in the search field and the arrow keys still scroll the advert while the reader is in the detail
  column.
- **The status picker carries a visible label**, on the board and in the offer detail. A bare select beside a badge
  stated an application's state twice and named it neither time.
- **A dev-image track, between "it built" and "it was released."** `ci.yml` builds both
  images on every push and deliberately does not push them; `release.yml` pushes only on a
  `v*` tag and writes a release from this file. Getting an intermediate state in front of a
  cluster therefore meant tagging, changelogging and releasing — the wrong ceremony for a
  build nobody is announcing. `dev-image.yml` publishes one for every green `main` as
  `<next patch>-dev.<commits since the tag>-g<sha>` plus a floating `main`, creating no git
  tag and no release. It runs on `workflow_run` after CI rather than on `push`, so an image
  never exists for a commit whose gate went red, and the gate is not paid for twice.
- **`leadgen.version`, so the header names the build it is.** `StatusController` has always read `${leadgen.version:…}`
  and the property existed nowhere — not in `application.yaml`, not in the chart, and the Gradle version is not injected
  into the jar (no `buildInfo()`, no manifest entry, and the Dockerfile copies `libs/*.jar` by glob). So every image
  reported the literal default whatever it was. The deployment sets it from the image tag.

### Changed

- **A score is a share of what was attainable, not a sum over every weight.** A factor the advert said nothing about
  writes no reason at all and is in neither half of the fraction; a factor that had something to say and scored badly
  writes a 0-point row and stays in the denominator. `offer_score_reason` carries `max_points` for that, so a screen can
  read
  "23 / 45". Summed instead, the scale was capped by things no advert could influence: the sources state a rate in 0.0 %
  of offers, so all 101 scored offers carried a 0-point
  `rate_fit`, `industry_fit` fired zero times, `project_setup` averaged 0.2 of 10, and the highest score in the whole
  table was 53. The accepted consequence is that the less an advert states, the more its skill overlap carries — an
  offer is judged on what it says.
- **Skill overlap is weighted and saturates; it is no longer a count.** `matched.size() /
  core.size()` ignored the per-skill weights and read neither `strong:` nor `peripheral:`, despite the weight table's
  own comment saying otherwise — so an advert asking for Kafka, PostgreSQL, Keycloak and CI/CD scored nothing for four
  things the profile is strong in, and a backend advert was charged for not naming Angular. The matched weights are
  added up (peripheral at half) and measured against the `scoring.saturation_core_count` heaviest core skills, because
  no advert names a whole profile and requiring one requires something that never happens. Over the corpus the factor
  never once exceeded five of eight core skills.
- **A composite skill name is split on `/` before matching, and an industry is matched through `match:` rather than
  through its name.** Folding keeps a name whole, so
  `REST / API-Design` was the phrase `rest api design` and matched only an advert writing it in that order — no advert
  does. And the profile names an industry in this repository's language while the adverts are German, so `Insurance` was
  compared against text that says *Versicherung*. Same shape and same reason as a skill's aliases; the name is still
  tried, so a profile written before this behaves as it did.
- **The judge is given the profile it is judging against.** The prompt used to say "a senior Java, Spring Boot and
  Angular developer who works from Germany" — three skills of the twenty-nine in `skill-profile.yaml`, hard-coded, while
  every other stage read the file. Role fit was judged against a description of somebody else, and editing the profile
  could not move it. The offer's rate, duration, workload and start go into the description too: those come from
  enrichment rather than from the advert's prose, and a judge calling an offer vague while the row beside it states all
  four knows less than the application does.

### Fixed

- **An offer the judge never answered for still carried a total.** `role_fit` is the one factor the prompt requires even
  at zero, so its absence is an unreachable endpoint, a reply that was not JSON, or a model that ignored the
  instruction — never an opinion. Measured before `Judge.answered` existed: 63 of 101 scored offers had no judged factor
  at all, and every one of them carried a number that looked like all the others. Such an offer is left unscored now,
  which is self-healing: a null `score_model` makes it due again, and `ScoringReport.unusable` puts it in the run's own
  log.
- **`max_per_run` did not bound how long a pass waits, so a backlog never cleared.**
  Refusing rather than waiting is right for the rate limiter and wrong for the pass on top of it: a run did one minute's
  worth of fetching and deferred the rest, so a backlog needed one run per `rate_limit_per_minute` offers to clear, and
  it never got them. Measured on the live database: **2,537 offers carried `enrichment_note = 'rate limit reached'` with
  a stamped `enriched_at`**, and only 23 rows in the whole table had a `full_text`.
  `AdFetcher` now waits for a permit the window would have granted anyway, up to the run's budget, and refuses beyond
  it. Unset means the old behaviour, so a configuration written before this key behaves as it did.
- **A rate-limited fetch was written off permanently.** The limiter refuses rather than
  waits, and the refusal was recorded like a failed fetch — which stamps `enriched_at`,
  and the due query is `enriched_at IS NULL`. The offer was therefore never fetched again
  and went on to be scored on the newsletter summary alone, with no rate, no duration and
  no full text. Measured on the first full pass against a real mailbox: 480 due, 20
  fetched, **460 written off with "rate limit reached" and 0 left due.**

  The cache already draws this line — "failures are cached, timeouts are not", because a
  403 is a fact about the page and a timeout is a fact about the moment. A refusal from the
  limiter is the second kind: it says this run has asked this portal often enough, which is
  true of the minute and of nothing else. `FetchResult.deferred` now says so, nothing is
  written for those offers, and they are due again on the next pass.
  `EnrichmentReport.deferred` reports them separately from `incomplete`, because the whole
  difference between the two is whether the offer comes back.
- **The scoring stage was one transaction, and it blocked every other run.** It held a write lock on each offer it had
  judged until the last one was answered — with a local model, hours — and any concurrent pass's filter stage, which
  writes a verdict on every row with no `WHERE`, waited behind it. Measured on the cluster: two filter updates blocked
  for thirteen minutes behind a scoring transaction open for twenty, advancing one offer every 33 s, with a third run
  stacked behind those. The boundary is now one offer, one transaction, which also means a run that dies halfway keeps
  the scores it produced instead of none.
- **The enrichment stage was one transaction too**, and it became the worse of the two the moment that stage started
  waiting for rate-limit permits by design: a pass holding a write lock on every offer it had touched, for minutes, with
  any concurrent filter stage sitting behind it. Each result is one statement and nothing there needs atomicity across
  offers.
- **The dashboard listed every source twice while a pass was running.** `source_run` has no run id, so its rows are
  addressed by time, and the window had a lower bound and no upper one — on the reasoning that "no later run exists to
  contribute rows above it", which holds only while nothing else is running. A run opens its `pipeline_run` row when it
  starts now (`RUNNING`, zeros, no `finished_at`), so the next run's `started_at` is knowable and becomes the bound.
  That column is the right one because it never moves;
  `finished_at` is pushed forward by the batch collector. The old placement's intent survives — a row that claims
  nothing cannot claim a clean pass, and a run that dies leaves it saying `RUNNING`, which is more honest than leaving
  no trace.
- **`POST /api/ingest` had no concurrency guard, so a second pass simply queued in the database.** It now answers `409`
  and starts nothing. The CronJob's
  `concurrencyPolicy: Forbid` never covered this: it governs only the jobs the CronJob itself creates, and the button is
  how a run is normally started.

### Upgrading

The schema migrates itself (`V16` adds `offer_score_reason.max_points`), but **the scores already in the table do not**:
a total written by 0.1.x is a sum over every weight, a total written by 0.2.0 is a share of what the advert made
attainable, and the shortlist threshold is one number read against both. Nothing recomputes them by itself either — a
run judges what is stale, and stale means never written, a different `ruleset_version` or a different
`score_model`, none of which this release changes on its own.

Bump `version:` in your `matching-rules.yaml` once after upgrading to put the standing list back on one scale. That is
one full pass at the configured model's rate, so check
`hard_filters.freshness.max_age_days` and the archive first: everything outside that window is filtered and archived
before the stage that costs money is reached.

## [0.1.1] — 2026-09-05

### Fixed

- **The IMAP source handed over nothing from a mailbox its owner reads.** Spring
  Integration's default `SearchTermStrategy` does not express "not already taken" in terms
  of the user flag alone — it also excludes every message carrying `\Seen`. In the mailbox
  this tool is pointed at, that is every message the owner has opened, so a run reported
  zero documents with no error anywhere. Measured against a real mailbox: 165 mails in the
  folder, 165 matching `NOT KEYWORD leadgen`, 0 matching the default term.

  Not marking `\Seen` is pointless if progress is read off it, and "fewer offers" is
  indistinguishable from a quiet day on the market — which is why this was invisible.
  `ImapSourceConnector` now supplies its own search term: not deleted, and not carrying the
  `leadgen` user flag. Every existing test delivered a fresh, unseen mail, so none of them
  could see it; the new one marks the message read first.

### Upgrading

Nothing to configure. The first run after this release hands over the whole backlog the
old search term was hiding, bounded only by the source's `selector.since_days` — on the
mailbox this was measured against, 138 newsletters announcing 14,241 offers, where every
previous run had reported zero. That pass is long and it is a one-off; the runs after it
see only what has arrived since.

What it costs is decided by `hard_filters.freshness.max_age_days`, not by `since_days`.
The archive pass sits between the hard filter and enrichment, so everything older than
that window is read, deduplicated, filtered and archived without ever reaching the stage
that leaves the machine or the one that calls a language model. Check that number before
the first run rather than after it.

## [0.1.0] — 2026-09-02

The first public release. Everything below already existed; this is the point at which it
became readable by somebody who did not write it.

### Added

- **The pipeline, end to end.** Ingest from files and IMAP, declarative extraction, an
  upsert that makes re-reading a newsletter free, deduplication of one project advertised
  by several portals, six deterministic knockout stages, an age archive, enrichment of the
  original ad, deterministic scoring with an optional language-model judge, an application
  package per shortlisted offer, and a digest written to a file.
- **Manual entry and its review.** A Markdown file uploaded through the browser waits in
  `inbox/pending/` until somebody has seen what was read from it; confirming writes the
  corrected frontmatter back and moves the file where the source is already looking.
- **Manual status capture.** Eleven application states across five lanes, every change
  recorded as an event, because the half of the loop that involves a human is the half the
  tool cannot observe.
- **Seven screens**, all on real endpoints: dashboard, shortlist, offer detail, pipeline
  board, analytics, sources, rules and review. Two themes, two languages, English the
  fallback.
- **A demo dataset** under `demo/` — an invented corpus, profile and rule set, so a fresh
  clone opens on a populated application. `docker compose -f docker-compose.yml -f
  docker-compose.demo.yml up`.
- **Apache-2.0 licence**, SPDX headers on every Java source, enforced by Spotless rather
  than remembered.
- **Coverage reporting** — JaCoCo for the backend, v8 for the frontend, both on `check`.
- **Spring portfolio where it earns its place.** The judges go through Spring AI's
  `ChatClient`, so two hand-written wire formats become none; the ad fetcher goes through
  `RestClient` with Framework 7's `RetryTemplate`, closing a gap where there had been no
  retry at all; the IMAP source goes through Spring Integration's `ImapMailReceiver`; and
  every run now records what each of its stages cost, in `pipeline_stage`.
- **Deliberately not adopted, each for a measured reason.** Spring Batch, because the
  pipeline has no chunk work and no restart requirement and would have gained nine tables of
  foreign DDL; Resilience4j's rate limiter, because it is a fixed window where this needs a
  sliding one; and Spring's cache abstraction for the page cache, because it has no JDBC
  provider and no per-entry TTL.

### Fixed

Found while building the demo, all of them in paths only a container exercises:

- `MANUAL_INBOX_DIR` was never set under Compose, so its default resolved inside the
  read-only `/config` mount and every upload failed with a permission error naming a path
  nobody had configured.
- `DIGEST_DIR` likewise defaulted to a directory under the container's working directory,
  which the non-root user cannot write. A run completed every stage and died on the last.
- The Anthropic judge discarded every answer that arrived wrapped in a Markdown fence,
  which was all of them on the model tested. The effect was four missing factors on an
  offer that looked judged, and one warning per offer as the only sign. The braces now
  decide, and the warning carries the text it could not read.
- `sources.yaml` required a `connections:` block. A configuration whose sources are all
  files has no credentials to carry, and leaving the key out failed the whole file at
  startup several frames away from the source that needed no connection.
- The dashboard's filter panel said "seven stages" after `STALE` moved to the archive and
  left six.
- The shortlist card printed the description's Markdown syntax in its teaser.

[Unreleased]: https://github.com/codeministry/leadgen/compare/v0.4.0...HEAD
[0.4.0]: https://github.com/codeministry/leadgen/releases/tag/v0.4.0
[0.3.2]: https://github.com/codeministry/leadgen/releases/tag/v0.3.2
[0.3.1]: https://github.com/codeministry/leadgen/releases/tag/v0.3.1
[0.3.0]: https://github.com/codeministry/leadgen/releases/tag/v0.3.0
[0.2.1]: https://github.com/codeministry/leadgen/releases/tag/v0.2.1
[0.2.0]: https://github.com/codeministry/leadgen/releases/tag/v0.2.0
[0.1.1]: https://github.com/codeministry/leadgen/releases/tag/v0.1.1
[0.1.0]: https://github.com/codeministry/leadgen/releases/tag/v0.1.0
