# Configuration

Nothing individual is baked into the artifact, and nothing individual is committed. This
document says where every value comes from and what happens when it is missing.

## Two layers, and only two

Working defaults ship on the classpath under `backend/src/main/resources/leadgen/` and are
part of the jar. The directory named by `leadgen.config-dir` overrides them **file by
file** — put one file there and the other three still come from the jar.

That is the same shape Spring's own configuration has, and it buys two things: the tool
runs on a fresh clone with no configuration at all, and a value belonging to one person is
never baked into an image. The startup banner names, per setting, which layer decided it.

The classpath directory is `/leadgen/` and deliberately **not** `/config/`: Spring scans
`classpath:/config/` for its own configuration by default, so a file placed there would be
read twice — once by this loader and once by Spring, which would quietly bind whatever
happened to match.

```
backend/src/main/resources/leadgen/    the defaults, in the jar — every value a ${PLACEHOLDER}
  pipeline.yaml
  matching-rules.yaml
  sources.yaml
  skill-profile.yaml
  templates/

config/                                yours, gitignored, overriding the above file by file
.env                                    the values behind the placeholders, gitignored
```

## The four files

| File | Bound to | What it decides |
|---|---|---|
| `sources.yaml` | `SourcesConfig` | Where offers come from and how a document is read, down to the CSS selector and the date format. A new source is a block here — see [ADDING-A-SOURCE.md](ADDING-A-SOURCE.md). |
| `matching-rules.yaml` | `MatchingRules` | The six knockout stages, the scoring weights and penalties, the three thresholds, deduplication, freshness, follow-up. |
| `skill-profile.yaml` | `SkillProfile` | Who is applying: skills with weights and aliases, industries, reference projects, and which CV goes with which language. |
| `pipeline.yaml` | `PipelineConfig` | The process itself: provider and model, enrichment, packaging, digest, auth. |

**`pipeline.yaml` is not `application.yaml`.** The latter is Spring's and only Spring's: it
wires the *process* — datasource, ports, where the configuration directory is. The two used
to share a name, which meant a stack trace naming it could mean either file.

### The rules that make them predictable

- **The three files are one snapshot**, read together and swapped atomically, which is why
  `rules.hot_reload` is one switch for all of them. Reloading one without the others would
  hand the pipeline a picture that never existed on disk.
- **Binding is strict.** An unknown property fails the file. A misspelled
  `min_remote_percent` would otherwise disable a hard filter in silence, and the only
  visible effect is a longer shortlist — which looks exactly like a good day on the market.
- **Invalid at startup is fatal; invalid at reload is not.** Running with a filter nobody
  wrote is worse than not running. But a half-saved file must not take a running tool down,
  so the last good snapshot stays and the problem is logged.
- **A path in these files names a file, never a location.** Only the file name is used, and
  the two-layer lookup decides where it comes from. Anything more forgiving was measured
  and removed: resolving `config/local/matching-rules.yaml` upwards from the working
  directory made a run read a file from *outside* the directory it was pointed at, and look
  entirely normal doing it.
- **Change detection polls timestamps.** For four files the efficiency argument is worth
  nothing, and `WatchService` is native only on Linux — on macOS the JDK falls back to
  polling with a ten-second default latency anyway. A change is applied one cycle after it
  is first seen, so a save in progress finishes first.

## Placeholders and `.env`

Every value in the shipped files is a `${PLACEHOLDER}`. `${VAR}` with no value becomes the
empty string, and an empty YAML scalar is **null**, not `""` — every consumer treats both
alike. Whether empty is acceptable is a question about the field, so validation answers it:
an unset LLM key is fine, an unset IMAP host on an *enabled* source is not.

`.env` is read by the application, not by the build. It is searched upwards from the working
directory, and real environment variables win — so `bootRun` (which runs in `backend/`), an
IDE run configuration (repository root) and a jar all behave identically. It also reaches
Spring, registered directly below `systemEnvironment`, which is why `LEADGEN_CONFIG_DIR`,
`POSTGRES_PASSWORD` and `SERVER_PORT` can be written there and have an effect.

**The file has to be called `.env`.** Compose substitutes the `${...}` in
`docker-compose.yml` from `.env` and from nothing else — not from `env_file:`, which only
injects into a container, and not from `COMPOSE_ENV_FILES` set inside a file. Another name
needs a flag on every call, and forgetting it silently applies the compose defaults, so the
stack listens where the application is not looking.

## Every variable

Start from [`.env.example`](../.env.example), which carries the same list with its
rationale. `*` marks a credential.

### Mail access

| Key | Default | Note |
|---|---|---|
| `IMAP_HOST` | — | Required on an *enabled* imap source, ignored otherwise. |
| `IMAP_PORT` | `993` | |
| `IMAP_USER` | — | |
| `IMAP_PASSWORD` * | — | |
| `NEWSLETTER_FOLDER`, `NEWSLETTER_FROM`, `NEWSLETTER_SUBJECT`, `NEWSLETTER_BLOCK_SELECTOR` | — | Consumed by a `sources.yaml` you write in `config/`, not by the shipped one. |

### Language model

| Key | Default | Note |
|---|---|---|
| `LLM_PROVIDER` | — | `anthropic`, `ollama` or `openai-compatible`. A kind, never a vendor: it names a wire format and the base URL decides who answers. Anything else is refused loudly. |
| `LLM_BASE_URL` | — | Required even for a hosted provider whose address never changes. A URL in the code is a vendor in the code. |
| `LLM_API_KEY` * | — | Optional in full. Without it the tool runs and loses the score total and the cover letter; the deterministic reasons are still written. `ollama` needs none. |
| `LLM_BATCH` | `false` | Half the price, answers minutes later. Only the Messages API batch is implemented; `true` on any other provider is fatal at load rather than quietly synchronous at full price. |
| `LLM_MODEL_SCORING` | — | The judge, and the default of the list below. |
| `LLM_MODEL_SCORING_OPTIONS` | — | Comma separated. An **allowlist**, checked before the run starts: the chosen model travels as a request parameter to an endpoint billed per token. |
| `LLM_MODEL_EXTRACTION`, `LLM_MODEL_WRITING`, `LLM_MODEL_EMBEDDING` | — | **Not read.** The stages exist in the concept and not in the code; a value in any of them changes nothing. |

### Database

| Key | Default | Note |
|---|---|---|
| `POSTGRES_HOST` | `localhost` | |
| `POSTGRES_PORT` | `55432` | Not 5432, on purpose — see [DEVELOPMENT.md](DEVELOPMENT.md). Inside the container the port is always 5432. |
| `POSTGRES_DB` | `leadgen` | |
| `POSTGRES_USER` | `leadgen` | |
| `POSTGRES_PASSWORD` * | `leadgen` under Compose | The compose default exists so the stack starts; set it. |

### Paths

| Key | Default | Note |
|---|---|---|
| `LEADGEN_CONFIG_DIR` | `./config` | Relative is searched upwards from the working directory. `/config` in the container. |
| `PACKAGES_DIR` | `./packages` | Where an application folder is written. |
| `DIGEST_DIR` | `./packages/digest` | Its **own** setting; it does not follow `PACKAGES_DIR`. Left unset in a container it resolves against the working directory, which the non-root user cannot write — the run then completes every stage and dies on the last. |
| `INBOX_DIR` | `./data/inbox` | Where the `local-eml` replay source reads from. |
| `MANUAL_INBOX_DIR` | `inbox` **inside the config directory** | Where an upload lands. Under Compose it must be named, or it resolves inside the read-only `/config` mount and every upload fails. |
| `PROFILE_PATH`, `RULES_PATH` | `skill-profile.yaml`, `matching-rules.yaml` | File names, resolved against the config directory. |

### Process

| Key | Default | Note |
|---|---|---|
| `SERVER_PORT` | `8080` | |
| `SERVER_ADDRESS` | `127.0.0.1` | The only thing in front of the write endpoints while `AUTH_MODE` is `none`. Compose sets `0.0.0.0`. |
| `AUTH_MODE` | `none` | The only implemented value; any other is fatal at load. |
| `LOG_LEVEL` | `INFO` | |
| `CONFIG_POLL_INTERVAL` | `PT2S` | Two polls are needed to apply a change, so worst case is twice this. |
| `SCORE_BATCH_POLL_INTERVAL` | `PT5M` | Only read when `LLM_BATCH` is true. |
| `DIGEST_FORMAT` | `html` | `text` or `html`. |
| `OIDC_ISSUER`, `OIDC_CLIENT_ID` | — | Read only when `AUTH_MODE` is `oidc`, which is not implemented. |
| `SAMPLE_FEED_URL` | — | The feed of `sample-portal-feed`, which ships disabled. |

### Compose and the dev server only

| Key | Default | Note |
|---|---|---|
| `WEB_PORT` | `4200` | The host port the web container publishes. |
| `API_PROXY_TARGET` | `http://localhost:8080` | Where `bun run start` proxies `/api`. The dev server prints the effective value on startup. |

These two belong to the tooling, not the application, which is why the startup banner leaves
them out — showing them would invite you to change one and wait for an effect that cannot
come.

## Reading a running instance

The backend prints one box on `ApplicationReadyEvent`: every app-relevant setting, its
effective value, and which layer decided it (`env` before `.env` before `yaml`). It is
cumulative rather than per file, because nobody debugging a run thinks in files — they think
"which database, which mailbox, which model".

Secrets are masked by **key name**, because a password is not recognisable by looking at it;
the only safe direction to be wrong in is masking something harmless. The mask is a fixed
width — stars matching the length would publish the length — and masked, empty and unset are
three different renderings, because whether a secret is configured at all is the one thing
about it worth logging. Credentials inside a URL are masked too.

Note that `docker compose config` resolves and prints every value in clear, including keys.
That is Compose, not this tool.
