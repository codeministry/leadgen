# CLAUDE.md — backend

Spring Boot 4.1, Java 25, Gradle. The repo-wide rules and the invariants are in the root
`CLAUDE.md`; this file holds what applies only here, and Claude Code loads it when it reads a
file in this tree.

The reasoning stage by stage lives in `docs/decisions/pipeline-ingest.md`,
`docs/decisions/pipeline-dedupe-filter.md`, `docs/decisions/pipeline-enrich-content.md`,
`docs/decisions/pipeline-scoring.md`, `docs/decisions/read-side.md`,
`docs/decisions/configuration.md` and `docs/decisions/manual-status.md`.

## Backend conventions

- **Lombok for the boilerplate, records for the data.** `@Slf4j` instead of a hand-written
  logger, `@RequiredArgsConstructor` where the constructor is nothing but assignments. Not
  where it does work (`ConfigRegistry` loads, `IngestService` builds a map) and not where
  the parameters carry annotations (`@Value` in `StatusController`) — Lombok would generate
  a constructor without them.
- **API types are records, each in its own file.** `AppStatus`, `IngestReport`,
  `SourceIngestResult`, `DocumentIngestResult`. No response type nested inside its
  controller or service. The configuration model is the exception: those records mirror the
  nesting of a YAML file, and flattening them would lose exactly the structure they exist to
  describe.
- **`@Valid` goes on the type argument, never on the container.** `List<@Valid Skill>`
  validates the elements; `@Valid List<Skill>` is deprecated in Hibernate Validator 9 and
  logs a `HV000271` per component at every start. The configuration model is almost
  entirely lists of validated records, so getting it wrong once fills the startup log.

- **JDBC, not JPA.** The pipeline writes offers in batches and upserts them with
  `ON CONFLICT`, which is one statement of plain SQL against a schema Flyway owns. An ORM
  would add a mapping layer over Postgres arrays for no gain. Flyway is therefore the only
  thing that touches the schema at all.
- **Boot 4 split the integrations into their own modules.** Without
  `spring-boot-flyway` the migrations sit on the classpath and never run, and the only
  symptom is Hibernate complaining about missing tables. `@WebMvcTest` likewise moved
  from `…test.autoconfigure.web.servlet` into `spring-boot-webmvc-test`.
- **`.env` is the one name, it is read by the application and not by the build, and Spring
  sees it too.** Compose substitutes `${...}` from `.env` and from nothing else — not
  `env_file:`, not `COMPOSE_ENV_FILES` inside a file. `DotEnvEnvironmentPostProcessor`
  registers it below `systemEnvironment`, so a real exported variable still wins and
  `LEADGEN_CONFIG_DIR`, `POSTGRES_PASSWORD` and `SERVER_PORT` mean the same thing however
  the process was started. What each half cost before it worked this way is in
  `docs/decisions/configuration.md`.
- **A published port's container side is fixed at 5432.** Postgres binds that port inside
  the container whatever the host side is; making both sides variable publishes a host port
  forwarding to a port nobody listens on, which looks exactly like no port at all.
- **The database host port defaults to 55432, not 5432.** A developer machine usually
  already has a Postgres on 5432, and connecting to the wrong one fails as
  `password authentication failed for user "leadgen"` — a message naming the user and
  neither the host nor the database it actually reached. `DatasourceBanner` prints the
  effective JDBC URL at startup for the same reason the frontend prints its proxy target.
- **The aotTest chain is off, and all three tasks of it.** Applying the GraalVM plugin puts
  AOT processing of the *test* source set into `check`, which starts every `@SpringBootTest`
  context at build time — measured, `check` went from 2.5 minutes to still running after
  fifty, naming no task. Disabling only `processTestAot` then fails `compileAotTestJava`
  against output that was never produced.
- **`processAot` runs the application context at build time, so a condition decided by
  `.env` is frozen into the jar.** Build artifacts in Docker or CI, never from a tree with a
  filled-in `.env`.
- **The AOT cache applies to the *extracted* jar and silently does nothing against the fat
  one.** Train and run on the same base image; `-Xlog:aot` is the only thing that will say it
  was rejected.
- **`leadgen.packages-dir` and `leadgen.inbox-dir` are gone, and were read by nothing.** The
  packages directory is `packaging.output_dir` in `pipeline.yaml`, the inbox is a source's
  `path` in `sources.yaml`, and both are read by the tool itself. Their only effect was to make
  `PACKAGES_DIR` and `INBOX_DIR` look as though they meant something on the Spring side as
  well, which is how a value ends up written in the one place that is not read.

## The call budget

**Every stage that sends a request to a model asks `LlmBudget.take()` first**, and a false
answer means "not now": the stage leaves its work undone and due rather than writing a result
it never received. A request is a request, so an embedding of thirty-two adverts counts the
same as one judge's prompt. Collecting a finished batch is the one exception and is not
counted — those answers are already bought, and refusing to collect them would strand them.
The reasoning is in `docs/decisions/pipeline-scoring.md`.

## Traps that have already cost money

- **`listOfRows()` hands the driver's own types straight on, and a cast is how that becomes a
  500.** A `jsonb` column arrives as a `PGobject` and a `TEXT[]` as a `PgArray`, so
  `(String) row.get("content_blocks")` threw a `ClassCastException` for every advert that had
  been segmented. `PackagingService`'s per-offer catch turned that into a counter,
  `package_dir` was never written, and the screen said every offer above the threshold had no
  package — for nine days, with a green suite, because every fixture set `full_text` alone.
  The `tags` array beside it never threw at all; it simply reached Freemarker as a wrapper
  around a JDBC array. **Read a row with a `RowMapper` and `rs.getString(...)`**, which is
  where the driver renders jsonb as text and where the other three readers of that column
  already are.

- **Masking a YAML document before parsing it breaks the parse, and silently.** The mask is
  `********`; a plain scalar beginning with `*` is a YAML *alias*, so a masked file stops
  composing and every block lookup after it comes back empty — no exception, just nothing
  found. `SourceDetailService` therefore cuts the block out of the file's own bytes first and
  masks what it is about to show. Found only by the fixture that writes a literal password into
  a `sources.yaml`; the shipped file is all `${PLACEHOLDER}`s and can never reproduce it.
- **SnakeYAML's `getEndMark()` is not the node's last line.** It points at the first token of
  whatever follows, which in a sequence of blocks is the next item — measured on the shipped
  `sources.yaml`, the newsletter block reported its end on the line reading
  `- id: sample-portal-feed`, swallowing the comment that belongs to that one. Checking for
  column zero does not save it, because that token begins at the dash's column. Bound a block
  by the *next* item's start mark instead.
- Search terms are wrapped in `<mark>` inside the title on some sources. Strip before any
  title comparison, or deduplication trips over `<mark>DevOps</mark>`. Not present in the
  current sample corpus; jsoup's `text()` handles it either way.
- Strip `(m/w/d)`, `(w/m/d)`, `(m/f/d)` before normalizing. Every title comparison goes
  through `TitleNormalizer`, so two of them cannot disagree.
- The location sits behind a `📍` prefix in one of four `span`s in `div.job-meta` —
  address it by the prefix, never by position.
- **A test that proves the keyless path must not read the developer's `.env`.** Placeholder
  resolution reads the process environment and then `.env`, whichever test is running, so
  `ScoringWithoutAModelTest` started scoring against a real endpoint the moment a key was
  filled in — and the test that exists to prove the tool works *without* a model failed for
  the one person who had finished configuring it. It empties the `${LLM_*}` placeholders in
  the materialised copy: what is under test is the code path, not whose machine it runs on.
- **Several IMAP sources may share a folder only because `selector.from` is in the `SearchTerm`.** The progress flag is
  one `progress_flag` per connection and the receiver writes it to whatever its *search* returned, before `matches()` sees sender or
  subject — so without that term the first source flags the others' mail and they read zero documents in silence.
  `subject_matches` cannot join it (Java regex vs. IMAP SEARCH), and `match_all: true` switches the check off: both mean
  separate folders. Reasoning in `docs/decisions/pipeline-ingest.md`.
- **`match_all: true` short-circuits `subject_matches` and not `from`,** because `fromAnyOf` builds the IMAP `SEARCH`
  term without looking at the flag, and with neither filter set it changes nothing at all.
  `ConfigLoader.checkSelectors` refuses the three arrangements where that bites; reasoning in
  `docs/decisions/pipeline-ingest.md`.
- **`<mark>` reaches the title as text, and stripping the angle brackets is not stripping the tag.** `[^a-z0-9]+` turns
  `<` and `>` into spaces and leaves the word `mark` standing twice, so `<mark>DevOps</mark> Engineer` fingerprints as
  `mark devops mark engineer` and never meets its twin. Measured: 402 of 13240 titles carry it. `TitleNormalizer`
  removes it before the gender suffixes, because a term matching "w" arrives as `(m/<mark>w</mark>/d)` and the suffix
  pattern does not recognise its own shape until then. Numbers in `docs/SAMPLE-ANALYSIS.md` § 4.
- **A comma in a jsoup selector is a union, and `selectFirst` answers in document order.**
  Adding a narrower class to `article, main, .job-description, #content` therefore changes nothing whenever a `<main>`
  wraps the page — which is every page that has one. The selector list reads like a priority order and is not one.
- **A `@DynamicPropertySource` supplier may run more than once, so it must not create anything.** Create the temp
  directory in a static field and let the supplier return it. Reasoning in `docs/decisions/configuration.md`.
- **A Spring AI options object built without a timeout pins every request at 60 s, whatever `llm.timeout` says.**
  `AbstractOpenAiOptions` fills a null one with its own default, so a per-call timeout is always sent and beats the
  client's. Set it on the options *and* the client, in `ChatModels` and `EmbeddingModels`. Measured: the same 60 s
  failure at PT120S, PT600S and PT20S. See `docs/decisions/retrieval.md`.
- **A pgvector distance against a NULL vector is NULL, so `ORDER BY … LIMIT k` returns k arbitrary rows.** Check
  that an anchor row actually has a vector before ordering by its distance. Reasoning in
  `docs/decisions/retrieval.md`.
- **An UPDATE cannot reference its own target table from a LATERAL item in its FROM clause.** Compute the pairs in a
  `WITH` and update `FROM` that. Reasoning in `docs/decisions/pipeline-dedupe-filter.md`.
- **Two constructors on a `@Component` are none, and the error names the wrong thing.** Spring picks neither and
  reports `No default constructor found` — a message about a constructor that was never meant to exist, rather than
  about the ambiguity. Every context in the suite fails at once: measured at 194 failures from one added convenience
  constructor taking a `Clock`. Keep one constructor, or mark one `@Autowired`.
- **Two methods called `kindOf(String)` that differ only in return type do not overload.**
  Same erasure, so the compiler refuses the second — and because annotation processing then does not run, the error it
  prints is 70 lines of "cannot find symbol: log" in files nobody touched. Read the *last* error, not the first.
