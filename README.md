# lead-generation

An acquisition tool for freelancers: collects project offers from arbitrary sources,
filters them against your own profile and assembles a ready-to-send application package
for the matches. Sending stays with the human.

## Why

Project offers arrive as newsletters, portal feeds and direct enquiries, and the same
project often comes through several agencies at once. Reviewing them costs time every
day; assembling the documents costs it again for every application. Both are
automatable. The decision is not.

## Principles

- **Nothing wired in.** Sources, extraction rules, profile, filters, weights, templates
  and models are configuration. A new offer source is a YAML block.
- **Rules before model.** Hard criteria run deterministically and for free; the language
  model only scores what survives. Without an LLM the tool still works.
- **No automatic sending.** Both outputs are files — the daily digest as text or HTML,
  the application package as a folder. A human reads them and sends.
- **Personal data stays out.** The configuration that ships names every value as a
  `${PLACEHOLDER}`. The values live in `.env.local`, anything individual beyond them in
  `config/local/`, and neither is committed.

## Stack

Spring Boot (Java 21) · Angular + NGRX + DaisyUI · PostgreSQL · Docker Compose, Helm

## Layout

```
backend/    Spring Boot, Gradle module :backend
  src/main/resources/leadgen/   the configuration that ships: pipeline, rules,
                                sources, profile — neutral, values as ${PLACEHOLDERS}
frontend/   Angular, Gradle module :frontend (bun does the work)
charts/     Helm chart
config/local/                   overrides the four files above, one by one (gitignored)
docs/       concept and decisions
```

Configuration comes in two layers, the same way Spring's own does: working defaults are
part of the jar, and an external directory overrides them file by file. The tool runs on a
fresh clone with nothing configured; the startup log says which layer each file came from.

## Getting started

```bash
cp .env.local.example .env.local                     # fill in credentials
docker compose up --build
```

That is the whole setup. To change a rule, copy the file you want to change out of
`backend/src/main/resources/leadgen/` into `config/local/` and edit it there — only the
files you put there override, and none of them are committed.

The web UI is then on `http://localhost:4200`, the API on `http://localhost:8080`.

## Development

```bash
./gradlew check                # both modules: backend tests, frontend lint + tests
./gradlew :backend:test        # Spring tests (needs Docker for Testcontainers)
./gradlew :backend:bootRun     # API on :8080, reads the untracked .env.local
docker compose up postgres     # the database the local run expects

cd frontend
bun run start                  # dev server on :4200, proxies /api (API_PROXY_TARGET)
bun run check:static           # ESLint, Stylelint, tsc — after every change
bun run test                   # Vitest
```

The dev server prints its effective proxy target (`[proxy] /api → …`) on startup — the
first place to look for unexplained 401s or empty lists.

## Documentation

- [Concept](docs/CONCEPT.md) — pipeline, domain model, module layout, order of work
- [Sample analysis](docs/SAMPLE-ANALYSIS.md) — what 14 real newsletter mails contain,
  and what they do not
