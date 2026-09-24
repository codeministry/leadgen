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
  where it does work (`ConfigRegistry` loads) and not where the parameters would carry
  annotations — Lombok would generate a constructor without them, which is why no `@Value`
  is left under `src/main`: the `leadgen.*` keys bind on `ConfigProperties` instead.
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

- **Read a row with a `RowMapper` and `rs.getString(...)`, never by casting a `listOfRows()` value.** — reasoning in `docs/decisions/pipeline-scoring.md`.
- **A raw control byte in a source file makes `rg` skip it as binary, silently.** `'\0'` is written as the escape, never as the byte; a repo-wide count runs `rg -a`. Measured: one such byte hid a whole class from the Lombok sweep and its probe.

- **Cut a YAML block out of the file's bytes before masking it; a masked document parses to nothing.** — reasoning in `docs/decisions/read-side.md`.
- **Bound a YAML block by the next item's start mark, never by `getEndMark()`.** — reasoning in `docs/decisions/read-side.md`.
- Strip `(m/w/d)`, `(w/m/d)`, `(m/f/d)` before normalizing. Every title comparison goes
  through `TitleNormalizer`, so two of them cannot disagree.
- The location sits behind a `📍` prefix in one of four `span`s in `div.job-meta` —
  address it by the prefix, never by position.
- **A test context never resolves a `${PLACEHOLDER}` from the machine; a new one goes into `ConfigFixtures.NEUTRAL_PLACEHOLDERS`.** — reasoning in `docs/decisions/configuration.md`.
- **IMAP sources share a folder only through `selector.from` in the `SearchTerm`; `subject_matches` or `match_all: true` means separate folders.** — reasoning in `docs/decisions/pipeline-ingest.md`.
- **`match_all: true` short-circuits `subject_matches` and not `from`,** because `fromAnyOf` builds the IMAP `SEARCH`
  term without looking at the flag, and with neither filter set it changes nothing at all.
  `ConfigLoader.checkSelectors` refuses the three arrangements where that bites; reasoning in
  `docs/decisions/pipeline-ingest.md`.
- **`TitleNormalizer` removes `<mark>` as a tag, before the gender suffixes, never as angle brackets; every title comparison goes through it.** — reasoning in `docs/decisions/pipeline-dedupe-filter.md`.
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
