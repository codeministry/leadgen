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
backend/    Spring Boot
frontend/   Angular
charts/     Helm chart
config/
  examples/ example configuration
  local/    your own configuration (gitignored)
docs/       concept and decisions
```

## Getting started

```bash
cp config/examples/application.example.yaml config/local/application.yaml
cp config/examples/matching-rules.example.yaml config/local/matching-rules.yaml
cp config/examples/skill-profile.example.yaml config/local/skill-profile.yaml
cp config/examples/sources.example.yaml config/local/sources.yaml
cp .env.example .env                                 # fill in credentials
docker compose up
```

## Documentation

- [Concept](docs/CONCEPT.md) — pipeline, domain model, module layout, order of work
- [Sample analysis](docs/SAMPLE-ANALYSIS.md) — what 14 real newsletter mails contain,
  and what they do not
