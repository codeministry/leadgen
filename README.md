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
- **No automatic sending.** The tool proposes and prepares; a human sends.
- **Personal data stays out.** Profile and credentials live in `config/local/` and in
  environment variables, never in the repository.

## Stack

Spring Boot (Java 21) · Angular + NGRX + DaisyUI · PostgreSQL · Docker Compose, Helm

## Layout

```
backend/    Spring Boot, Gradle module :backend
frontend/   Angular, Gradle module :frontend (bun does the work)
charts/     Helm chart
config/
  examples/ example configuration
  local/    your own configuration (gitignored)
docs/       concept and decisions
```

## Getting started

```bash
mkdir -p config/local
for f in application matching-rules skill-profile sources; do
  cp "config/examples/$f.example.yaml" "config/local/$f.yaml"
done
cp .env.example .env                                 # fill in credentials
docker compose up --build
```

The web UI is then on `http://localhost:4200`, the API on `http://localhost:8080`.

## Development

```bash
./gradlew check                # both modules: backend tests, frontend lint + tests
./gradlew :backend:test        # Spring tests (needs Docker for Testcontainers)
./gradlew :backend:bootRun     # API on :8080, reads the untracked .env
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
