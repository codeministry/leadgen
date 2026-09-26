<img src="../brand/leadgen.png" alt="LEADgen / AI" height="28">

# Ingest, extraction and manual entry

How a document becomes an offer: connectors, the configured extraction table, the Markdown inbox and the review in front
of it.

These are working notes moved out of `CLAUDE.md` so the always-loaded file stays small.
Every paragraph here was paid for once; none of it is a summary.

## Ingest and extraction

`backend/…/ingest/`. A connector fetches documents, `HtmlBlockExtractor` splits them into
blocks and reads fields, `OfferMapper` turns a block into an `ExtractedOffer`, `OfferStore`
upserts it. `POST /api/v1/ingest` runs one pass.

- **No selector is written in Java.** Block selector, every field, the date format and the
  proxy parameter all come from the source's `extraction` section. That is what makes a
  new source a YAML block. The worked example is `local-eml` in
  `backend/src/main/resources/leadgen/sources.yaml`.
- **The eight field names are the contract** between `sources.yaml` and `OfferMapper`:
  `title`, `url`, `description`, `location`, `portal`, `agency`, `published`, `tags`. A
  field spelled differently is extracted and then ignored, in silence.
- **`expect_count_from_subject` is the only check nothing else can make.** A selector that
  stops matching loses offers, and fewer offers is indistinguishable from a quiet day on the
  market. The document states its own count; a mismatch is logged loudly and never discards
  what did come through.
- **`selector.from` is part of the server-side search, not only of the post-filter.** The
  progress flag is one name for every source and the receiver writes it to whatever its search
  returned, so two sources sharing a folder race and the loser reads nothing — silently. Asking
  the server for the senders a source actually wants makes the flag it sets its own business
  again, and it gives back something the flag had taken: widening `from` now reaches the mails
  behind it. `subject_matches` cannot join it, because a Java regex is not an IMAP SEARCH, so
  two sources told apart by subject alone still need their own folders.
- **A table-layout mail has no classes to select, so the card is addressed by the shape of its
  link.** Both search-agent portals ship mjml-style tables whose only stable feature is that a
  project link looks like a project link. `table:has(a[href*=…])` is the obvious selector and
  the wrong one: it matches every ancestor table too, and counted 46, 56 and 31 cards where
  there were 9, 11 and 6. The child combinator is what makes it exact —
  `td:has(> p > a[href*=…])` — and the count is the thing worth pinning in a test, because a
  block that yields no title is dropped without a word.
- **Neither search-agent mail carries a description, and that is a measurement about the hard
  filter.** One portal states a start phrase, an agency and a place; the other adds a created
  date, a contract form and a start. Neither states prose. `NO_CORE_SKILL` and `ROLE_OR_STACK`
  therefore judge those offers on the title alone and more gets through — which is acceptable
  here only because the mails come from saved searches that are already narrow, and because
  enrichment fetches the real advert immediately afterwards. The location is still extracted:
  `OUT_OF_REACH` is the largest filter stage by far.
- **A field's label can be shared with two other fields, and then `prefix` is the wrong tool.**
  One portal writes `Ort: … // Vertragsart: … // Start: …` into a single element, so a prefix
  read hands back all three. `prefix` also returns before `regex`, `attr` and `list` are ever
  applied, so the two cannot be combined; the way through is a selector narrow enough to reach
  that one element and a `regex` with a group.
- **A second source inherits an extraction, it never copies one.** `extraction.inherit: <id>`
  resolves at load, one level only. Two copies of a selector table drift, and the copy nobody
  looks at drifts unnoticed.
- **`prefer_part` picks the alternative, and the search runs backwards.**
  `multipart/alternative` orders its parts least-preferred first, so the plain-text version
  comes before the HTML one — taking part zero yields text with none of the structure the
  rules address.
- **A field's `format` describes the whole value, not a prefix of it.** The source-level
  `date_format` is the fallback. Cutting the raw string to the pattern's length works only
  while the two happen to line up, and stops at the first quoted literal.
- **Meta fields are addressed by their emoji prefix, never by position.** Four spans sit in
  one row and 9.2 % of offers state no company — read by position, every following field of
  those offers is shifted by one.
- **`text()` joins every node with a space, so an advert arrives as one line — and keeping
  only the block boundaries is not enough either.** Paragraphs come back and the headings,
  the bullet lists and the emphasis stay gone, which on screen is a column of equal-looking
  paragraphs: nearly the wall it replaced. `HtmlToMarkdown` converts the element to
  Markdown instead, which keeps all of it and is still plain text, so the filter still
  matches words and a reader with no renderer still sees the ad.
- **Only the prose field is read that way, and it is named rather than inferred.** The
  markup does not say which field is a document: a title sits in an `<h3>` on the sample
  source and would arrive as "### Senior Java Developer" — in the shortlist, in the
  fingerprint and in the cover letter. `description` in ingest and `full_text` in
  enrichment are the two, and the eight field names were already the contract.
- **A pattern still reads the collapsed text.** A regex in YAML is written against a line,
  `.` does not match a newline, and `**` around a word breaks it outright. **A pattern
  reads a line, a field reads a document.**
- **Links are resolved against the page before conversion.** A portal writes
  `/projects/argo-cd`, and a relative link surviving into the Markdown is a link into *this*
  application's router, which answers it with the shortlist. Without a base URI the target
  is dropped and the text kept, which is the right way round.
- **`ProxyLink.unwrap` is a privacy boundary, not a convenience.** Every link in the corpus
  carries the subscriber's address as a query parameter. An unrecognised wrapper therefore
  loses its whole query rather than keeping it. `SampleCorpusAcceptanceTest` fails on an
  `@`, an `email=` or a `%40` in any of the 1289 URLs.
- **Extraction needs no language model** for this source: `fallback: none`, and the count
  the subject announces matches in all 14 mails.
- **`published_on` keeps the date and drops the time.** The source states a time without a
  zone, which cannot become an instant without guessing; the freshness rule counts days.
- **The upsert on `(source_id, external_id)` is what makes re-reading free.** A newsletter
  repeats what is still open, so re-reading is the normal case. `written` in the report
  counts rows touched, insert or update alike — 1289 offers extracted become 1280 rows,
  because nine listings appear in two mails. **That difference is not deduplication**:
  dedupe collapses one *project* advertised by several portals, this collapses one *listing* seen twice.
- **The acceptance test is skipped without the corpus.** `docs/samples/emails/` is
  gitignored, so it is absent on a fresh clone and in CI. `ExtractionTest` covers the same
  mechanics against a fixture that ships, and that one must stay in step.
- **The IMAP side is Spring Integration's `ImapMailReceiver`, used with no channel and no
  poller.** A run is a synchronous pull that has to come back with per-source counts, and an
  inbound channel adapter has nothing to hand back. Three of its behaviours are documented
  nowhere near the setter that causes them, and each yields zero documents from an intact
  mailbox: it resolves `integrationEvaluationContext` on init, so `spring-integration-mail`
  alone fails with "No such bean" without `spring-boot-starter-integration`; with
  `autoCloseFolder` on it closes the folder before a body can be read, so every message ends
  in `FolderClosedException`; and with it **off** `receive()` hands back Spring messages
  rather than `jakarta.mail` ones, which an `instanceof` check silently drops.
- **The search term is this connector's own, and it must be.** Spring Integration's default
  `SearchTermStrategy` excludes every message carrying `\Seen` as well as the ones carrying
  the user flag. Pointed at a mailbox its owner reads on a phone, the receiver therefore
  hands over nothing, the run reports zero documents, and nothing errors — measured against
  the real mailbox: 165 mails in the folder, 165 matching `NOT KEYWORD leadgen`, 0 matching
  the default term. Not marking `\Seen` is pointless if progress is read off it. The
  replacement asks one question, "not deleted and not carrying the `leadgen` flag", and
  `\Deleted` is in there because an unexpunged deletion is not a document, not because it
  is progress. Every test before this delivered a fresh, unseen mail, which is why the suite
  was green for a connector that returned nothing.
- **Not flagging `\Seen` still takes two things.** `shouldMarkMessagesAsRead(false)` is not
  enough on its own: fetching a body otherwise issues `FETCH BODY[]`, and the server sets the
  flag regardless. `mail.imap.peek` is the second. Measured against a real IMAP server.
- **Three guarantees were given up when the cursor went, and they are worth naming.** The
  receiver tracks what it has handed over with a *user flag* written into the mailbox, not
  with a UID watermark kept on this side. So the tool no longer leaves the owner's mailbox
  untouched; "a message the selector skipped is not progress" is gone, because the flag lands
  on everything the *search* returned before sender, subject and age are applied; and a
  recreated folder has no equivalent of the `UIDVALIDITY` reset. `flaggedAsFallback` is off,
  so a server without user flags gets no marker rather than a `\Flagged` the owner would see.
  What still holds: no `\Seen`, no `\Flagged`, no `\Deleted`.
- **The flag is one name per instance, not one name for the tool.** It was the constant `leadgen`, and a laptop and
  the deployed server read the same mailbox: whichever searched first flagged a mail, and the other never saw it, with
  nothing in either log. The deployed instance simply had fewer offers, which looks like a quiet week. The name is now
  the connection's `progress_flag` (`IMAP_PROGRESS_FLAG`, default `leadgen`, so an installation that sets nothing
  behaves as before), and the connector logs it per run so two instances can be told apart. A new name re-reads the
  folder once: every mail from the selected senders comes back, `since_days` is applied only afterwards, the upsert
  keeps it from duplicating anything, and an `llm` source pays one extraction call per mail. The `state` and
  `mark_seen` selector keys, which described the dropped UID cursor and were read by nothing, are refused by name.
- **`IngestCursor`, `IngestCursorStore` and the `ingest_cursor` table are gone** (`V21`). They
  were read by nobody once the connector moved to the user flag, and two ways to remember the
  same thing is one too many — the one nobody reads is the one that rots. The three guarantees
  given up with the cursor are the paragraph above; dropping the table changed none of them, it
  only stopped a schema claiming a mechanism the code had abandoned.
- **One failing source must not end the run.** `IngestService` catches `IngestException` per
  source, so an unreachable mailbox does not stop the file sources behind it.
- **The schedule is a cron expression the application reads, and the default is no schedule.** Three options were on the
  table. A `@Scheduled(fixedDelayString = …)` has no way to say "off": there is no disabled marker and a zero delay is a
  busy loop, so the switch would have had to be a second key, and the state where the boolean is off while the interval
  looks configured is exactly the one an operator misreads. A `@ConditionalOnProperty` on the bean reads better and is
  the trap: `processAot` evaluates conditions at build time, so the image would be built with the schedule off and
  setting the variable in the running container would change nothing, silently — which is this repository's whole
  category of expensive failure. A placeholder inside `@Scheduled(cron = …)` is resolved when the task is registered,
  which is at runtime in the operator's own environment, and Spring's `-` is a disabled marker that needs no second key.
  The deployed instance schedules the run with a Kubernetes CronJob and will keep doing so; this exists because Docker
  Compose is the supported way to run this repository and Compose schedules nothing. The timezone is deliberately the
  JVM's: naming one here would be wiring in where the operator lives.
- **`match_all` is weaker than its documentation, and its obvious test proves nothing.** The key reads as "dedicated
  folder: take everything", and `ImapSourceConnector.matches` does return early on it. But the selector's senders are
  also in the IMAP `SEARCH` term, and `fromAnyOf` never looks at the flag, so a source with both `match_all: true` and
  a `from` still reads only that sender's mail. `docs/ADDING-A-SOURCE.md` claims both filters are short-circuited;
  only `subject_matches` is, because a Java regex cannot go into an IMAP search in the first place. The second half is
  worse for testing: with neither filter configured, the early return and the fall-through reach the same answer, so a
  test that configures a dedicated source the way the documentation describes it passes with the feature switched off.
  `ImapSourceConnectorTest` therefore carries two cases: the claim's own end state, and a source whose
  `subject_matches` matches nothing, which is the only arrangement in which the flag is observable at all.
- **The sentence was corrected and the loader made the flag load-bearing, rather than the search term being changed.**
  Putting `from` behind the flag was the other option and is the wrong one: it is precisely what lets a dedicated
  source flag its neighbours' mail, which is the failure `from`-in-the-search exists to prevent. So the documentation
  now says `subject_matches` only, and `ConfigLoader.checkSelectors` refuses three arrangements at load: `match_all`
  beside `from`, because the two say opposite things and the sender wins; a selector naming no filter at all, because
  reading the whole folder is both a legitimate intention and an accident that looks identical to it until a second
  sort of mail arrives; and `match_all` in a folder another *enabled* source reads, because the dedicated source marks
  that source's mail as taken before it runs. All three were previously accepted, so this is a breaking configuration
  change, and pre-1.0 that is a PATCH. The alternative to the second rule was a warning, which is what the log already
  does for an empty `onsite_cities` — refused here instead, because a warning at startup about a source that will read
  the wrong mail is read once and then never again.
- **The `<mark>` trap is not reproducible in the current corpus** — zero occurrences in all
  14 mails. jsoup's `text()` strips it regardless, and `ExtractionTest` guards it, but treat
  it as an expectation rather than a measurement.

## Manual entry

`backend/…/manual/` plus the `markdown-frontmatter` strategy in `ingest/extract/`, and
`features/review/` in front of it. A `.md` file dropped in the inbox becomes an offer on
the next run; a file uploaded through the browser waits for review first.

- **It is a `file` source, not a new mechanism.** `manual-inbox` in the shipped
  `sources.yaml` points at a directory and reads `*.md`. An upload only has to put the
  file where that source is already looking, so copying one in by hand works with no UI at
  all — and there is no second code path to keep in step.
- **One document is one offer here**, unlike the newsletter where one document holds a
  hundred. So there is no block selector and no `expect_count_from_subject`; what earns
  this a strategy of its own is that it stays deterministic. An offer typed by hand needs
  no language model to be read, which keeps *rules before model* true on the one path a
  person walks by hand.
- **The frontmatter is the eight-field contract, and the body is the description.** The
  body wins over a `description:` key: someone who writes both means the prose they typed
  under the fence. A key spelled differently is read and then ignored, in silence, which is
  exactly why an upload has to be reviewed before it becomes an offer.
- **YAML resolves scalars, so everything is stringified before `OfferMapper` sees it.** A
  bare `2026-09-01` parses to a date, and `String.valueOf` on it yields a form no
  `date_format` describes.
- **`external_id` falls back to a hash of the title and the text.** The upsert is on
  `(source_id, external_id)`, so without it the same ad uploaded twice is two offers and
  deduplication has to clean up after. It is a weak identity, but it is the one the
  document itself carries, and re-reading has to stay free.
- **`ProxyLink.unwrap` still runs.** A file pasted out of the newsletter carries the
  subscriber's address in every link, and it does not matter that the document arrived by
  hand.
- **A relative source `path` resolves against the configuration directory**, not the
  working directory — `Directories.under`. The same rule the four YAML files follow.
  Against the working directory the very same configuration points at `backend/…` under
  `bootRun`, at the repository root in an IDE and at neither from a jar: three empty
  directories that all look like a source with nothing in it. Measured: a test run created
  `backend/config/inbox/pending` before the rule was applied, outside the gitignore that
  was written for `config/`. A path that only resolves from the working directory is still
  read, **and the fallback logs a warning naming both paths** — exactly as the config
  loader's does, and for the same reason: breaking an existing configuration over a style
  is not worth it, but a directory outside the one the process was pointed at must not be
  silent.
- **`pending/` is a subdirectory of the inbox and is inert by construction.** The file
  connector lists regular files only, so what waits for review cannot be ingested by
  accident.
- **A file with no frontmatter is the source's `fallback` decision, not the extractor's.**
  Under `none` it yields no offer and stays where it is. Under `llm` it is handed to a
  model, and every other way out is a sentence in the log — an unknown fallback value is
  named rather than treated as `none`, and a configuration that reaches no model says so
  instead of looking like an unreadable document.

## The extraction fallback

`backend/…/ingest/extract/LlmExtractor`, its per-run factory `LlmExtractors`, and the
one-method seam `ExtractionFallback` that `MarkdownExtractor` calls.

- **It is the exception that proves *rules before model*, not a break in it.** Every
  structured source is read by CSS or by frontmatter and costs nothing; what reaches here
  is a pasted advert, where there is no structure to address and the alternative is not a
  cheaper reading but no offer at all. The model is asked only after the deterministic rule
  found nothing, and a test fails if a readable frontmatter asks anything.
- **The description is never the model's.** It is the document, verbatim. A model asked for
  a description writes a summary, and that summary is what the enrichment stage, the
  classifier and the judge would then all read instead of the advert. What the model is
  asked for is the seven short fields around it.
- **Two of those seven are checked against the document before they are kept**, and both
  are the kind of mistake that is invisible on the review screen. A `url` that is not in
  the document character for character is an address the model assembled out of a portal's
  name, and it leads somewhere real. A `published` date needs a quote from the document
  behind it — the same rule the field extractor follows — and has to fall between 2000 and
  today, because `published` is what the freshness rule counts days from and a future date
  stays new for as long as the offer exists.
- **A document with no title yields nothing.** An offer without one is worse than no offer:
  it reaches the shortlist as a blank row.
- **It reads `llm.models.extraction`, and falls back to `scoring`.** The key shipped with
  `# not read yet` beside it for three releases, which is the same class of lie as an
  unimplemented auth mode. Falling back rather than demanding a second entry is the same
  argument that made an API key optional for Ollama: most installations run one model, and
  there would be nothing to write in the second line. Which of the two was taken is said
  once per process, and the Rules screen shows the same choice because it asks the same
  method rather than reproducing it.
- **The reading is cached per version of a file, and the reason is correctness before
  cost.** The review queue describes every waiting document on every request, so without a
  cache a list of pasted adverts pays for one model call each, measured at about a minute
  for a single document against a local 20B model. Worse, a model asked the same question
  twice does not answer identically: the list, the panel and the confirm would each show a
  slightly different reading of the same unchanged file, and the operator would be
  approving the last one by accident. The key is the file's size and timestamp, so editing
  the document on disk produces a fresh reading and the file stays the state.

## The upload and its review

`backend/…/manual/ManualUploadService` and `web/ManualSourceController`, with
`features/review/` on the other end. The first endpoint that puts a file on disk.

- **An upload lands in `pending/` and becomes an offer only when somebody confirms it.**
  A pasted ad can be extracted wrongly and a frontmatter key spelled differently is read
  and then ignored, in silence. Without the step in between, a bad reading enters the
  shortlist, which is the one list that gets trusted instead of the mailbox.
- **The review screen marks the fields a model read, and only those.** Without the marks a
  reading the rules made and one a model made look exactly alike, so everything gets
  checked equally — which in practice means nothing does. The badge sits *inside* the
  label, so it is part of the input's accessible name rather than a decoration beside it.
  The provenance travels in the response and never into the file: what the confirm writes
  is the eight-field contract and nothing else, because by then the values have been
  through a person.
- **No staging table: the file is the state.** It can be read with `cat`, confirming is a
  move, and a rejected upload is a file that was deleted rather than a row nobody looks at
  again. The correction is written back into the document, so re-reading the same file
  later produces the same offer.
- **`ManualDocumentName` is the whole attack surface, and it is one file.** `sanitize`
  decides what a name may contain, `resolve` decides where the result may land, and both
  run on every path. The second check is not redundant: a rule enforced only by
  construction stops being enforced the first time construction changes.
- **A directory part in an uploaded name is dropped, not cleaned.** A name is a name, and
  the only reason an upload carries a path is that someone wants it somewhere else.
  `../../etc/passwd.md` becomes `passwd.md`.
- **The extension list is an allowlist, and it is the source's glob.** Anything but `.md`
  is a file nothing would ever read again, so accepting it would only be a place to store
  things.
- **The size limit is checked twice on purpose.** `spring.servlet.multipart.max-file-size`
  belongs to the container and answers with a framework error; the explicit check belongs
  to the endpoint and answers with a sentence naming the limit.
- **Deduplication answers before the confirm, not after.** The same fingerprint the dedupe
  pass uses is looked up while there is still a decision to make, so adding something
  already in the pipeline costs nothing and says so.
- **The 400 carries its reason as plain text.** "only .md documents are accepted" is
  actionable; a bare 400 is a support request. The store shows the server's sentence rather
  than one of its own.
- **`security.auth` is answered rather than left open.** Only `none` is implemented, so any
  other value is now **fatal at load** — someone writing `basic` and believing the write
  endpoints are protected is the worst failure available here. What stands in front of them
  instead is `server.address`, which defaults to `127.0.0.1`; the container overrides it
  because a process bound to loopback inside one is reachable through nothing at all.
- **Uploading is not ingesting.** The file goes in the queue and *Run ingest* does the
  rest, so there is exactly one thing in this application that reads sources.
- **A long advert is folded, and the decision is a character count rather than a measured height.** A portal ad runs to
  several thousand characters with everything the tool decided underneath it, so unfolded the ad *is* the page.
  Measuring the overflow would mean
  `scrollHeight` against a clamp or a `ResizeObserver`, and both are suspended in a backgrounded tab — the toggle would
  be missing exactly where a screenshot says the page is fine. The clamp is a `max-height` plus an alpha-ramp mask, not
  a truncated string, so the Markdown stays whole in the DOM and the browser's own find still reaches the end of it. No
  transition either: a height animation between a clamp and `auto` needs a measured target.
- **What is unfolded is an offer id, not a boolean.** A boolean survives the navigation to the next offer, so its ad
  opens too, for a decision nobody made about it. Holding the id makes the reset fall out of the comparison and needs no
  effect to undo it.

- **Punctuation does not belong around `@if`.** A count assembled as
  `{{ n }} waiting@if (…) { , … }.` renders with the template's own whitespace inside the
  sentence — "1 waiting for review , 1 already in the pipeline ." on the page. Build the
  sentence in TypeScript.

## Traps moved from backend/CLAUDE.md

- **Several IMAP sources may share a folder only because `selector.from` is in the `SearchTerm`.** The progress flag is
  one `progress_flag` per connection and the receiver writes it to whatever its *search* returned, before `matches()` sees sender or
  subject — so without that term the first source flags the others' mail and they read zero documents in silence.
  `subject_matches` cannot join it (Java regex vs. IMAP SEARCH), and `match_all: true` switches the check off: both mean
  separate folders. The same point, with the earlier premise, stands near the top of this file.
