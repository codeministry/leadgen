# Contributing

Thanks for looking. This is a small, opinionated project in alpha, built by one person with
the help of an AI coding agent. That shapes what makes a contribution easy to accept, so
the rules below are worth two minutes before you write code.

By participating you agree to the [Code of Conduct](CODE_OF_CONDUCT.md).

## Two invariants a change must not break

Both are enforced by tests, not by review, and both exist because the failure they prevent
is silent.

1. **Nothing is ever sent.** Both outputs are files: the digest as text or HTML, the
   application package as a folder. There is no transport, no recipient and no channel in
   the code *or in the configuration schema* — modelling one would be an invitation to add
   the code. `NothingIsSentTest` reads the repository for `Transport.send`,
   `JavaMailSender`, `MimeMessageHelper`, `setRecipient(` and `mailto:`, in both modules.
2. **Nothing is wired in.** No newsletter name, no portal, no mail provider, no model name
   and no personal datum belongs in a committed file. The configuration that ships names
   every value as a `${PLACEHOLDER}`; the values live in `.env` and anything individual
   beyond them in `config/`, both gitignored. A new offer source is a YAML block, not a
   release.

## Before you open a pull request

```bash
./gradlew check
```

That is the whole gate: Spotless and the Spring tests for the backend, ESLint, Stylelint,
`tsc` and Vitest for the frontend. It needs a running Docker — the backend tests use
Testcontainers. If Spotless reformats something, commit the reformat.

## House rules

**Everything in this repository is English.** Code, identifiers, comments, config comments,
documentation, commit messages, issues, UI strings, log output, test names. The one
exception is *content*: the offers the tool reads are German, the cover letters it writes
are German, and those live in `config/` and in the i18n catalogs, selected by the language
of the ad and never hardcoded.

**bun, never npm or npx.** `bun.lock` is the lockfile and `package.json` is the single list
of frontend commands; Gradle calls `bun run <script>` rather than reimplementing them.

**Backend:** JDBC and Flyway, not JPA. Records for data, each API type in its own file.
Lombok only where the code is pure boilerplate. `@Valid` goes on the type argument
(`List<@Valid Skill>`), never on the container.

**Frontend:** strict layering `shared` → `core` → `layout` → `features`, crossed only
through the tsconfig aliases (`@core/*`, `@shared/*`, …) because that is what the ESLint
rule matches on. Standalone components, signals, `OnPush`, zoneless. No `@Input/@Output`,
no `*ngIf`, no `| async`, no barrels. `.css`, never `.scss`. Specs live beside their file.
No prose in TypeScript — a component returns a catalog key and its parameters.

**Comments say why, not what.** This repository's comments carry the reasoning and, where
there is one, the measurement behind a decision. A comment restating a method signature is
noise; a comment naming the failure a line prevents is the reason the line survives the
next refactor.

## What is especially welcome

- **A new offer source.** It should be a block in `sources.yaml` and nothing else. If it
  needs a code change, that is a bug in the abstraction and worth reporting as one —
  `docs/CONCEPT.md` §3 describes the seam.
- **A measurement that contradicts one in the code.** Numbers in comments and in the README
  come from `docs/samples/`; if yours disagree, that is the most useful issue you can file.
- **Anything under [What does not work yet](README.md#what-does-not-work-yet).**

## Reporting a bug

Use the issue templates. For anything that looks like a security problem, do **not** open
an issue — see [SECURITY.md](SECURITY.md).

## Commit messages

A short imperative subject that says what changed, and a body that says why if the why is
not obvious. No prefix convention is enforced.
