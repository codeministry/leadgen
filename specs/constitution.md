---
repo: lead-generation
derived: 2026-09-20
standards_version: 1.0.0
design_track: app
stack: [spring-boot, angular]
---

# Constitution — lead-generation

> Derived on 2026-09-20 from the sources listed below. The rules live in those sources;
> this file says which of them bind spec work, where to read them, and what proves them.

## What a spec may contain, and in which language

Two rules, both of them consequences of this repository being public rather than of
anybody's taste.

**Everything under `specs/` is English.** Not only the slugs and the branch names but the
prose as well: goal, claims, plan, tasks, decisions. `CLAUDE.md § Language` says it for the
repository and `specs/` is part of the repository, so no exception is carried here. The
German that legitimately exists in this project is *content* — the adverts the tool reads,
the letters it writes, the pitches in the profile — and it lives in `config/` and in the
i18n catalogs, never in a spec.

**A spec names no value that `.env` and `config/` exist to hold.** No portal, no newsletter
sender, no mail provider, no model name, no rate floor, no city list, no matching profile.
This is the repo-wide "nothing is wired in" invariant applied to prose instead of to
configuration, and it bites harder here, because a spec describes the intention behind a
value and an intention is often more revealing than the value. Anonymized forms
(`<newsletter-sender>`, `portal-a`) are available and are the answer when a spec genuinely
has to talk about a source.

**Where a thing goes that cannot be written that way.** If a piece of work can only be
specified with real portals, real senders or real numbers, it does not get a folder under
`specs/`. It stays in the master `ISA.md`, which is untracked, or in `Plans/`, which is
untracked too and carries no authority. Choosing the private lane is a normal outcome, not
a failure; what is not allowed is writing the personal datum into the public one.

## Binding sources

| Source | Kind | Governs |
|--------|------|---------|
| `~/.claude/LIFEOS/USER/ENGINEERING/` | house | default stack, architecture, design (app track), delivery, verification |
| `CLAUDE.md`, `backend/CLAUDE.md`, `frontend/CLAUDE.md` | repo | invariants, commands, the traps already paid for, order of work |
| `docs/decisions/` (11 files) | repo | technology decisions per pipeline stage, read side, frontend design system |
| `docs/ARCHITECTURE.md`, `docs/CONFIGURATION.md`, `docs/WRITING-RULES.md` | repo | structure, configuration layers, text rules |
| `build.gradle.kts`, `gradle/libs.versions.toml`, `.editorconfig`, `.github/workflows/` | repo | toolchain, pins, formatting and the gates that actually run |
| `ISA.md § Principles`, `§ Constraints` | repo, **untracked** | substrate-independent truths and the constraints that do not move |

**`ISA.md` is in `.gitignore` and stays there.** It names the operator throughout and its
measurements give away where he lives, and the comment at `.gitignore:78` calls it a working
document rather than project documentation in as many words. One rule follows for this file:
**no row under `## Non-negotiables` rests on the master alone.** Each also names the tracked
file that carries the same rule, so a reader of the public repository can look every row up.
What exists only in the master binds the operator and does not appear here.

**What that means for specs.** `specs/` is versioned and reviewed in the pull request; the
master is not. Claim IDs are still minted in the master first, and a `spec.md` carries the
claim *text* rather than only its number, so the ID is an address into a private register
and the public spec still reads on its own.

**Design track `app`,** derived from: `@ngrx/signals` with nine stores under
`frontend/src/app/core/store/`, `src/app/layout/` as the app shell, no SSG configuration, no
SEO service. No marketing track.

## Non-negotiables

Every rule from `BACKEND.md`, `FRONTEND.md`, `DELIVERY.md` and `DESIGN.md` (app track)
binds, except what stands under `## Adaptations` and `## Conformance baseline`. Named
individually are only the rules whose probe has its own resolution here, or that this
repository particularly rests on.

| Rule | Source | Verdict | Probe |
|------|--------|---------|-------|
| BE-BLD-01 Java 25 through the toolchain | house + `backend/build.gradle.kts` | binding | `./gradlew check` |
| BE-BLD-07 one Gradle entry point for both languages | house + `frontend/build.gradle.kts` | binding | `./gradlew check` |
| BE-DB-02 Flyway owns the schema | house + `backend/CLAUDE.md` ("JDBC, not JPA") | binding | `./gradlew check` |
| BE-DB-04 `JdbcClient` rather than JPA | house + `docs/decisions/read-side.md` | binding | `./gradlew check` |
| BE-QUA-04 an SPDX header in every `.java` | house + `.github/workflows/ci.yml` | binding | CI step "Every Java file carries its SPDX header" |
| BE-DB-03 migrations byte-identical | house + `.editorconfig` + `.github/workflows/ci.yml` | binding | CI step against changed migrations |
| BE-TST-07 test inputs declared as Gradle `inputs.files` | house + `backend/build.gradle.kts` | binding | `./gradlew check` |
| FE-LAYER-01..04 layering through aliases | house + `frontend/eslint.config.mjs` | binding | `bun run check:static` |
| FE-FW-04/05 OnPush and signals as lint errors | house + `frontend/eslint.config.mjs` | binding | `bun run check:static` |
| FE-STATE-01 `@ngrx/signals`, events dialect | house + `frontend/CLAUDE.md` (NgRx line) | binding | review |
| FE-TOOL-01 bun, one lockfile, `packageManager` agrees | house + `CLAUDE.md` § Commands + `frontend/package.json` | binding | `bun install --frozen-lockfile` |
| DS-APP-04 `color-no-hex` | house + `frontend/.stylelintrc.json` | binding | `bun run lint:css` |
| XC-03 never commit | house + `CLAUDE.md` § Repo-wide invariants | binding | review |
| XC-04 everything English, no exceptions | `CLAUDE.md` § Language | adapted | review |
| XC-08 working notes stay small | house + `backend/build.gradle.kts` | binding | `WorkingNotesStaySmallTest` |
| OPS-CHART-01 chart published as OCI | house | waived | — (`CLAUDE.md`: Docker Compose is the supported way to run this) |

## Adaptations

| Rule | House default | This repo does | Recorded in | Why |
|------|---------------|----------------|-------------|-----|
| XC-04 | code English, UI strings German | everything English, the log output included; the German content lives as data in `config/` | `CLAUDE.md` § Language | the repository is going public. Content is configuration, not surface |
| BE-QUA-02 | Spotless active is `deliberate-absence` | identical, plus a CI grep standing in for the licence header | `backend/build.gradle.kts`, comment block | the switch-off is argued here **and** the replacement is built. The house standard does not know about the replacement |
| OPS-CHART-01 | chart published as an OCI artifact | no chart; Docker Compose is the supported way | `CLAUDE.md` | single operation on one machine, no cluster target |
| BE-API-03 | path `/<service>/v1/...` | `/api/v1/...`: the version was pulled in, the service name was not | this entry | `<service>` pays off once several services sit behind one gateway. Exactly one sits behind this one, and `/api` is wired into nginx and the dev proxy. The version is the part of the rule that buys something: a breaking change now has somewhere to go |

## Conformance baseline

> Measured on 2026-09-20. `./gradlew check --no-daemon` ran through unchanged:
> **BUILD SUCCESSFUL in 2m 51s**, exit 0, nine tasks. This section describes what holds
> today, not what binds.

| Rule | Status | Measured | Note |
|------|--------|----------|------|
| backend tiers quick and static | **met** | 2026-09-20, `./gradlew check` exit 0 in 2m 51s | covers compilation, JUnit with Testcontainers against a real PostgreSQL, `ddl-auto: validate`, JaCoCo **and** the whole frontend static and unit tier |
| G-FE-03 ladder script names | **cleared** | 2026-09-20, `bun run check:static` runs green | `verify:quick` and `verify` added the same day. All three tier names now exist on the bun side too |
| G-FE-01 browser tier and contrast | grandfathered | 2026-09-20, no `test-browser` target, zero `*.browser.spec.ts` | DS-APP-32/33 and FE-TST-02 are unproven here. Clearing condition: port the target plus the contrast spec |
| G-FE-02 i18n parity | grandfathered | 2026-09-20, Transloco present, no parity spec | FE-I18N-03 unproven. XC-05 is moot as long as everything is English |
| FE-TST-05 coverage as a ratchet | grandfathered | 2026-09-20, v8 coverage runs, no threshold in `angular.json` | the report exists and is uploaded in CI; the ratchet is missing |
| BE-DB-03 migration immutability | **cleared** | 2026-09-20, CI step added and the diff logic checked against a fixture branch | the step runs on pull requests only, because "changed against the base" has an answer only there |
| BE-ARCH-01..03 vertical slices | not measured | — | not checkable without ArchUnit. Gap G-BE-01, verdict "defer" |
| BE-API-03 path versioning | **cleared** | 2026-09-20, `./gradlew check` exit 0 after the rewrite | 157 replacements across 33 files plus 24 in 11 template literals and doc comments, and `SECURITY.md`. nginx and the dev proxy needed nothing: `location /api/` matches `/api/v1/` and rewrites correctly |

## Decided on 2026-09-20

1. **Java version.** `ISA.md § Constraints` said Java 21, the toolchain pins 25. The ISA line
   had been left standing and is corrected. Note: `ISA.md` is in this repository's
   `.gitignore`, so the correction is local and is not versioned along with the rest.
2. **API versioning.** Pulled in straight away rather than deferred, see `## Adaptations` and
   the cleared baseline row. The rewrite is a breaking change and belongs in `CHANGELOG.md`,
   which was in open work at the time of the change and was therefore left alone.

## Decided on 2026-09-21

1. **The master stays untracked, and this file stops resting on it.** Five rows under
   `## Non-negotiables` and one under `## Adaptations` named `ISA.md § Constraints` as their
   source, a file a reader of the public repository cannot open. Every one of those rules is
   written down in a tracked file as well, and that is what the rows name now. The
   alternative was to track `ISA.md`; what argues against it is what the file contains, not
   how it is written.
2. **`specs/` stays tracked, under a content rule and in English.** The reason the master is
   excluded is its content, not its kind, and a spec carries none of that content: it says
   what is being built and why, for the same readers as `docs/decisions/`. Making the tree
   untracked would turn it into a second `Plans/` and remove the only reason the specs are in
   the repository at all. So the tree stays, `## What a spec may contain` says what may not
   be written into it, and the language is English like the rest of the repository. The
   German-prose default this principal's tooling carries for spec work is a private-project
   default and does not reach a public repository.

## Gates

| Tier | Command | Runs when |
|------|---------|-----------|
| static | `./gradlew :frontend:lint` | before a claim closes |
| quick | `./gradlew check` | at every implementation stop |
| full | `./gradlew build` | before a spec goes to `complete` |

The same three names have existed on the bun side since 2026-09-20 (`check:static`,
`verify:quick`, `verify`); the Gradle column stays the resolution, because only it covers
both stacks.

The Gradle column is the resolution here because `frontend/build.gradle.kts` hangs the bun
scripts in as `Exec` tasks and `check` depends on `lint` and `test`. The browser tier is in
none of the three steps, because it does not exist: see G-FE-01.

## How specs are held to it

Every new spec is read against `## Non-negotiables`. A draft that goes against a `binding`
row marks that inline as `⟨?: deviates from <RULE-ID> — …⟩` and resolves it in
`plan.md § Stack Decisions`, with the rule ID, the failure mode and a probe that stays green.
If one of the three is missing, the row is not written but asked about.

A spec whose work touches a row under `## Conformance baseline` carries
`plan.md § Conformance Impact` and says, per row, whether it clears it, extends it or leaves
it alone. A gate that goes red through the work is repaired, never entered here afterwards.
