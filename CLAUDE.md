# CLAUDE.md

Acquisition tool for freelancers. Collects project offers from configured sources,
filters them against a profile, enriches the survivors, scores them and assembles a
ready-to-send application package. **No automatic sending.**

Concept: `docs/CONCEPT.md`. Measured baseline: `docs/SAMPLE-ANALYSIS.md`.
Why the pipeline grew an enrichment stage: `docs/CONCEPT-addendum-enrichment.md`.

## Language

**Everything in this repository is English.** Code, identifiers, comments, config
comments, documentation, commit messages, issues, UI strings, log output, test names.
No exceptions, and no German creeping back in over time.

The one thing that is *not* repo language but data: the offers this tool reads are
German, the cover letters it writes are German, and the reference-project pitches in
the profile are German. That is **content**, it lives in `config/local/` and in i18n
catalogs, and it is selected by the language of the job ad — never hardcoded.

If you find German anywhere else, translate it in the same change. Do not add a
German comment "just this once".

## Repo-wide invariants

Violating one of these is expensive, and most of them fail silently.

- **Nothing is wired in.** This repo is going public. No newsletter name, no portal,
  no mail provider, no model name and no personal datum belongs in the code. Everything
  individual lives in `config/local/` (gitignored) and in `.env`. A new source is a YAML
  block, not a deploy.
- **Rules before model.** The hard filter runs deterministically and for free before any
  LLM call. Without a language model the tool must still run, only weaker.
- **No CV tailoring.** Fixed PDFs in `config/local/documents/`, selected by the language
  of the ad and nothing else.
- **The mail address never leaves the machine.** Newsletter links are proxied as
  `…/proxy?target=…&email=…`. Unwrap `target`, discard `email`. Raw `.eml` files and
  anything derived from them are gitignored — they carry the address in headers and
  unsubscribe links.
- **`min_hourly_eur` must not apply before the enrichment stage.** The newsletter carries
  a rate in 0.0 % of offers. Applied earlier, the rule filters either everything or nothing.
- **Never commit.** Do the work, leave it uncommitted, offer the commit — Marcello decides.

## Monorepo

`backend/` (Spring Boot, Java 21, Gradle) · `frontend/` (Angular + NGRX + DaisyUI) ·
`charts/` (Helm) · `config/` · `docs/`. The root Gradle build brackets both, the
frontend through the Node Gradle plugin.

## What already exists

```
config/local/sources.yaml         Strato IMAP, verified JobScout selectors, 6 sources
config/local/matching-rules.yaml  hard filters + scoring weights
config/local/application.yaml     LLM, enrichment, packaging, digest
config/local/skill-profile.yaml   skills with weights and aliases, reference projects
config/examples/*.example.yaml    neutral versions for the public repo
.env.example
docs/samples/emails/*.eml         14 real newsletter mails (gitignored)
docs/samples/analyze_samples.py   extraction, field coverage, duplicates
docs/samples/simulate_filter.py   simulation of the hard filters
```

The two Python scripts are the **reference implementation**. Whatever they do, the Java
code has to reproduce — the numbers in `docs/SAMPLE-ANALYSIS.md` are the target values.

## Measured baseline

- 14 mails, **1289 offers**, all extracted deterministically via CSS. The count announced
  in the subject matches exactly in all 14. `fallback: none` for this source.
- **0.0 % contain an hourly rate.** Rate, duration, workload and start date only arrive
  from the enrichment stage (fetching the original ad from the portal).
- The hard filter lets **16.5 %** through, **~15 per mail** after deduplication. That is
  the daily LLM budget.
- **12.3 % duplicates** by exact title alone, within a single mail.

## Order of work

1. **Monorepo skeleton** — root build, `backend/` skeleton, `frontend/` skeleton,
   `docker-compose.yml` (postgres, api, web), `.env` loading, Flyway.
2. **Configuration layer** — load, validate and hot-reload `sources.yaml`,
   `matching-rules.yaml`, `application.yaml`. First, because everything else stands on it.
3. **Ingest + extract** against the `local-eml` source (files, no mailbox needed).
   Acceptance test: 1289 offers from `docs/samples/emails/`, field coverage as in the analysis.
4. **IMAP connector** — same extraction, different source. Progress tracked via
   `UIDVALIDITY`/`UID`, **never** via seen/unseen: the user reads the same mails on a phone.
5. **Dedupe** — do not postpone, it pays off within a single mail.
6. **Hard filter** — must hit the 16.5 % from the simulation. A deviation is a bug.
7. **Enrichment** — HTTP fetch of the original ad, rate limit, cache, `robots.txt`.
   A failed fetch is not a knockout; the offer stays in as *incomplete*.
8. **Scoring + digest** — first daily overview, still without a frontend.
9. **Packaging** — cover letter from a Freemarker template plus reference projects from
   the profile, copy in the PDF matching the ad's language.
10. **Frontend** — shortlist, detail, pipeline, sources, profile & rules.

## Traps that have already cost money

- Search terms are wrapped in `<mark>` inside the title. Strip before any title
  comparison, or deduplication trips over `<mark>DevOps</mark>`.
- Strip `(m/w/d)`, `(w/m/d)`, `(m/f/d)` before normalizing.
- The location sits behind a `📍` prefix in one of four `span`s in `div.job-meta` —
  address it by the prefix, never by position.

## Open

- **Adopt the code conventions from `codeministry/customer/ship360`.** Not done yet.
  Read its `CLAUDE.md` and `AGENTS.md` first, then carry over what applies to this repo:
  strict layering with tsconfig aliases and no barrels, standalone components with
  signals and `OnPush`, the `@ngrx/signals` events dialect, `.css` with Tailwind and no
  raw hex, strict TypeScript settings, specs beside their file, and the tooling baseline
  (`.editorconfig`, ESLint, Prettier, Stylelint, CI). Deviate only where a Spring Boot
  backend genuinely differs, and note why.
- License for publication (Apache 2.0, like `straightmail`?)
- Repository name and GitHub organisation
- Which folder in the Strato mailbox the newsletter lands in
