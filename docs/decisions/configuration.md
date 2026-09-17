# The configuration layer and the startup banner

The two layers, the three files read as one snapshot, and the box that says which layer won.

These are working notes moved out of `CLAUDE.md` so the always-loaded file stays small.
Every paragraph here was paid for once; none of it is a summary.

## The configuration layer

`backend/…/config/`. Everything else reads `ConfigRegistry.snapshot()` and nothing
reads a YAML file itself.

- **The three files are one snapshot**, read together and swapped atomically. Reloading
  one without the others would hand the pipeline a picture that never existed on disk,
  which is why `rules.hot_reload` is one switch for all three.
- **Binding is strict** (`FAIL_ON_UNKNOWN_PROPERTIES`). A misspelled `min_remote_percent`
  would otherwise disable a hard filter in silence, and the only visible effect is a
  longer shortlist — which looks exactly like a good day on the market.
- **The failure policies differ by design.** Invalid at startup is fatal: running with a
  filter nobody wrote is worse than not running. Invalid at reload is not: the last good
  snapshot stays and the problem is logged, because a half-saved file must not take the
  running tool down.
- **`min_hourly_eur` before enrichment is rejected at load time** — the invariant is enforced, not just written down. So
  is `review` above `auto_shortlist`: `Score.band`
  tests the shortlist bound first, so the inverted pair does not fail, it silently deletes the REVIEW band and builds an
  application package for every offer above the lower of the two. Found live, with `auto_shortlist: 30` sitting under
  `review: 50`.
- **Placeholder resolution is deliberately dumb.** `${VAR}` without a value becomes the
  empty string, and an empty YAML scalar is **null**, not `""` — every consumer treats
  both alike. Whether empty is acceptable is a question about the field, so validation
  answers it: an unset LLM key is fine, an unset IMAP host on an *enabled* source is not.
- **Paths in `application.yaml` are file names**, resolved against the config directory.
  A path with the directory baked in breaks the moment it moves — in the container it is
  `/config`, not `./config/local`. Such a path is still accepted, resolved from the working
  directory upwards, because breaking an existing configuration over a style is not worth it, **and the fallback logs a
  warning naming both paths**: it can resolve to a file outside the
  directory the process was pointed at, which turns two configurations into one and looks
  entirely normal doing it.
- **The working directory is not one thing.** Gradle's `bootRun` runs in `backend/`, an IDE
  run configuration in the repository root, a jar wherever it sits. A relative
  `leadgen.config-dir` is therefore searched upwards from the working directory; a default
  that is correct in one of them is wrong in the others, and the symptom is a missing file
  at a path nobody recognises.
- **Change detection polls timestamps, it does not use `WatchService`.** For three files
  the efficiency argument is worth nothing, and the watch service is native only on
  Linux; on macOS the JDK falls back to polling with a ten-second default latency. A
  change is applied one cycle after it is first seen, so a save in progress finishes
  first.
- **`application.yaml` is Spring's and only Spring's.** The tool's own configuration is
  `pipeline.yaml`, and the class behind it is `PipelineConfig`. They used to share a name,
  which meant a stack trace naming it could mean either file.
- **The classpath directory is `/leadgen/` and deliberately not `/config/`.** Spring scans
  `classpath:/config/` for its own configuration by default, so a file placed there would
  be read twice — once by this loader and once by Spring, which would quietly bind whatever
  happened to match.
- **A path in `pipeline.yaml` names a file, never a location.** Only the file name is used,
  and the two-layer lookup decides where it comes from. Anything more forgiving was
  measured and removed: resolving `config/local/matching-rules.yaml` from the working
  directory upwards made a run read a file from outside the directory it was pointed at,
  and look entirely normal doing it.

## The startup banner

`ConfigurationBanner`, beside `DatasourceBanner`. One box, one log entry, on
`ApplicationReadyEvent`.

- **Cumulative, not per file.** `application.yaml` and `.env` are two files with two readers,
  but nobody debugging a run thinks in files — they think "which database, which mailbox,
  which model". Both are merged into one view, grouped by subject, and every row says where
  its value came from instead of which list it was in.
- **Effective, not declared.** A `${POSTGRES_PORT:55432}` shows the port in use, and a `.env`
  key a real environment variable overrides shows the value that wins. Otherwise the banner
  disagrees with the resolver exactly where it matters.
- **App-relevant is measured, not listed.** A `.env` key is shown when a `${...}` in
  `application.yaml` or in one of the four `leadgen/*.yaml` files names it — read from the raw
  text, in both layers. `WEB_PORT` and `API_PROXY_TARGET` belong to the dev server and to
  Compose, and showing them invites the reader to change one and wait for an effect that
  cannot come. The count of what was left out is printed, so "left out" never means "lost".
- **A variable both files name is one row, not two.** It appears under the property that
  consumes it, carrying the value that won.
- **Every row names the layer that decided it**, `env` before `.env` before `yaml`, which is
  the precedence `DotEnvEnvironmentPostProcessor` registers.
- **`Secrets` decides by key name, because a password is not recognisable by looking at it.**
  The only safe direction to be wrong in is masking something harmless. The mask is a fixed
  width — stars matching the length would publish the length — and masked, empty and unset are
  three different renderings: whether a secret is configured at all is the one thing about it
  worth logging. Credentials inside a value are masked too: `scheme://user:password@host`.
- **The icons are emoji from the block with no text-presentation past, and no `U+FE0F`
  anywhere.** A legacy symbol like `⚠` is one column in some terminals and two in others, and
  either way the border is torn off exactly the rows that carry an icon. Padding is computed in
  display columns, not in `String.length`.
- **A banner must not be able to end a startup.** An unresolvable placeholder is printed as
  such, an unreadable file contributes nothing, and neither throws at the last moment before
  the process is ready.
- **A `@DynamicPropertySource` supplier is called once per resolution, not once per context,
  so it must not create anything.** Reading `leadgen.config-dir` for the banner made
  `PackagingServiceTest` build a second temp configuration and reassign the static it asserts
  against — it then deleted a CV the application was never going to open. A supplier that makes
  a temp directory hands out a different one on each resolution: the loader keeps the first,
  the test rewrites the last, and every edit is read from a file nobody loads. The
  configuration reloads cleanly, logs "Configuration reloaded", and never changes — measured on
  `LlmBudgetTest`, where four assertions failed against a file that plainly held the new value.
  Create the directory in a static field and let the supplier return it; anything else with a
  side effect has to be memoized.
