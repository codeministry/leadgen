---
repo: lead-generation
derived: 2026-09-20
standards_version: 1.0.0
design_track: app
stack: [spring-boot, angular]
---

# Constitution — lead-generation

> Abgeleitet am 2026-09-20 aus den unten gelisteten Quellen. Die Regeln stehen in diesen
> Quellen; diese Datei sagt, welche davon Spec-Arbeit binden, wo sie zu lesen sind und was
> sie beweist.

## Binding sources

| Source | Kind | Governs |
|--------|------|---------|
| `~/.claude/LIFEOS/USER/ENGINEERING/` | house | Default-Stack, Architektur, Design (App-Track), Delivery, Verification |
| `CLAUDE.md`, `backend/CLAUDE.md`, `frontend/CLAUDE.md` | repo | Invarianten, Kommandos, bereits teuer bezahlte Fallen, Arbeitsreihenfolge |
| `docs/decisions/` (11 Dateien) | repo | Technologieentscheidungen je Pipeline-Stufe, Read-Side, Frontend-Designsystem |
| `docs/ARCHITECTURE.md`, `docs/CONFIGURATION.md`, `docs/WRITING-RULES.md` | repo | Aufbau, Konfigurationsschichten, Textregeln |
| `ISA.md § Principles`, `§ Constraints` | repo | substratunabhängige Wahrheiten und unverrückbare Vorgaben |
| `build.gradle.kts`, `gradle/libs.versions.toml`, `.editorconfig`, `.github/workflows/` | repo | Toolchain, Pins, Formatierung und die Gates, die wirklich laufen |

**Design-Track `app`,** abgeleitet aus: `@ngrx/signals` mit neun Stores unter
`frontend/src/app/core/store/`, `src/app/layout/` als App-Shell, keine SSG-Konfiguration,
kein SEO-Service. Kein Marketing-Track.

## Non-negotiables

Jede Regel aus `BACKEND.md`, `FRONTEND.md`, `DELIVERY.md` und `DESIGN.md` (App-Track)
bindet, ausgenommen was unter `## Adaptations` und `## Conformance baseline` steht.
Einzeln genannt werden nur die Regeln, deren Probe hier eine eigene Auflösung hat oder die
dieses Repo besonders tragen.

| Rule | Source | Verdict | Probe |
|------|--------|---------|-------|
| BE-BLD-01 Java 25 über die Toolchain | house + `backend/build.gradle.kts` | binding | `./gradlew check` |
| BE-BLD-07 ein Gradle-Einstieg für beide Sprachen | house + `frontend/build.gradle.kts` | binding | `./gradlew check` |
| BE-DB-02 Flyway besitzt das Schema | house + `ISA.md § Constraints` | binding | `./gradlew check` |
| BE-DB-04 `JdbcClient` statt JPA | house + `docs/decisions/read-side.md` | binding | `./gradlew check` |
| BE-QUA-04 SPDX-Header in jeder `.java` | house + `.github/workflows/ci.yml` | binding | CI-Schritt „Every Java file carries its SPDX header" |
| BE-DB-03 Migrationen bytegleich | house + `.editorconfig` + `.github/workflows/ci.yml` | binding | CI-Schritt gegen geänderte Migrationen |
| BE-TST-07 Testeingaben als Gradle `inputs.files` deklariert | house + `backend/build.gradle.kts` | binding | `./gradlew check` |
| FE-LAYER-01..04 Schichtung über Aliase | house + `frontend/eslint.config.mjs` | binding | `bun run check:static` |
| FE-FW-04/05 OnPush und Signals als Lint-Fehler | house + `frontend/eslint.config.mjs` | binding | `bun run check:static` |
| FE-STATE-01 `@ngrx/signals`, Events-Dialekt | house + `ISA.md § Constraints` | binding | review |
| FE-TOOL-01 bun, ein Lockfile, `packageManager` stimmt | house + `ISA.md § Constraints` | binding | `bun install --frozen-lockfile` |
| DS-APP-04 `color-no-hex` | house + `frontend/.stylelintrc.json` | binding | `bun run lint:css` |
| XC-03 nie committen | house + `ISA.md § Constraints` | binding | review |
| XC-04 alles Englisch, ohne Ausnahme | `ISA.md § Constraints` | adapted | review |
| XC-08 Arbeitsnotizen bleiben klein | house + `backend/build.gradle.kts` | binding | `WorkingNotesStaySmallTest` |
| OPS-CHART-01 Chart per OCI | house | waived | — (`CLAUDE.md`: Docker Compose ist der unterstützte Betriebsweg) |

## Adaptations

| Rule | House default | This repo does | Recorded in | Why |
|------|---------------|----------------|-------------|-----|
| XC-04 | Code Englisch, UI-Strings Deutsch | alles Englisch, auch die Logausgabe; die deutschen Inhalte liegen als Daten in `config/` | `ISA.md § Constraints` | das Repo geht öffentlich. Inhalt ist Konfiguration, nicht Oberfläche |
| BE-QUA-02 | Spotless aktiv ist `deliberate-absence` | identisch, plus ein CI-Grep als Ersatz für den Lizenzheader | `backend/build.gradle.kts`, Kommentarblock | die Abschaltung ist hier begründet **und** der Ersatz ist gebaut. Der Hausstandard kennt den Ersatz nicht |
| OPS-CHART-01 | Chart als OCI-Artefakt veröffentlicht | kein Chart; Docker Compose ist der unterstützte Weg | `CLAUDE.md` | Einzelbetrieb auf einer Maschine, kein Cluster-Ziel |
| BE-API-03 | Pfad `/<service>/v1/...` | `/api/v1/...` — die Version ist nachgezogen, der Dienstname nicht | dieser Eintrag | `<service>` zahlt sich erst aus, wenn mehrere Dienste hinter einem Gateway liegen. Hier liegt genau einer dahinter, und `/api` ist in nginx und im Dev-Proxy verdrahtet. Die Version ist der Teil der Regel, der etwas kauft: ein Breaking Change hat jetzt ein Ziel |

## Conformance baseline

> Gemessen am 2026-09-20. `./gradlew check --no-daemon` lief unverändert durch:
> **BUILD SUCCESSFUL in 2m 51s**, Exit 0, neun Tasks. Dieser Abschnitt beschreibt, was heute
> gilt, nicht was bindet.

| Rule | Status | Measured | Note |
|------|--------|----------|------|
| Backend-Tier quick und static | **erfüllt** | 2026-09-20, `./gradlew check` Exit 0 in 2m 51s | deckt Kompilierung, JUnit mit Testcontainers gegen echtes PostgreSQL, `ddl-auto: validate`, JaCoCo **und** den ganzen Frontend-Static- und Unit-Tier ab |
| G-FE-03 Ladder-Skriptnamen | **geklärt** | 2026-09-20, `bun run check:static` läuft grün | `verify:quick` und `verify` am selben Tag ergänzt. Alle drei Tier-Namen existieren jetzt auch auf der bun-Seite |
| G-FE-01 Browser-Tier und Kontrast | grandfathered | 2026-09-20, kein `test-browser`-Target, null `*.browser.spec.ts` | DS-APP-32/33 und FE-TST-02 sind hier ungeprüft. Klärbedingung: Target plus Kontrast-Spec portieren |
| G-FE-02 i18n-Parität | grandfathered | 2026-09-20, Transloco vorhanden, kein Paritäts-Spec | FE-I18N-03 ungeprüft. XC-05 ist gegenstandslos, solange alles Englisch ist |
| FE-TST-05 Coverage als Ratsche | grandfathered | 2026-09-20, v8-Coverage läuft, keine Schwelle in `angular.json` | der Report existiert und wird in CI hochgeladen; die Ratsche fehlt |
| BE-DB-03 Migrations-Immutabilität | **geklärt** | 2026-09-20, CI-Schritt ergänzt und die Diff-Logik gegen einen Fixture-Branch gegengeprüft | der Schritt läuft nur auf Pull Requests, weil die Frage nach einer Änderung gegenüber der Basis nur dort eine Antwort hat |
| BE-ARCH-01..03 Vertical Slices | not measured | — | ohne ArchUnit nicht prüfbar. Lücke G-BE-01, Urteil „vertagen" |
| BE-API-03 Pfadversionierung | **geklärt** | 2026-09-20, `./gradlew check` Exit 0 nach dem Umbau | 157 Ersetzungen in 33 Dateien plus 24 in 11 Template-Literalen und Doc-Kommentaren, dazu `SECURITY.md`. nginx und Dev-Proxy brauchten nichts: `location /api/` matcht `/api/v1/` und schreibt korrekt um |

## Entschieden am 2026-09-20

1. **Java-Version.** `ISA.md § Constraints` sagte Java 21, die Toolchain pinnt 25. Die
   ISA-Zeile war stehengeblieben und ist korrigiert. Beachten: `ISA.md` steht in der
   `.gitignore` dieses Repos, die Korrektur ist also lokal und wird nicht mitversioniert.
2. **API-Versionierung.** Direkt nachgezogen statt vertagt, siehe `## Adaptations` und die
   geklärte Baseline-Zeile. Der Umbau ist ein Breaking Change und gehört in den
   `CHANGELOG.md` — der lag zum Zeitpunkt der Änderung in offener Arbeit und wurde
   deshalb nicht angefasst.

## Gates

| Tier | Command | Runs when |
|------|---------|-----------|
| static | `./gradlew :frontend:lint` | bevor ein Claim schliesst |
| quick | `./gradlew check` | an jedem Implementierungs-Stop |
| full | `./gradlew build` | bevor eine Spec auf `complete` geht |

Auf der bun-Seite existieren seit 2026-09-20 dieselben drei Namen (`check:static`,
`verify:quick`, `verify`); die Gradle-Spalte bleibt die Auflösung, weil nur sie beide
Stacks abdeckt.

Die Gradle-Spalte ist hier die Auflösung, weil `frontend/build.gradle.kts` die bun-Skripte
als `Exec`-Tasks einhängt und `check` von `lint` und `test` abhängt. Der Browser-Tier ist
in keiner der drei Stufen enthalten, weil es ihn nicht gibt: siehe G-FE-01.

## How specs are held to it

Jede neue Spec wird gegen `## Non-negotiables` gelesen. Ein Entwurf gegen eine
`binding`-Zeile markiert das inline als `⟨?: deviates from <RULE-ID> — …⟩` und löst es in
`plan.md § Stack Decisions` auf, mit Regel-ID, Fehlermodus und einer Probe, die grün bleibt.
Fehlt eine der drei Angaben, wird die Zeile nicht geschrieben, sondern gefragt.

Eine Spec, deren Arbeit eine Zeile aus `## Conformance baseline` berührt, führt
`plan.md § Conformance Impact` und sagt je Zeile, ob sie sie klärt, verlängert oder liegen
lässt. Ein Gate, das durch die Arbeit rot wird, wird repariert, nie nachträglich hier
eingetragen.
