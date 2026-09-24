---
spec: 003-visual-renovation
type: feature
status: approved
updated: 2026-09-24
---

# Plan 003 — The visual renovation

**Purpose:** how the claims in `spec.md` get built. The what lives there; this file
holds no acceptance criterion.

## Approach

Harness first, then the palette and the mark reviewed together, then everything that sits on the
palette. Stage 0 adds the browser test tier, the colour guard, the tier guard in report mode and a
density baseline without changing a pixel, so the first thing the renovation does is make itself
measurable. Stage 1 writes the new palette into both themes and puts three SVG mark candidates on
one sheet, and the operator reviews both in one sitting, because a palette is judged with the mark
on it and a mark with the palette behind it. Stages 2 to 5 (tiers, section colour, type and rhythm,
brand) then build on a palette that has already passed the contrast gate and the operator's eye;
Stage 6 writes the records. Every stage ends with `./gradlew check` green and all seven screens seen
in a real browser in both themes, and the operator confirms before the next stage starts.

The obvious alternative was to wire tiers and sections first on the old palette and repaint last.
It was not taken because every intermediate review would still show the grey the operator is
replacing, every contrast number would be measured twice, and the expensive corrections (a palette
that does not fit, a font that does not set) would arrive after everything was built on them.

## Stack Decisions

| Rule | Chosen | Alternatives | Why | Probe that stays green | Recorded in |
|------|--------|--------------|-----|------------------------|-------------|
| DS-APP-05 colour-guard spec named `theme-colors.spec.ts` | the guard reads `styles.css` from disk inside a Vitest unit spec; if the Angular builder refuses `node:fs`, the guard becomes `bun tools/check-colour-guard.ts` run from `check:static` | (a) a browser spec that reads computed styles instead of the file; (b) no guard | the rule exists so an undefined token cannot render transparent unnoticed; a file-level parse catches it before any browser runs, and a bun script inside the static gate catches it just as early if the builder cannot host it | ISC-222, ISC-223 rows in `spec.md` § Test Strategy; `bun run check:static` (constitution § Gates, static) | this row; `docs/decisions/frontend-design-system.md` at Stage 6 |
| G-FE-01 browser tier and contrast (grandfathered) | cleared: `test-browser` target through `@angular/build:unit-test`, headless Chromium via Playwright, own setup file | (a) a bun script over CDP through `VerifyViewport.ts`; (b) Karma | the rule exists because jsdom resolves neither custom properties nor `oklch()` and passes with the element invisible; only a real renderer can say no. `VerifyViewport.ts` lives outside the repository, so CI cannot run it; Karma is a second runner beside Vitest | ISC-226, ISC-227, ISC-228 rows; `./gradlew check` once the Exec task is wired (constitution § Gates, quick) | `specs/constitution.md` baseline row and Gates table at Stage 6 |
| DS-APP-41 motion tokens (binding, unprobed today) | enforced: `declaration-property-value-disallowed-list` on `transition` and `animation` durations, `motion.css` exempt through `overrides` | leave the 17 literals and add tokens only for new motion | the rule exists so forty transitions do not carry thirty-nine durations; the fonts stage touches every transition anyway, so the literals go in the same pass | ISC-235 row; `bun run lint:css` | `.stylelintrc.json` comment; decision record |

No row departs from a `binding` verdict. Every row either clears a baseline entry or gives a rule
its first probe.

## Affected Files and Modules

Paths under `frontend/` unless a root path is written.

| Path | Change | Claim |
|------|--------|-------|
| `angular.json` | `test-browser` target (`browsers: ["chromiumHeadless"]`, `include: src/**/*.browser.spec.ts`, `setupFiles`), `exclude` on the unit target | ISC-228 |
| `package.json`, `bun.lock` | script `test:browser`; devDependencies `@vitest/browser-playwright`, `playwright`; the three font packages swapped | ISC-228, ISC-234 |
| `build.gradle.kts` | `testBrowser` Exec task hung into `check` | ISC-228 |
| `.github/workflows/ci.yml` (root) | `bunx playwright install --with-deps chromium` before the frontend step | ISC-228 |
| `src/test-setup.browser.ts` (new) | Transloco as in `test-setup.ts`, no canvas stub | ISC-228 |
| `src/app/core/theme/theme-colors.spec.ts` (new) | parses both theme blocks and the corrective blocks; gamut, hex round-trip, `FALLBACK` equality, token definedness, section geometry | ISC-222, ISC-223, ISC-225 |
| `src/app/core/theme/contrast.browser.spec.ts` (new) | the text and UI pair tables, resolved through a canvas per theme; renders the toast stack, the badge, the page header, each button tier, the brand mark, and `.btn-primary` under seven section attributes | ISC-226, ISC-227, ISC-230, ISC-232, ISC-240 |
| `src/app/core/theme/button-tiers.spec.ts` (new) | scans every template for `btn` elements | ISC-231 |
| `src/app/core/theme/color-math.ts` (new) | OKLCH↔sRGB, ΔE-OK, the WCAG ratio; pure functions both guards import | ISC-222, ISC-225, ISC-226 |
| `tsconfig.spec.json`, `tsconfig.app.json` | `node` types and the browser setup file in the spec compile, out of the app compile | ISC-228 |
| `src/styles.css` | new theme blocks and corrective blocks (light, dark, system-dark); `--lg-signal-text`, `--lg-section-*`, `--lg-code-*`; font imports and `@theme` fonts; imports `primitives.css` | ISC-222, ISC-224, ISC-225, ISC-234 |
| `src/styles/tokens.css` | `--lg-signal`, `--lg-section` per `[data-section]`, rhythm tokens, the re-cut `type-*` utilities, `--lg-mono-features` | ISC-223, ISC-230, ISC-234, ISC-237 |
| `src/styles/primitives.css` (new) | `.lg-panel` with the section edge, replacing the four `.panel` rules | ISC-230 |
| `src/styles/motion.css` | the four transition tokens the literals become | ISC-235 |
| `.stylelintrc.json` | the duration rule with `motion.css` exempt | ISC-235 |
| `src/app/core/theme/chart-theme.ts` | `FALLBACK` exported and updated | ISC-222 |
| `src/app/shared/markdown/markdown.css`, `markdown-source.css` | syntax highlighting on `--lg-code-*` | ISC-224 |
| `src/app/shared/score/score.css` | figure text on `--lg-signal-text`, stroke on the fill | ISC-224, ISC-226 |
| `src/app/shared/chart/ranked-bar-chart.ts`, `features/analytics/applications-panel/*` | the "answered" series takes a passed tone, not the accent | ISC-224 |
| `src/app/core/toast/toast.model.ts` | `TOAST_LINK_CLASS` literal map beside `TOAST_TONE_CLASS` | ISC-232 |
| `src/app/layout/toast-stack/toast-stack.html` | link class from the map | ISC-232 |
| the thirteen templates: `offer-detail.html`, `application-panel.html`, `ask-panel.html`, `pipeline.html`, `review.html`, `review-card.html`, `facet-panel.html`, `saved-views.html`, `shortlist-page.html`, `sort-menu.html`, `source-panel.html`, `app-header.html`, `toast-stack.html` | every `btn` classified into a tier | ISC-231 |
| `features/shortlist/sort-menu/sort-menu.css`, `facet-panel/facet-panel.css` | the "filter not at default" marker | ISC-231 |
| `src/app/app.routes.ts` | `data.section` on the seven top-level routes | ISC-229 |
| `src/app/core/theme/section.model.ts` (new) | the `SECTIONS` literal union | ISC-229 |
| `src/app/layout/app-shell/app-shell.ts`, `app-shell.spec.ts` | `section` computed from `leaf().data`, bound as `[attr.data-section]`; one added test | ISC-229 |
| `src/app/layout/app-nav/app-nav.css` | active marker and wash on `--lg-section` | ISC-230 |
| `src/app/shared/page-header/page-header.ts`, `page-header.css` (new) | the accent on the h1 variant | ISC-230 |
| `features/analytics/analytics.css`, `dashboard/dashboard.css`, `offer-detail/offer-detail.css`, `rules/rules.css`, `shared/stat-tile/stat-tile.html` | `.panel` becomes `lg-panel` | ISC-230 |
| `frontend/brand/mark.svg` (new, tracked) | the chosen mark, groups `body` and `signal` | ISC-238, ISC-241 |
| `tools/build-favicon.sh` | rewritten around `rsvg-convert` from the SVG, recolour by element id, deterministic output | ISC-238, ISC-239 |
| `public/favicon.ico`, `public/favicon-256.png`, `public/logo-mark.svg` (new), `public/logo-mark.png` (removed) | regenerated | ISC-239 |
| `src/app/shared/brand-mark/brand-mark.{ts,css,html}`, `brand-mark.spec.ts` | intrinsic box from the viewBox, mask URL, new wordmark | ISC-240, ISC-233 |
| `src/index.html`, `frontend/.gitignore` | the comment on the icons' source; the untracked bitmap entries dropped | ISC-238 |
| `docs/decisions/frontend-design-system.md` (root) | the renovation section: palette, signal, tiers, section, fonts, brand | ISC-242 |
| `frontend/CLAUDE.md`, `CLAUDE.md` (root) | one rule line; the inventory line for `primitives.css` | ISC-242 |
| `CHANGELOG.md`, `README.md` (root) | § Unreleased under Changed; badge colours | ISC-242 |
| `specs/constitution.md` | G-FE-01 row cleared, Gates table gains the browser tier | ISC-228 |

## Interfaces

| Contract | Before | After | Callers |
|----------|--------|-------|---------|
| route `data` | `{measure?, fill?, closeTo?}` | plus `section: Section` on the seven top-level routes, inherited by `:id` children | `AppShell.leaf()` |
| `AppShell` host | `class="shell"` | plus `[attr.data-section]` | `tokens.css` selectors, nothing in TypeScript |
| `core/toast/toast.model.ts` | `TOAST_TONE_CLASS` | plus `TOAST_LINK_CLASS: Record<ToastTone, string>` | `toast-stack.html` |
| `RankedBarChart` | series colour fixed to primary and accent | the second series' tone is an input with the old value as default | `applications-panel` |
| `BrandMark` | `INTRINSIC` = 116×128 from the PNG | from the SVG's viewBox | `app-header` |
| `build-favicon.sh` | input `public/logo.png`, a pixel box for the spout | input `brand/mark.svg`, element ids | the operator, by hand |

## Risks

| Risk | Blast radius | Early warning | Mitigation |
|------|--------------|---------------|------------|
| the Angular builder does not inject `styles.css` into browser-mode specs | every contrast row | the Stage 0 smoke spec reads `--color-primary` as empty | the browser setup file links the built stylesheet |
| the builder refuses `node:fs` in a unit spec | the colour guard | first run of `theme-colors.spec.ts` | the bun script alternative in § Stack Decisions |
| thirteen hues on one wheel do not fit the ΔE floors | the section palette | ISC-225 red | lower chroma on the sections, or move the signal to an unused region |
| new font metrics break widths measured against Manrope (36rem list, nav label breakpoint, 320 px bar) | every split view, the nav | overflow probe at 320 and at 1439/1440 | re-measure `--lg-list-w`, `--lg-detail-w`, `--lg-runs-w` and the breakpoint in Stage 4 |
| a soft link on a soft alert tint has no visible boundary | the toast link | ISC-232's boundary ratio | the outline variant of the link |
| Tailwind emits no class it did not see literally | every new variant | `rg -c btn-soft frontend/dist/**/*.css` after a build | literal maps and literal template classes only |
| a "more vivid" value leaves the sRGB gamut and canvas clamps it differently from CSS | every hex comment, the charts | ISC-222 red | keep every value in gamut |
| the `frontend/CLAUDE.md` budget | the records | `WorkingNotesStaySmallTest` | one line; the reasoning goes to the decision record |
| uncommitted edits in `README.md`, `CLAUDE.md`, `docs/*` by the operator | Stage 6 | `git status` before Stage 6 | merge, never overwrite |
| the mask's box changes with an SVG source | the header, `brand-mark.spec.ts` | the spec's width assertion | derive the box from the viewBox and update the one line |

## Conformance Impact

| Baseline entry | Effect |
|----------------|--------|
| G-FE-01 browser tier and contrast (grandfathered) | **cleared** by ISC-228, ISC-226 and ISC-227: the target exists, the contrast spec runs in both themes, the row and the Gates table are rewritten at Stage 6 |
| G-FE-02 i18n parity (cleared 2026-09-23) | left; no catalog key changes |
| FE-TST-05 coverage as a ratchet (grandfathered) | left; the browser tier reports no coverage and the ratchet is not this spec's work |
| BE-ARCH-01..03 vertical slices (not measured) | left; nothing under `backend/` changes |
