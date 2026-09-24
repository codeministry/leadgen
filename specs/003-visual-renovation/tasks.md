---
spec: 003-visual-renovation
plan: plan.md
updated: 2026-09-24 (all six stages built)
---

# Tasks 003 — The visual renovation

**Purpose:** atomic, checkable steps. Each task hangs on exactly one claim ID from
`spec.md`. This file defines nothing, it decomposes.

## Legend

`[P]` = parallelizable. `(after: T…)` = must run after that task.

`[P]` was derived from `IsaFrontier.ts frontier` on 2026-09-23: fourteen claims are takeable (ISC-222, 223, 224,
228, 229, 231, 233, 234, 235, 236, 237, 238, 241, 242) and seven are blocked behind ISC-228, ISC-229, ISC-231, ISC-224 and
ISC-238. A task carries `[P]` only when its claim is takeable, it has no `after` edge, and no other `[P]` task names
its file. `styles.css`, `tokens.css` and `contrast.browser.spec.ts` are each touched by many tasks, which is why most
of Stages 1 to 4 run in sequence whatever their claims' edges say. The stage stops come from `plan.md` § Approach and
are where `ImplementSpec` runs the gate ladder and waits for the operator; the live frontier is the authority at
dispatch time, this column is a hint.

## Tasks

### Stage 0 — harness and guards, no visible change

- [x] T1 · ISC-228 · [P] — `test-browser` target on `@angular/build:unit-test` (`browsers`, `include`, `setupFiles`), `exclude` of `*.browser.spec.ts` on the unit target · `frontend/angular.json`
- [x] T2 · ISC-228 · [P] — script `test:browser`, devDependencies `@vitest/browser-playwright` and `playwright`, `bun install` · `frontend/package.json`, `frontend/bun.lock`
- [x] T3 · ISC-228 — the browser setup file: Transloco as in `test-setup.ts`, no canvas stub (after: T2) · `frontend/src/test-setup.browser.ts`
- [x] T4 · ISC-228 — smoke browser spec: `--color-primary` non-empty on `documentElement`, a 1×1 canvas turns `oklch()` into sRGB; the contrast helpers (resolve, composite, ratio) live here (after: T1, T3) · `frontend/src/app/core/theme/contrast.browser.spec.ts`
- [x] T5 · ISC-228 — Gradle `testBrowser` Exec task hung into `check` (after: T4) · `frontend/build.gradle.kts`
- [x] T6 · ISC-228 — CI step `bunx playwright install --with-deps chromium` before the frontend step (after: T4) · `.github/workflows/ci.yml`
- [x] T7 · ISC-222 · [P] — the colour guard's first half: parse both theme blocks, gamut check, hex round-trip, `FALLBACK` exported from `chart-theme.ts` and compared; pinned to today's palette · `frontend/src/app/core/theme/theme-colors.spec.ts`, `frontend/src/app/core/theme/chart-theme.ts`
- [x] T8 · ISC-223 — the guard's second half: every `var(--x)` under `src/app` against the four stylesheets and DaisyUI's names, fallbacks not counted, `--lg-anchor-*` allowlisted, dark and system-dark corrective blocks compared; fix `--lg-gap-s` in `ask-panel.css` (after: T7) · `frontend/src/app/core/theme/theme-colors.spec.ts`, `frontend/src/app/features/offer-detail/ask-panel/ask-panel.css`
- [x] T9 · ISC-231 · [P] — the tier guard in report mode: scan every template for `btn` elements and print the class distribution, asserting nothing yet · `frontend/src/app/core/theme/button-tiers.spec.ts`
- [x] T10 · ISC-237 · [P] — the density baseline: `VerifyViewport.ts` at 1440×900 on the shortlist, cards above the fold and page-header height, recorded in `spec.md` § Decisions by the parent · (evidence only)

### Stage 1 — the palette and the mark candidates, one review

- [x] T11 · ISC-222 — the new theme blocks and the three corrective blocks (light, dark, system-dark), stage ramps rebuilt, every value `oklch()` with its hex comment (after: T7, T8) · `frontend/src/styles.css`
- [x] T12 · ISC-222 — `FALLBACK` updated to the new light hex values (after: T11) · `frontend/src/app/core/theme/chart-theme.ts`
- [x] T13 · ISC-224 — `--lg-signal` and `--lg-signal-text` aliases, `--score-strong` on `--lg-signal`, `--lg-accent-text` kept one stage as an alias (after: T11) · `frontend/src/styles/tokens.css`, `frontend/src/styles.css`
- [x] T14 · ISC-224 — `--lg-code-string` and `--lg-code-number` in the corrective blocks; syntax highlighting reads them (after: T13) · `frontend/src/styles.css`, `frontend/src/app/shared/markdown/markdown.css`, `frontend/src/app/shared/markdown/markdown-source.css`
- [x] T15 · ISC-224 — the "answered" series takes a passed tone on `RankedBarChart`, default primary; the panel passes it (after: T13) · `frontend/src/app/shared/chart/ranked-bar-chart.ts`, `frontend/src/app/features/analytics/applications-panel/applications-panel.html`
- [x] T16 · ISC-224 — the wordmark's second half off the accent (after: T13) · `frontend/src/app/shared/brand-mark/brand-mark.css`
- [x] T17 · ISC-226 — the score figure's text on `--lg-signal-text`, the ring's stroke on the fill (after: T4, T13) · `frontend/src/app/shared/score/score.css`
- [x] T18 · ISC-225 — the seven `--lg-section-*` values per theme, same L and C, rotating hue, hex comments (after: T13) · `frontend/src/styles.css`
- [x] T19 · ISC-225 — section geometry in the guard: L and C bands, pairwise hue distance, ΔE-OK to signal, primary and the semantic four (after: T18) · `frontend/src/app/core/theme/theme-colors.spec.ts`
- [x] T20 · ISC-226 — the text pair table in the contrast spec, per theme, rendered elements where the claim names one (after: T4, T13, T17) · `frontend/src/app/core/theme/contrast.browser.spec.ts`
- [x] T21 · ISC-227 — the UI pair table: signal, sections, primary, focus outline, nav marker, brand mask (after: T20, T18) · `frontend/src/app/core/theme/contrast.browser.spec.ts`
- [x] T22 · ISC-241 — three single-colour SVG mark candidates on one scratch sheet outside the repo, masked with `--color-primary` on base-100 and base-200 in both themes at 16, 26, 32 and 128 px and on the favicon plate (after: T11) · (scratch, outside the repo)
- [x] T23 · ISC-241 — the Stage 1 review sitting: all seven screens in both themes through the Interceptor skill, the candidate sheet beside them; the operator's word on the palette, the fonts' default, the hues and the mark (after: T19, T21, T22) · (evidence only)

### Stage 2 — action tiers

- [x] T24 · ISC-231 — classify every `btn` in the detail and board templates: `offer-detail.html`, `application-panel.html`, `ask-panel.html`, `pipeline.html`, `review.html`, `review-card.html`, `source-panel.html`, `app-header.html` (after: T23) · those eight templates
- [x] T25 · ISC-231 — classify every `btn` in the shortlist templates: `shortlist-page.html`, `saved-views.html`, `facet-panel.html`, `sort-menu.html` (after: T23) · those four templates
- [x] T26 · ISC-231 — the "filter not at default" marker replacing the conditional `btn-outline` (after: T25) · `frontend/src/app/features/shortlist/sort-menu/sort-menu.html`, `sort-menu.css`, `frontend/src/app/features/shortlist/facet-panel/facet-panel.html`, `facet-panel.css`
- [x] T27 · ISC-231 — the tier guard switches to enforcing (after: T24, T26) · `frontend/src/app/core/theme/button-tiers.spec.ts`
- [x] T28 · ISC-232 — `TOAST_LINK_CLASS` literal map beside `TOAST_TONE_CLASS`; the stack reads it (after: T24) · `frontend/src/app/core/toast/toast.model.ts`, `frontend/src/app/layout/toast-stack/toast-stack.html`
- [x] T29 · ISC-232 — contrast spec: one toast per tone, the link's label and boundary against the tint (after: T28, T21) · `frontend/src/app/core/theme/contrast.browser.spec.ts`
- [x] T30 · ISC-232 — pixel capture over CDP of the three tones with a link at 1280 and 390 px, past the reveal (after: T29) · (evidence only)

### Stage 3 — the section colour

- [x] T31 · ISC-229 · [P] — the `SECTIONS` literal union and its type · `frontend/src/app/core/theme/section.model.ts`
- [x] T32 · ISC-229 — `data.section` on the seven top-level routes, merged into pipeline's existing data (after: T31) · `frontend/src/app/app.routes.ts`
- [x] T33 · ISC-229 — `AppShell.section` computed from `leaf().data`, bound as `[attr.data-section]` on the host (after: T31) · `frontend/src/app/layout/app-shell/app-shell.ts`
- [x] T34 · ISC-229 — the shell spec's added test: `RouterTestingHarness` over the seven paths, `/shortlist/7`, `/pipeline/7`, `/nope` (after: T32, T33) · `frontend/src/app/layout/app-shell/app-shell.spec.ts`
- [x] T35 · ISC-230 — `--lg-section` neutral on `:root`, one `[data-section='x']` rule per section, the rhythm token `--lg-edge-w` (after: T18, T33) · `frontend/src/styles/tokens.css`
- [x] T36 · ISC-230 — the nav's active marker and wash on `--lg-section`, the label on `base-content` (after: T35) · `frontend/src/app/layout/app-nav/app-nav.css`
- [x] T37 · ISC-230 — the page header's accent on the h1 variant (after: T35) · `frontend/src/app/shared/page-header/page-header.ts`, `frontend/src/app/shared/page-header/page-header.css`
- [x] T38 · ISC-230 — `primitives.css` with `.lg-panel` and its section edge, imported after `tokens.css`; the four `.panel` rules and the stat-tile surface replaced (after: T35) · `frontend/src/styles/primitives.css`, `frontend/src/styles.css`, `frontend/src/app/features/analytics/analytics.css`, `dashboard/dashboard.css`, `offer-detail/offer-detail.css`, `rules/rules.css`, `frontend/src/app/shared/stat-tile/stat-tile.html`
- [x] T39 · ISC-230 — contrast spec: `.btn-primary` background read under all seven attributes (after: T36, T37, T38) · `frontend/src/app/core/theme/contrast.browser.spec.ts`
- [x] T40 · ISC-230 — browser pass: the seven routes plus `/shortlist/:id` and `/pipeline/:id` in both themes (after: T39) · (evidence only)

### Stage 4 — type and rhythm

- [x] T41 · ISC-234 — the three font packages swapped, `bun install` (after: T23) · `frontend/package.json`, `frontend/bun.lock`
- [x] T42 · ISC-234 — the imports, the `@theme` font variables, `--lg-mono-features` for the new mono (after: T41) · `frontend/src/styles.css`, `frontend/src/styles/tokens.css`
- [x] T43 · ISC-237 — the `type-*` utilities re-cut, the rhythm tokens, `--lg-muted` darker, `--lg-content-gap` tightened (after: T42) · `frontend/src/styles/tokens.css`, `frontend/src/styles.css`
- [x] T44 · ISC-235 — the four transition tokens; the seventeen duration literals removed (after: T42) · `frontend/src/styles/motion.css`, `frontend/src/app/layout/app-nav/app-nav.css`, `frontend/src/app/shared/score/score.css`, `frontend/src/app/shared/funnel-rail/funnel-rail.css`, `frontend/src/styles.css`
- [x] T45 · ISC-235 — the stylelint duration rule with `motion.css` exempt (after: T44) · `frontend/.stylelintrc.json`
- [x] T46 · ISC-234 — browser probe: the built CSS names the three families, the mono has the features `--lg-mono-features` names (after: T42) · `frontend/src/app/core/theme/contrast.browser.spec.ts`
- [x] T47 · ISC-236 — `--lg-list-w`, `--lg-detail-w`, `--lg-runs-w`, the nav label breakpoint and the 320 px bar re-measured; `VerifyViewport.ts` overflow probe at six widths in both themes (after: T43) · `frontend/src/styles/tokens.css`, `frontend/src/app/layout/app-nav/app-nav.css`
- [x] T48 · ISC-237 — density measured against the Stage 0 baseline, k set, recorded by the parent (after: T43, T10) · (evidence only)

### Stage 5 — the brand

- [x] T49 · ISC-238 — the chosen mark as `brand/mark.svg`, tracked, groups `body` and `signal` (after: T23) · `frontend/brand/mark.svg`
- [x] T50 · ISC-238 — `build-favicon.sh` rewritten around `rsvg-convert` from the SVG, recolour by element id, `-strip` for deterministic output (after: T49) · `frontend/tools/build-favicon.sh`
- [x] T51 · ISC-239 — the icons regenerated, the script run twice, the diff read (after: T50) · `frontend/public/favicon.ico`, `frontend/public/favicon-256.png`, `frontend/public/logo-mark.svg`
- [x] T52 · ISC-238 — `brand-mark`: ~~the mark inlined as SVG with `body` on primary and `signal` on the signal, the intrinsic box from the viewBox~~ (pulled forward on 2026-09-24, the operator wanted the ring in the header at once); still owed here: the wordmark treatment, the `index.html` comment, the bitmap entries out of `.gitignore`, the old PNGs removed (after: T51) · `frontend/src/app/shared/brand-mark/brand-mark.ts`, `brand-mark.css`, `brand-mark.html`, `frontend/src/index.html`, `frontend/.gitignore`, `frontend/public/`
- [x] T53 · ISC-233 — the intrinsic-box line in `brand-mark.spec.ts` (36px → 40px, the viewBox is square), done with the mark on 2026-09-24 (after: T52) · `frontend/src/app/shared/brand-mark/brand-mark.spec.ts`
- [x] T54 · ISC-240 — contrast spec: the mask on base-100 and base-200 per theme; `VerifyViewport.ts` under emulated `forced-colors: active` reads the `.mark` pixels (after: T52, T21) · `frontend/src/app/core/theme/contrast.browser.spec.ts`
- [x] T55 · ISC-240 — browser pass: the header at 26 px, the tab icon, forced colours (after: T54) · (evidence only)

### Stage 6 — records and closing

- [x] T56 · ISC-242 — the renovation section in the decision record: palette, signal, tiers, section, fonts, brand, and what each review changed (after: T30, T40, T48, T55) · `docs/decisions/frontend-design-system.md`
- [x] T57 · ISC-242 — one rule line in the frontend notes; the inventory line for `primitives.css` in the root notes (after: T56) · `frontend/CLAUDE.md`, `CLAUDE.md`
- [x] T58 · ISC-242 — § Unreleased under Changed; the README badge colours (after: T56) · `CHANGELOG.md`, `README.md`
- [x] T59 · ISC-228 — the constitution: G-FE-01 row cleared with the date, the Gates table names the browser tier (after: T5, T6) · `specs/constitution.md`
- [x] T60 · ISC-242 — `WorkingNotesStaySmallTest` run after the three edits (after: T57, T58) · `backend/src/test/java/de/codeministry/leadgen/WorkingNotesStaySmallTest.java`
- [x] T61 · ISC-233 — the final diff over `frontend/src/**/*.spec.ts` against the base, read against the allowlist (after: T53, T60) · `frontend/src/**/*.spec.ts`

## Probe Mapping

| Task | Claim | Probe (from `spec.md` § Test Strategy) |
|------|-------|----------------------------------------|
| T1–T6, T59 | ISC-228 | `bun run test:browser`; rg for the target, the Exec task and the CI step; rg `G-FE-01` in the constitution |
| T7, T11, T12 | ISC-222 | parse both theme blocks: gamut, hex round-trip, `FALLBACK` equality |
| T8 | ISC-223 | every `var(--x)` defined, fallbacks not counted, corrective blocks identical |
| T9, T24–T27 | ISC-231 | scan every template for `btn` elements |
| T10, T43, T48 | ISC-237 | `VerifyViewport.ts` at 1440×900 against the baseline |
| T13–T16 | ISC-224 | `rg -l` over the signal names against the allowlist |
| T17, T20 | ISC-226 | the text pair table through a canvas, per theme |
| T18, T19 | ISC-225 | L and C bands, hue distance, ΔE-OK floors |
| T21 | ISC-227 | the UI pair table through a canvas, per theme |
| T22, T23 | ISC-241 | the candidate sheet at four sizes; the operator's word |
| T28–T30 | ISC-232 | one toast per tone, the link's label and boundary; the two toast specs unchanged |
| T31–T34 | ISC-229 | `RouterTestingHarness` over ten paths |
| T35–T40 | ISC-230 | `rg -l 'var\(--lg-section\)'`; `.btn-primary` background under seven attributes |
| T41, T42, T46 | ISC-234 | rg over `package.json`, `src`, `dist`; the mono feature probe |
| T44, T45 | ISC-235 | `bun run lint:css`; rg for duration literals |
| T47 | ISC-236 | `VerifyViewport.ts` overflow probe at six widths |
| T49, T50, T52 | ISC-238 | rg for the old bitmaps; rg for the script's input |
| T51 | ISC-239 | the script twice, `git diff --exit-code frontend/public` |
| T54, T55 | ISC-240 | the mask through a canvas; forced-colours pixels |
| T53, T61 | ISC-233 | `git diff --stat <base>` over the spec files |
| T56–T58, T60 | ISC-242 | `WorkingNotesStaySmallTest`; rg over the record and the changelog |
