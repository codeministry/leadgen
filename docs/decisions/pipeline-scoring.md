# Scoring, the digest and the application package

Rules before model, the weight table that outranks the judge, and the folder a run leaves on disk.

These are working notes moved out of `CLAUDE.md` so the always-loaded file stays small.
Every paragraph here was paid for once; none of it is a summary.

## Scoring and the digest

`backend/…/score/` and `…/digest/`. Two halves and one file.

- **Rules before model, again.** `RuleScorer` decides everything the profile and the offer's own fields can decide —
  skill overlap with aliases, rate against the floor,
  seniority, how much of the engagement's shape is stated, industry — for free. A `Judge`
  is asked only about role fit and the three penalties.
- **The total is a share of what was attainable, not a sum.** A factor the offer said nothing about writes no reason at
  all and is in neither half of the fraction; a factor that had something to say and scored badly writes a 0-point row
  and stays in the denominator. `ScoreReason.maxPoints` is what carries the distinction, and it is on the row so the
  screen can read "23 / 45". Summed instead, the scale was capped by things no offer could influence: the sources state
  a rate in 0.0 % of offers, so **all 101 scored offers carried a 0-point `rate_fit`**, `project_setup` averaged 0.2 of
  10, `industry_fit`
  fired **zero** times, and the highest score in the whole table was **53**. The accepted consequence is that the less
  an ad states, the more its skill overlap carries — an offer is judged on what it says.
- **`project_setup` is a bonus and the penalties are deductions; neither is in the pool.**
  Inside the denominator, an ad naming one of duration/workload/start scores 3 of 10 and lands below one that names
  nothing at all, because naming nothing keeps the factor out of the denominator entirely. As an absolute addition it is
  monotone. It is also the only factor that measures the advert rather than the fit.
- **Skill overlap is weighted and saturates; it is not a count.** `matched.size() /
  core.size()` ignored the per-skill weights and read neither `strong:` nor `peripheral:`
  despite the weight table's own comment saying otherwise — so an ad asking for Kafka, PostgreSQL, Keycloak and CI/CD
  scored nothing for four things the profile is strong in, and a backend ad was charged for not naming Angular. The
  matched weights are added up (peripheral at half) and measured against the `scoring.saturation_core_count` heaviest
  core skills, because no advert names a whole profile and requiring one requires something that never happens. Measured
  over the corpus: the factor never once exceeded five of eight core skills.
- **A composite skill name is split on `/` before matching.** Folding keeps a name whole, so `REST / API-Design` is the
  phrase `rest api design` and matches only an ad that writes it in that order. No ad does; both halves are offered to
  the matcher instead.
- **An industry is matched through `match:`, not through its name.** The profile names an industry in this repository's
  language and the adverts are German, so `Insurance` was compared against text that says *Versicherung*. Same shape and
  same reason as a skill's aliases; the name is still tried, so a profile written before this behaves as it did.
- **An alias has to be specific enough to mean something.** `Build` for Gradle and
  `Reporting` for Superset matched a plain Java backend ad and added nine points of skill weight for words that say
  nothing about a stack. A profile is data, but a generic alias is a measurement error in it.
- **Unscored is not zero, and not nothing.** With no key the deterministic reasons are
  still written, so the operator sees "+45 core skill overlap, +10 rate fit". What is
  withheld is the *total*: computed from five of the nine weights it would not be
  comparable to one from all nine, and the same offer would score differently depending on
  whether a key happened to be configured that morning.
- **A judge that answered nothing is the keyless case, not a low score.** `role_fit` is the one factor the prompt
  requires even at zero, so its absence is an unreachable endpoint, a reply that was not JSON, or a model that ignored
  the instruction — never an opinion.
  `Judge.answered` is the single reader of that, on all three paths (run, batch collector, and the rescore button, which
  says so out loud rather than showing a fresh number). Measured before it existed: **63 of 101 scored offers had no
  judged factor at all** and every one of them still carried a total. It is self-healing, because a null `score_model`
  makes the offer due again, and `ScoringReport.unusable` puts it in the run's own log.
- **A judged zero is kept; a zero penalty is dropped.** A weight is a share of what was attainable, so "this role does
  not fit" has to stay in the denominator or a bad match reads as a good one. A penalty is an absolute deduction, so a
  zero one is nothing at all.
- **The prompt carries the profile, and it is read rather than restated.** It used to say
  "a senior Java, Spring Boot and Angular developer who works from Germany" — three skills of the twenty-nine in
  `skill-profile.yaml`, hard-coded, while every other stage read the file. Role fit was judged against a description of
  somebody else, and editing the profile could not move it. `describe(offer)` likewise passes the rate, duration,
  workload and start: those come from enrichment and not from the advert's prose, and a judge calling an offer vague
  while the row beside it states all four knows less than the application does.
- **A stub that is not a whole chat completion proves nothing.** `JudgeIsBuiltPerRunTest`
  sent `choices` alone; the SDK refused it with "`id` is not set", the judge caught that and returned no reasons, and
  the test stayed green because a run counted an offer as judged whether or not an answer came back. What it actually
  proved was that a judge gets *built*
  after a reload.
- **The weight table decides, not the answer — and it is read, not restated.** A factor the
  model invents is dropped, and a model awarding itself 900 points for role fit gets exactly
  what `scoring.weights.role_fit` says. The four bounds used to be Java constants that
  matched the table by coincidence: raising a weight in the file moved the deterministic
  half of the score and left both the clamp and the prompt text where they were. The factor *names* stay in Java,
  because they are the judge's contract the way the eight field names
  are the extractor's; the numbers behind them come from the configuration, per run.
- **A judge that fails returns nothing rather than throwing.** One unreachable endpoint
  must not end the run; the offer keeps its deterministic reasons and scores lower, which
  is visible and reviewable.
- **`provider` is a kind, never a default.** It names a wire format and nothing else. Two
  are implemented — the OpenAI-compatible chat API (`openai-compatible`, `ollama`) and the
  Messages API (`anthropic`) — and the base URL still decides who answers. A provider the
  code does not know is refused loudly rather than approximated, because a request in the
  wrong shape does not fail cleanly: it comes back a 400, or gets parsed out of a field
  that is not there into an offer that looks judged and is not.
- **The wire format is Spring AI's problem now, and that is most of what it bought.** Five
  differences between the two APIs used to be spelled out by hand and each failed silently:
  the auth header, the version header, the system prompt as a field rather than a message, the
  mandatory `max_tokens`, and the answer in `content[]` rather than `choices[]`. Two of them
  had already cost a run — the current models reject a `temperature` outright, and reasoning
  counts against `max_tokens` before the text begins.
- **The answer is read out of every generation, not the first one.** Spring AI emits a model's
  thinking as a generation of its own, *ahead of* the text, so `call().content()` alone hands
  back the reasoning and drops the JSON. Four missing factors on an offer that looks judged,
  and the same trap the raw HTTP version documented, returned through the framework.
- **The *model* modules, never the `spring-ai-starter-model-*` ones.** The judge is built per
  run from the hot-reloadable snapshot, so there is nothing for auto-configuration to
  configure — and it is not merely useless: it builds every model the module knows at boot, so
  the OpenAI starter failed the whole context with "At least one credential source must be
  specified" while constructing an *audio speech* model this application will never call.
- **One offer, one transaction — and the stage is deliberately not one.** `ScoringService.run`
  carries no `@Transactional`; `ScoreWriter.write` does, because the three statements behind a single score have to
  commit together. Wrapping the loop instead holds a write lock on every offer already judged until the last is
  answered, which with a local model is hours, and the filter stage of any concurrent run writes a verdict on all rows
  with no `WHERE` — so it waits behind it. Measured: two filter updates blocked thirteen minutes behind a scoring
  transaction open for twenty. `ScoringTransactionBoundaryTest` pins both annotations, because a passing run reveals
  nothing about which one is missing.
- **A pass refuses to start while one is running.** `IngestService` holds a `tryLock` and answers `409`, never a queue:
  a second pass is the same work done twice, and a caller that waits is a request held open for hours. The CronJob's
  `concurrencyPolicy: Forbid` governs only the jobs the CronJob creates and says nothing about the button.
- **The batch path is still hand-rolled HTTP, deliberately.** Spring AI 2.0 has no batch
  abstraction; the SDK underneath has one only behind `client.beta()`, and taking it would
  rebuild the request as typed params and rewrite four JSONL tests to reach the same two
  endpoints that already work.
- **`base_url` is required even for a hosted provider whose address never changes.** A URL
  in the code is a vendor in the code, and no committed file in this repository names one.
  It lives in `.env` beside the key.
- **One judge, one question.** `ChatClientJudge` owns the question, the bounds, the
  description of an offer and the reading of the answer, for every provider. `AnthropicJudge`
  extends it and adds nothing but the batch half. The bounds especially: they are what stop a
  model outvoting the weight table, and a second copy would mean the same offer scoring
  differently depending on who was asked.
- **Ollama is the one provider that needs no key**, and requiring one made it unusable —
  there was nothing to write in `.env`, so the judge was silently never built. The rule is
  about the value, which is why the provider is listed separately from
  `openai-compatible` even though it gets the same judge.

## How long one request may take

`llm.timeout` in `pipeline.yaml`, read by `ChatModels` and `EmbeddingModels`, default
`PT120S`.

It used to be a constant of 30 s in both factories, which is a comfortable ceiling for a
hosted endpoint and the wrong one for a local model that has to be loaded before it can
answer. Measured on the deployed instance on 2026-09-17, against Ollama on the LAN with
`gpt-oss:20b`: content, fields and scoring each spent their whole stage waiting and then gave
up, and the run reported `1 without a usable answer` — a sentence about the model's reply for
a reply that never arrived. The endpoint was reachable and the model present the whole time;
the chart's own measurement puts that model at 34 s warm, so a cold start has no chance under
30 s.

**Absent means the default, and that is deliberate.** The key is bound as a `Duration` whose
accessor substitutes `DEFAULT_TIMEOUT` for a null, so a configuration file written before the
key existed keeps working — and because the two configuration layers override file by file,
that is not a rare case but the normal one for anybody who copied the file once.

**The timeout is part of the client's cache key.** Both factories cache one built client per
configuration, so without it a change to this value would be accepted, logged, and then have
no effect until the next restart — the failure mode that the base URL and the key already had
to be keyed on.

**Raising it does not raise what a run costs.** The budget counts requests, not seconds, and a
request that times out has already been paid for. What it buys is that the answer arrives
before the stage stops waiting for it.

## The day's allowance

`backend/…/llm/LlmBudget`, `V24`, and one call site in every stage that sends a request.

- **`max_calls_per_day` shipped in the configuration from the beginning and was read by
  nothing**, which is the same class of lie as an unimplemented auth mode. It is read now.
- **A request is a request.** A judge's prompt and an embedding carrying thirty-two adverts
  count the same, because the number in the file says "calls" and an exception to that would
  live only in the code rather than beside the number a person reads.
- **Collecting a submitted batch is the one thing that does not count.** Those answers are
  already bought; a spent budget that refused to fetch them would strand them in flight and
  the money with them.
- **A spent day stops each stage where it is, and nothing is written as answered.** The judge
  leaves the offer unscored and therefore due, the field extractor its columns, the ingest
  fallback the document. That is the same shape every one of them already had for a model
  that does not answer, which is why no stage needed a new state.
- **The count is a row per day in the database, not a field in memory.** A restart would
  otherwise hand out the allowance twice, and a restart is what happens on the night
  something else goes wrong. It also means the nightly pass and a run started by hand share
  one day.
- **Check and increment are one statement.** Between a `SELECT` and an `UPDATE` there is a
  window where two stages both read the last remaining call and both take it; the `WHERE` on
  the conflict closes it, and a refused call updates nothing and returns no row.
- **`cache_by_message_id` was deleted rather than implemented.** It sat in the same block and
  came from a concept in which extraction itself was a model call: cache the answer per mail,
  and the same mail is never paid for twice. Extraction is deterministic now, and no model is
  ever asked about a mail at all — it is asked about an offer, a block or a document. What the
  key promised is true five times over and by five different keys: the IMAP user flag, the
  `(portal, digest)` label cache, the fetch cache, the upload's reading cache, and scoring's
  own staleness predicate. A sixth cache keyed by something no stage holds would have been a
  mechanism looking for a caller.
- **`0` means no calls; no ceiling is the absent block.** The other reading is the expensive
  one — somebody writing `0` to mean "off" would get a bill. The record component is an
  `Integer` for the same reason: as a primitive, an absent key bound to zero and a file that
  merely names the block would have stopped every call in the pipeline.

- **`llm.models.scoring` is read by three stages, and `extraction` by a fourth.** The judge,
  the content classifier and the field extractor share `scoring`; the ingest fallback reads
  `extraction` and takes `scoring` when it is empty, and the deduplication pass reads
  `embedding` with no fallback at all. `writing` is the only one left that nothing reads: the
  cover letter is a Freemarker template. A key that looks configured and is not is the same
  class of lie as an unimplemented auth mode, so the shipped file says which is which — and
  the list of lies is down to one.
- **The judge is built per run**, not once at startup, because the configuration is
  hot-reloadable: a key added to `.env` should start producing scores without a restart.
- **A run judges what is stale, not everything that ever passed.** Every stage before this
  one already worked that way; scoring queried `status = 'PASSED'` alone, so each run paid
  a language-model call for the whole standing backlog and the bill grew with the accumulated
  list rather than with the day's inflow — silently, because a re-judged offer produces the
  same number as before. Three things make a score stale and they are the three it is only
  comparable within: never written, different `ruleset_version`, different `score_model`. The
  last is not caution about a worse model. Two judges are two scales, and the shortlist
  threshold is one number read against both.
- **Only `ScoringReport.scored` counts this run; the rest are standing totals.** The same
  reason `IngestReport.merged` is one. Once a run judges only what changed, a second run
  legitimately judges nothing, and per-run counts would report an empty shortlist rather
  than an idle pass.
- **The judge is a bounded classifier, so it does not need the largest model.** Four factors
  clamped to +15 / -30 / -25 / -10 by the weight table before anything is kept, and the
  answer is a few lines of JSON. `LLM_MODEL_SCORING` is a `.env` line, so which model
  answers is measured against `offer_score_reason` rather than argued about — and on models
  where thinking is on by default, the reasoning tokens are billed at the output rate for a
  classification that fits in three lines.
- **Batching is off by default, and it moves the end of the run.** `llm.batch` hands the
  scoring requests over as one batch at half the price; the answers arrive minutes later,
  so `ScoreBatchCollector` polls, writes them, and then runs packaging and the digest. The
  digest is still the last thing that happens, just not in the request that started it. Off
  by default because a run that answers within itself is the simpler thing to reason about
  and the saving is worth having only once the nightly pass is large.
- **`offer.score_batch_id` is what stops a batch being paid for twice.** The staleness guard
  asks what still needs judging, and an offer whose answer is bought and in flight does not.
  Without the pointer the next run resubmits it, and the symptom is a bill, not a bug. It is
  also why "what is in flight" survives a restart.
- **`llm.batch: true` on a provider with no batch endpoint is fatal at load.** Same class of
  lie as an unimplemented auth mode: read, ignored, and scoring synchronously at full price
  while the person who wrote it believes they are paying half. `PipelineConfig.Llm.BATCHING_PROVIDER`
  is the single name, so the loader and the judge factory cannot disagree.
- **A collected batch releases its offers whatever happened to it.** Ended, failed, or
  collected under a configuration that can no longer talk to it — all three clear the
  pointer. An offer held by a finished batch is held forever, and nothing says so.
- **An offer whose batch entry errored stays unscored rather than getting a partial total.**
  The same rule as the keyless path: five of nine weights do not make a number comparable to
  one from all nine. Written that way it is self-healing, because a null `score_model` makes
  the offer due again.
- **The bounds live in `ChatClientJudge.reasonsOf` and the clamp in `Score.of`, once each.** Two
  paths now produce one score, and a shortlist whose halves bound or clamp differently is
  not a ranking — the same offer would score differently depending on how busy the night was.
- **Which judge answers is a parameter of the run, not a setting.** `llm.models.scoring`
  is the default and `scoring_options` names the alternatives; the select beside the run
  button sends one of them with the request, and the server remembers nothing. A stored
  setting would mean a scheduled pass silently inheriting whatever the browser last showed,
  and the one thing worth comparing here is two models over the same corpus.
- **The configured list is an allowlist, and it is checked before the run starts.** The name
  arrives as a request parameter and the endpoint behind it is billed per token, so anything
  else is refused rather than forwarded — a model the provider happens to accept answers,
  scores, and writes itself into `score_model`, where it is indistinguishable from a
  deliberate choice. `Judges.check` runs as `IngestService.run`'s first statement because
  scoring is the last stage: checked only where it is used, an unknown name comes back 400
  having already read the sources, clustered the duplicates, applied the filter and fetched
  the surviving ads. Measured, before the check was moved.
- **Switching the model re-judges the standing shortlist, and that is the price of the
  comparison.** `score_model` is one of the three staleness criteria, so the choice is never
  free: one full pass at the chosen model's rate every time it changes. It is also why the
  choice cannot be a display preference — two judges are two scales, and the shortlist
  threshold is one number read against both.
- **The browser holds the choice in localStorage and drops one the server no longer offers.**
  The server refuses it anyway, but a name picked weeks ago and kept locally would otherwise
  turn the next click into a 400 for a reason nobody can see. Below 40rem the select is
  hidden rather than wrapped — measured: at 480 px the header wanted 555 — and hiding the
  control does not clear the setting.

- **The digest is a file, and the last thing a run does.** No transport, no recipient, no
  channel — and no schedule of its own either: whatever schedules the run schedules the
  digest, and a cron nothing reads would be one more key that lies. An unscored offer gets
  its own heading rather than being sorted to the bottom of a ranking that does not exist.

## The application package

`backend/…/packaging/`. One folder per offer somebody has decided to answer, built when
they decide it.

- **The gate is the person's decision and not the score band.** It was the band: the run
  built a folder for everything it liked, at the end of every pass. Measured on the deployed
  instance on 2026-09-17, that gave **93 applications at `PACKAGED` against 2 ever sent** —
  fifty prepared for every one that went out, each paying for its templates, its CV copy and
  its reference-ranking embeddings, and each leaving a directory nothing ever deleted (76 of
  them, 6.0 MB). Reaching the shortlist now opens an application at `NEW` and costs one row;
  moving it to `PACKAGED` is what asks for the folder, and `PackagingService.DUE` reads
  exactly that. Dropping `score_band` from the query is deliberate rather than incidental: an
  operator may decide to answer a `REVIEW`, and a gate that second-guessed them would leave a
  `PACKAGED` application with nothing behind it.
- **The build runs after the status write commits, and the run keeps a retry.** A folder
  written inside the transaction that asked for it survives a rollback the row does not, so
  `PackageWorker` listens `AFTER_COMMIT`; and because the work is disk and templates rather
  than something the operator is waiting on, it is `@Async`. That is only safe because
  nothing is lost when a listener never runs: the offer stays due, so the `PACKAGE` stage in
  the next pass builds it. On a healthy instance that stage now reports zero, which is the
  expected reading and not a fault. The browser meanwhile shows "building" and re-reads the
  board a few times, because the answer to the PATCH is a `PACKAGED` row with no folder yet
  and "no package" there would read as a build that failed.
- **Archiving an offer discards its package unless it was ever sent.** A folder costs disk
  for as long as it exists and is rebuildable from the same advert — nulling `packaged_at`
  is what re-arms that — so an offer leaving the working list takes it along. What is not
  rebuildable is the record of what actually went out, and **that question is the event log's
  and not the status's**: a `LOST` application may have been answered and lost, or written
  off before anybody wrote a line, and those two have opposite answers. The current status is
  asked as well, because deleting is the irreversible half and a row standing at `SENT` with
  a hole in its log must not lose the folder over it. Only the manual archive does this; the
  age pass reconciles and undoes itself, and a pass that reverses itself must not delete
  files on the way.
- **`OrphanSweep` collects what nothing points at**, at every start. Three things leave a
  folder behind and none can clean up after itself: a build that died before recording where
  it wrote, a discard that cleared the row and could not delete the directory, and `V27`,
  which cleared seventy-odd rows in one statement because a migration has no disk. It removes
  a **direct child of the output directory that carries a `meta.json`** and is named by no
  `package_dir`. That marker is the safety catch: the directory comes from configuration and
  this runs unattended, so a misconfigured path has to find nothing it recognises rather than
  a directory full of somebody's files.
- **This is where a send button would arrive**, one convenient afternoon: the folder is
  finished and the contact is right there in `meta.json`. `NothingIsSentTest` reads the
  repository for `Transport.send`, `JavaMailSender`, `MimeMessageHelper`, `setRecipient(`
  and `mailto:`, and for configuration keys naming a transport — in the backend and in the
  frontend both. ISC-52 is enforced, not remembered.
- **`new MimeMessage` is deliberately not on that list.** It is how an `.eml` file is
  parsed, and the file connector does exactly that. Neither is `channel:` a forbidden key:
  `sources.yaml` uses it for where an offer *came from*. A check that cannot tell inbound
  from outbound is a check that gets switched off.
- **Templates come from the two-layer lookup**, `templates/…` in the config directory
  first and on the classpath second, exactly like the four YAML files. `{lang}` in a
  template path is the language of the ad and nothing else.
- **Templates see camelCase.** The row from the database is snake_case, and
  `offer.full_text` resolves to nothing in Freemarker rather than failing — silently
  producing a letter with a hole in it. The model is converted once before rendering, and
  the Freemarker exception handler is set to rethrow for the same reason.
- **Language: German if the text is German, English if there is text and no German, the
  profile's `locale_primary` only when there is nothing to go on.** The order matters —
  falling back to `locale_primary` for an ad that simply has no German in it sends a
  German letter to an English posting. Measured: 0 of 1289 descriptions lack a German
  function word, so English really is the exception and not the default.
- **No CV is tailored.** The language picks a fixed PDF, and that is the whole rule. A
  missing file is recorded as `cv-MISSING.txt` rather than failing the package: without
  the CV it is still most of the work.
- **`meta.json` carries the decision, not just the offer** — the score, every reason
  behind it, the fields, the matched skills, the reference projects chosen, and every
  portal in the duplicate cluster, so one project advertised three times is one package
  that says so.
