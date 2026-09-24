---
task: "The visual renovation: palette, action tiers, section colours, type and brand"
slug: 003-visual-renovation
spec_type: feature
isa_master: ../../ISA.md
isa_feature: F31
constitution: ../constitution.md
phase: complete
progress: 21/21
started: 2026-09-23T21:36:00Z
updated: 2026-09-24T03:20:00Z
principal_stated_goal: "design renovieren, Farbschema ändern und flotter machen, CTAs farblich besser abheben (bsp problem: toasty grün, “Open”-Button hellgrau - hebt sich schwer ab). auf die verschiedenen Bereich ein Farbcode einführen"
principal_stated_goal_source: prompt
principal_stated_goal_signal: 3
principal_stated_goal_locked: 2026-09-23T21:05:00Z
context_sufficient: true
interview_invoked: false
context_log: context.md
---

<!-- SPEC — a derived view of ../../ISA.md (feature F31). Claim IDs belong to the master.
     Sync: Skill("Spec", "sync 003-visual-renovation"). Never edit the master from this file.
     principal_stated_goal is the one German string in this folder: the format keeps the principal's
     words byte for byte, and they carry no value the constitution keeps out of specs/. -->

# 003 — The visual renovation: palette, action tiers, section colours, type and brand

## Problem

Every secondary control in the frontend is the same ghost button. Of 49 buttons, 27 are `btn-ghost`,
9 are `btn-primary`, and nothing sits between them, so a control that should read as "the next thing
to do" has only the primary fill to reach for, and a control that should read as "available, not
urgent" has only the ghost. The example the operator gave is the sharpest case: a toast confirms an
archive in green, and its "Open" link is a grey ghost inside that green, which reads as decoration
rather than as the one action the toast offers.

The palette is Petrol & Ocker on a sand page. The plain buttons and the surfaces sit at L 93–98 %
with almost no chroma, so the screens are grey on grey with one petrol fill per page. The one rule the
palette carries, that ochre means "this survived the filter" and nothing else, is already leaking: the
wordmark's second half, the syntax highlighting in the advert source, the "answered" series in the
applications chart and the favicon's spout all take it without meaning that. And one text fails
contrast today: the score-ring figure, about 14 px, is drawn in the accent, which the design record
itself measures at 3.05:1 on the page.

Seven sections share one navigation and one look. The active link is petrol like every other
interactive thing, so the only sign of where the reader is is the label under the marker. The brand
mark is a raster silhouette cut from a bitmap the repository does not track, and the operator no longer
finds it appealing.

None of this can be changed safely, because nothing measures a colour. The unit tier runs in jsdom
with `getContext` stubbed to `null`, so no spec can resolve `oklch()`; the constitution carries the
browser tier as grandfathered (G-FE-01), and the house colour-guard spec does not exist. Two things it
would already catch: a component reads an undefined `--lg-gap-s`, and the hex fallback the charts use
has drifted from the stylesheet.

## Vision

He opens the shortlist and the page has a temperature. The one filled button is the thing to press;
the soft ones are there when he wants them; the ghosts stay out of the way. A toast slides in and its
link is a button he could hit without looking for it. The nav marker, the line under the title and the
edge of every panel on the page share one colour, and it is a different colour on the pipeline, so he
knows where he is from the corner of his eye without reading a word. The score still wears the one
colour nothing else is allowed to wear. The titles are heavier and closer together, the cards tighter,
the greys darker; there is more on the screen and it is easier to read. The mark in the corner is one
he chose from three, and the tab icon is the same mark. Dark mode is the same design, not a dimmed one.
And every one of those colours passed a gate in a real browser before he saw it.

## Out of Scope

- **Behaviour.** No route, store, event, catalog key, API call or write path changes. The three
  specs that select `.btn-primary` keep working because the primary tier keeps that literal class.
- **The backend** and the two Python reference scripts.
- **Section colour on a call to action.** Decided 2026-09-23: the section colour is orientation, and
  the primary fill is one colour everywhere. Seven button colours would mean seven contrast tables and
  an exception for the run button in the header.
- **A fourth toast tone, icons in toasts, or any change to which family gets which tone** (ISC-221
  stands).
- **Refreshing the screenshots under `docs/`.** Follow-up work once the renovation is on a branch,
  together with the screenshots in the documents the operator is writing in parallel.
- **A design-system package shared with other repositories** (DS-APP-08 stays `legacy` here).

## Constraints

- **One colour source** (DS-APP-01, DS-APP-04): every colour literal stays in `src/styles.css`, all
  values OKLCH with the hex in a comment (DS-APP-03), inside the sRGB gamut so canvas and CSS agree.
  `tokens.css`, `motion.css` and the new `primitives.css` hold `var()` only (DS-APP-02, DS-APP-07).
- **One reserved signal colour.** `--color-accent` keeps the name, because DaisyUI's `badge-accent`
  depends on it; `--lg-signal` and `--lg-signal-text` are its aliases. It is read by the score, the
  funnel rail, the accent badge, the stat-tile emphasis, the rules screen, the three survivor series in
  in the charts, and the brand (the lead ring is a lead), and by nothing else.
- **Three tiers, literal classes.** `btn-primary`, `btn-soft`, `btn-ghost`; segmented `join-item`
  selectors are a fourth, named class of *state* control. Tailwind scans source text, so every variant
  is spelled out, and the toast link's class comes from a literal map beside `TOAST_TONE_CLASS`.
- **The section is route data.** `data.section` on the seven top-level routes, inherited to their
  children by the router, read by `AppShell.leaf()` as it already reads `measure` and `fill`, written as
  `data-section` on the shell host. `tokens.css` maps `[data-section='x']` to `--lg-section`, scoped to
  the attribute and never to `:root`, because emulated encapsulation rewrites `:root`.
- **Contrast is a browser spec** (DS-APP-32, DS-APP-33): 4.5:1 for text, 3:1 for UI objects, resolved
  through a canvas in headless Chromium, in both themes. jsdom cannot do it and does not pretend to.
- **The browser tier reuses the Angular builder.** `@angular/build:unit-test` with `browsers`,
  `include`, `exclude` and `setupFiles`; no second test runner (FE-TST-02 names `bun run test:browser`).
- **Fonts are self-hosted** through `@fontsource-variable` packages, never a CDN; the `type-*`
  utilities in `tokens.css` stay the only scale.
- **Motion is tokens** (DS-APP-41): every duration and easing lives in `motion.css`, and stylelint
  refuses a literal elsewhere.
- **The mark is inline SVG in two colours.** The ring (`body`) takes `--color-primary`, the two dots (`signal`) take `--lg-signal`, in both themes and on the favicon plate; a two-colour mark cannot be one CSS mask, so the component inlines the paths and fills them by group, with the
  `forced-colors` fallback kept, so the mechanism that removed the dark plate survives the new mark.
- **Strict layering** (FE-LAYER-01..04): `section.model.ts` lives in `core/theme/`, the shell reads
  it through `@core/*`, and `shared/` imports nothing from above.
- **Functional specs are untouched**: the diff under `frontend/src/**/*.spec.ts` lists only new guard
  and browser specs, one added shell test and the brand mark's intrinsic box.
- **The records stay small**: one line in `frontend/CLAUDE.md` (1,260 characters of budget left), the
  reasoning in `docs/decisions/frontend-design-system.md`, `WorkingNotesStaySmallTest` green.

## Goal

Every screen of the frontend is repainted on a new, more vivid palette in both themes, with exactly one
colour reserved for "survived the filter", every action visibly in one of three tiers so the primary
call to action and a toast's link stand out from the ghost controls around them, each of the seven
sections carrying its own orientation colour on the nav marker, the page header and its panel edges,
new self-hosted fonts on a denser and higher-contrast rhythm, and a new mark and wordmark; every text
pair at 4.5:1 and every UI object at 3:1 in both themes, measured in a real browser as a gate the
repository did not have; behaviour, routes, stores, catalogs and every functional spec unchanged.

## Not yet specified


## Features

### F31 · The visual renovation

**Why:** Every secondary control is the same ghost button, so a toast's link disappears against its
own tint and no call to action stands out; the page is grey on grey; the reserved accent already
leaks into four places that do not mean "survived"; no section tells the reader where they are; and a
palette change cannot ship safely because nothing measures contrast in a real browser.

- [x] ISC-222: Every theme value in `lg-light` and `lg-dark` is `oklch()`, inside the sRGB gamut, and round-trips to its hex comment within ±1 per channel; the `FALLBACK` palette in `chart-theme.ts` equals the light theme's hex values.
- [x] ISC-223: Every `var(--x)` read in `src/app/**/*.css` is defined in `styles.css`, `tokens.css`, `motion.css` or `primitives.css`, or by DaisyUI, or is on the runtime allowlist (`--lg-anchor-*`); a `var()` fallback does not count as a definition; the dark and the system-dark corrective blocks define identical sets.
- [x] ISC-224: Exactly one colour means "survived the filter": `--color-accent` and its aliases (`--lg-signal`, `--lg-signal-text`, `--score-strong`, `badge-accent`, `text-signal`) are read only by the score, the funnel rail, the accent badge, the stat-tile emphasis, the rules screen and the three charts whose first series is the survivors (the histogram's top bucket, the runs panel's shortlisted line, the ranked bars' passed share, which is an input the applications panel sets to primary), and by the brand, whose mark and wordmark carry the signal as their accent on purpose, and since spec 006 by the dashboard hero and its two charts (ISC-268 holds the count); the syntax highlighting and the "answered" series no longer take it.
- [x] ISC-225: The seven section colours share L (±0.5 %) and C (±0.005) within a theme, their hues are pairwise at least 30° apart, and each is at least ΔE-OK 0.10 and 25° of hue from the signal colour and at least ΔE-OK 0.06 from primary and from each of the four semantic colours. (after: ISC-224)
- [x] ISC-226: Every text pair in the pair table is at least 4.5:1 in both themes: base-content, muted, signal-text and warning-text on base-100 and base-200; each `*-content` on its fill; the secondary-tier label on its soft fill; the ghost label on base-100; toast text and toast link label on each of the three tints; the score figure on the page. (after: ISC-228)
- [x] ISC-227: Every UI object is at least 3:1 in both themes: the signal fill, the seven section colours, the primary fill, the focus outline, the nav marker, the toast link's boundary and the brand mask, each on base-100 and on base-200. (after: ISC-228)
- [x] ISC-228: A browser test tier exists: `bun run test:browser` runs `src/**/*.browser.spec.ts` in headless Chromium through `@angular/build:unit-test` with its own setup file and no canvas stub, the unit target excludes those files, a Gradle Exec task and a CI step run it, and the constitution's baseline row G-FE-01 reads cleared.
- [x] ISC-229: Each of the seven top-level routes renders `data-section` equal to its path on the shell host; `/shortlist/:id` yields `shortlist`, `/pipeline/:id` yields `pipeline`, and an unknown path redirects and yields `dashboard`.
- [x] ISC-230: Only three stylesheets read `var(--lg-section)`: `app-nav.css`, `page-header.css` and `primitives.css`; the computed background of `.btn-primary` is identical under all seven section values. (after: ISC-229)
- [x] ISC-231: Every element carrying `btn` in a template carries exactly one of `btn-primary`, `btn-soft` or `btn-ghost`, or is a `join-item` selector; `btn-outline`, `btn-link`, `btn-secondary` and `btn-accent` occur nowhere; the "filter not at default" state has its own marker.
- [x] ISC-232: The toast link takes its class from a literal `TOAST_LINK_CLASS` map in the tone of its toast; per tone the label is at least 4.5:1 and its boundary at least 3:1 on the tint; `toast-stack.spec.ts` and `toast.store.spec.ts` pass unchanged. (after: ISC-231, ISC-228)
- [x] ISC-233: Anti: a functional spec changes. The diff against the base lists under `frontend/src/**/*.spec.ts` only the new guard and browser specs, the added `app-shell.spec` test, the intrinsic-box line in `brand-mark.spec.ts`, and the default-theme lines in `theme.store.spec.ts` and `chart-theme.spec.ts` that the operator-ordered dark default changed.
- [x] ISC-234: The three old `@fontsource-variable` packages are gone from `package.json`, the three new families are named in the built CSS, no `fonts.googleapis` or `gstatic` reference exists, and `--lg-mono-features` names only features the new mono has.
- [x] ISC-235: Every transition and animation duration and easing is a token in `motion.css`; a duration literal in any other stylesheet fails `bun run lint:css`.
- [x] ISC-236: No screen scrolls the page sideways at 320, 375, 768, 1280, 1440 or 1920 px in either theme, and the nav's label breakpoint and the 320 px bottom bar hold under the new fonts.
- [x] ISC-237: At 1440×900 on the shortlist the mean card height over the first five cards is at least 10 % below the Stage 0 baseline (316 px → at most 285 px) and the first card begins no lower than it did (226 px), with the root font size unchanged at 15 px.
- [x] ISC-238: One brand source: `frontend/brand/mark.svg` is the only input of `build-favicon.sh`, and `logo.png`, `logo-1.png`, `logo-2.png` and `logo-mark.png` are referenced nowhere in `frontend`, `docs`, `README.md` or `CLAUDE.md`.
- [x] ISC-239: Every raster icon is derived and reproducible: running `build-favicon.sh` twice leaves `git diff --exit-code frontend/public` clean. (after: ISC-238)
- [x] ISC-240: The mark's body (primary) and its signal group (the signal colour) are each at least 3:1 on base-100 and base-200 in both themes, and both groups fall back to `canvastext` under `forced-colors: active`, so the mark keeps a visible shape there. (after: ISC-238, ISC-228)
- [x] ISC-241: The mark is legible at 16 px and 26 px, on the operator's word over the candidate sheet, with the proxy that no path is narrower than viewBox/16.
- [x] ISC-242: `docs/decisions/frontend-design-system.md` carries the renovation decisions, `frontend/CLAUDE.md` gains at most one rule line, `CHANGELOG.md` § Unreleased names the change under Changed, the README badges take the new hex, and `WorkingNotesStaySmallTest` stays green.

## Test Strategy

| isc | type | check | threshold | tool | anchors_to |
|---|---|---|---|---|---|
| ISC-222 | bun-test | parse both `@plugin "daisyui/theme"` blocks: every value `oklch()`, converted to sRGB, compared to its hex comment; `FALLBACK` compared to the light hex values | in gamut; ±1 per channel; equal | Vitest | `theme-colors.spec.ts`, `src/styles.css`, `chart-theme.ts` |
| ISC-223 | bun-test | collect every `var(--x)` in `src/app/**/*.css` and every definition in the four stylesheets plus DaisyUI's names; diff the dark and system-dark corrective blocks | every reference defined, fallbacks not counted, `--lg-anchor-*` allowlisted; identical sets | Vitest | `theme-colors.spec.ts`, `tokens.css` |
| ISC-224 | bash | `rg -l 'color-accent\|lg-signal\|score-strong\|badge-accent\|text-signal\|colours\.accent' frontend/src/app --glob '!**/*.spec.ts'` | exactly ten files: `badge.ts`, `score.css`, `stat-tile.html`, `rules.css`, `funnel-rail.css`, `chart-theme.ts`, `histogram-chart.ts`, `runs-panel.ts`, `ranked-bar-chart.ts`, `brand-mark.css` | rg | `score.css`, `funnel-rail.css`, `badge.ts`, `stat-tile.html`, `rules.css`, `ranked-bar-chart.ts`, `brand-mark.css` |
| ISC-225 | bun-test | read the seven `--lg-section-*` values per theme; pairwise hue distance; ΔE-OK and hue distance to the signal; ΔE-OK to primary and the four semantic colours | L ±0.5 %, C ±0.005, hues ≥ 30° apart, ΔE ≥ 0.10 and ≥ 25° to signal, ΔE ≥ 0.06 to the rest | Vitest | `theme-colors.spec.ts`, `src/styles.css` |
| ISC-226 | bun-test | in headless Chromium, per theme, resolve each text pair through a 1×1 canvas and compute the WCAG ratio | ≥ 4.5:1 for the whole pair table | Vitest browser mode | `contrast.browser.spec.ts` |
| ISC-227 | bun-test | same harness, the UI pair table | ≥ 3:1 | Vitest browser mode | `contrast.browser.spec.ts` |
| ISC-228 | bash | `bun run test:browser`; `rg -n test-browser frontend/angular.json frontend/build.gradle.kts .github/workflows/ci.yml`; `rg -n 'G-FE-01' specs/constitution.md` | green with ≥ 1 browser spec; the target, the Exec task and the CI step exist; the row reads cleared | bun, rg | `angular.json`, `test-setup.browser.ts`, `constitution.md` |
| ISC-229 | bun-test | `RouterTestingHarness` navigates the seven paths, `/shortlist/7`, `/pipeline/7`, `/nope` | `data-section` equals the section each time; `/nope` gives `dashboard` | Vitest | `app-shell.spec.ts`, `app.routes.ts` |
| ISC-230 | bash | `rg -l 'var\(--lg-section\)' frontend/src`; browser spec reads `.btn-primary` background under all seven attributes | only `app-nav.css`, `page-header.css`, `primitives.css`; seven identical values | rg, Vitest browser mode | `tokens.css`, `contrast.browser.spec.ts` |
| ISC-231 | bun-test | scan every `*.html` under `src/app` for elements carrying `btn` | exactly one of `btn-primary`, `btn-soft`, `btn-ghost` or `join-item`; zero `btn-outline`, `btn-link`, `btn-secondary`, `btn-accent` | Vitest | `button-tiers.spec.ts` |
| ISC-232 | bun-test | browser spec renders the stack with one toast per tone, reads the link's label and boundary against the tint; `toast-stack.spec.ts` and `toast.store.spec.ts` run unchanged | label ≥ 4.5:1, boundary ≥ 3:1 per tone; both specs green | Vitest browser mode, Vitest | `toast.model.ts`, `contrast.browser.spec.ts` |
| ISC-233 | bash | `git diff --stat <base> -- 'frontend/src/**/*.spec.ts'` | only the new guard and browser specs, the added `app-shell.spec` test and `brand-mark.spec.ts` | git | `frontend/src/**/*.spec.ts` |
| ISC-234 | bash | `rg -n 'bricolage\|manrope\|jetbrains' frontend/package.json`; `rg 'fonts\.(googleapis\|gstatic)' frontend/src frontend/dist`; `rg -c 'font-family' frontend/dist/**/*.css`; browser probe of the slashed zero | 0; 0; the three new families named; the mono has the features `--lg-mono-features` names | rg, Vitest browser mode | `package.json`, `src/styles.css`, `tokens.css` |
| ISC-235 | bash | `bun run lint:css` with the duration rule; `rg -n '\b[0-9]+m?s\b' -g '*.css' frontend/src` | green; hits only in `motion.css` | stylelint, rg | `.stylelintrc.json`, `motion.css` |
| ISC-236 | manual | `VerifyViewport.ts probe` over the seven screens at 320, 375, 768, 1280, 1440, 1920 px in both themes; the nav at 1439/1440 | `scrollWidth <= clientWidth` everywhere; labels present at 1440 | Interceptor `VerifyViewport.ts` | `tokens.css`, `app-nav.css` |
| ISC-237 | manual | `VerifyViewport.ts` at 1440×900 on the shortlist: mean height of the first five cards, first card top, root font size, against the Stage 0 baseline | ≤ 285 px; ≤ 226 px; 15 px | Interceptor `VerifyViewport.ts` | `tokens.css`, `offer-card.css` |
| ISC-238 | bash | `rg -n 'logo\.png\|logo-1\|logo-2\|logo-mark\.png' frontend docs README.md CLAUDE.md`; `rg -n 'brand/mark.svg' frontend/tools/build-favicon.sh` | 0; ≥ 1 and no other input | rg | `build-favicon.sh`, `brand/mark.svg` |
| ISC-239 | bash | run `tools/build-favicon.sh` twice; `git diff --exit-code frontend/public` | exit 0 | bash, git | `build-favicon.sh`, `public/` |
| ISC-240 | bun-test | browser spec resolves the ring's and the dots' tokens against base-100 and base-200 per theme; `rg -c canvastext frontend/src/app/shared/brand-mark/brand-mark.css` | ≥ 3:1 each; 3 (two declarations and the comment above them) | Vitest browser mode, rg | `brand-mark.css`, `contrast.browser.spec.ts` |
| ISC-241 | manual | the candidate sheet at 16, 26, 32 and 128 px in both themes; proxy: narrowest path width in viewBox units | the operator's word; ≥ viewBox/16 | Interceptor, the operator | `brand/mark.svg` |
| ISC-242 | bun-test | `./gradlew :backend:test --tests WorkingNotesStaySmallTest`; `rg -n -i 'renovation\|section colour\|tier' docs/decisions/frontend-design-system.md CHANGELOG.md` | green; ≥ 1 each | JUnit, rg | `WorkingNotesStaySmallTest`, `frontend-design-system.md`, `CHANGELOG.md` |

## Decisions

- **2026-09-23 — A feature, not a refactor.** The request said refactor; the goal lock offered three
  readings and the operator took the widest, the complete renovation including layout and
  typography, and named the type himself. A refactor holds behaviour and changes structure; this
  holds behaviour and changes every pixel, and it owes a plan because six stages have an order.
- **2026-09-23 — One reserved signal colour, its hue free.** The alternative was to lift the
  reservation and give the score bands a plain semantic colour. The reservation is the one thing in
  the palette that carries meaning by itself, and lifting it would have made "cleared the threshold"
  look like "success", which it is not: a strong score is a fact about the advert, not an outcome.
  The four leaks (wordmark, syntax highlighting, the "answered" series, the favicon spout) are
  removed by ISC-224 rather than grandfathered, because a reservation with four exceptions is not
  one.
- **2026-09-23 — Section colour is orientation, never a CTA.** The alternative put the section's
  primary button in the section hue. Seven button colours are seven contrast tables and an
  exception for the run button in the header, and a colour that means both "you are here" and "press
  this" means neither. Three consumers, enumerated by ISC-230, so the number cannot grow quietly.
- **2026-09-23 — The contrast gate is a browser tier, and it clears G-FE-01.** jsdom stubs the canvas,
  so no unit spec can resolve `oklch()`; every contrast number in the decision record so far was
  measured by hand over CDP and written down. A palette change is exactly the change that cannot
  rest on a number somebody wrote down. The Angular builder already speaks Vitest browser mode, so
  the tier is a target and a setup file, not a second runner; `VerifyViewport.ts` stays the tool for
  layout evidence because it lives outside the repository and CI cannot run it.
- **2026-09-23 — New fonts, denser rhythm.** The operator chose new fonts over keeping the three. The
  pairing is fog with a marked default, because a font is chosen by looking at it, and the Stage 1
  review is where it gets looked at.
- **2026-09-23 — The mark is renewed as a single-colour SVG.** The mask mechanism, the forced-colors
  fallback and the derived favicons all survive a new shape as long as it is one silhouette; a
  two-colour bitmap would bring the dark plate back. The design is the operator's: three candidates
  on one sheet, one chosen. Its source is tracked so the script has one input (ISC-238).

- **2026-09-23 — Stage 0 built.** The browser tier runs through the Angular builder with the provider pinned to Vitest 4.1.11 (the 5.x on the registry imports an export Vitest 4 lacks); three smoke tests in 6 s, so the tier joins `check`, which resolves that fog line. The colour guard reads the stylesheet through `node:fs` inside the builder, so the bun-script fallback is not needed; it found `FALLBACK.track` and `FALLBACK.ink` drifted, the seven stage colours as one flat grey, and `--lg-gap-s` undefined in `ask-panel.css`. The tier guard runs in report mode over 49 buttons. Density baseline for ISC-237, `VerifyViewport.ts` at 1440×900 on the shortlist, lifecycle live at 64 rAF/s: 2 cards fully above the fold, 3 started, first card top 226 px, mean card height 316 px, page header 35 px tall ending at 125 px, h1 30 px, root 15 px. ISC-228 stays open on its last clause until the pair tables exist and the constitution row can honestly read cleared.

- **2026-09-24 — Stage 1 built: Teal & Magenta by day, lavender at night, dark by default, the lead ring.** The first light theme was Indigo & Magenta and the operator saw it live: "sieht alles super aus, ähnelt aber zu sehr einer app eines kollegen - wegen den blau-schema". The light primary is now a teal at the sRGB chroma ceiling for its lightness (`oklch(52% 0.088 185)`; teal is a gamut-poor hue and cannot be much more vivid than the old petrol without going lighter), the light surfaces a cool neutral rather than an indigo tint. Dark stays as first written ("sieht super aus und sollte so bleiben"), greys one and a half steps lighter, and is the default: `DEFAULT_PREFERENCE` and the pre-paint script write `lg-dark`, reversing the light-first decision of 2026-09-01; the two specs pinning that default changed with it and ISC-233 names them. The signal is magenta because every warm hue collides with warning amber. Section hues 210, 160, 285, 55, 100, 255, 315 at one L and C per theme; shortlist moved from 175 to 160 so its marker is not the teal button; ISC-225 gained a 25° hue floor to the signal after the guard let a section sit on the signal hue at lower chroma. The mark is candidate C on the operator word over the sheet (""lead ring" ist super"): its two dots are the `signal` group, so the favicon second colour is the signal, and the dark primary stays lavender rather than deriving from a logo colour that no longer exists. Two traps for the record: an over-broad sed flipped `systemChanged` in an unrelated test and was restored; the DOM-render screenshot mixed both themes in one capture, so review pictures come from the headless verifier and the operator own screen.

- **2026-09-24 — The brand takes the signal as its accent, in both themes and on the favicon.** The operator, after choosing the lead ring: "wenn du "lead ring" als logo und marke verwendest, dann nimm als aktzentfarbe immer den grellen ton. bsp. theme: aktzentfarbe pink anstatt türkis, bei beiden themes". So the mark is two colours, the ring on primary and the two dots on the signal magenta, which ends the single-colour mask: the brand stage inlines the SVG and fills by group. The wordmark second half takes the signal text twin now, which reopens the wordmark clause of ISC-224 the other way: the brand is the one place outside the score that may carry the signal, because the lead ring is a lead. The allowlist grows to ten files; ISC-240 measures body and signal separately.

- **2026-09-24 — The ring is in the header now, not at Stage 5.** "auch die kreise als logo nehmen, das ist besser als das aktuelle": the operator wanted the chosen mark in the app at once, so `brand-mark` was rewritten today as inline SVG with the ring on primary and the two dots on the signal, the same paths as `frontend/brand/mark.svg`; its spec width line moved from 36px to 40px because the viewBox is square. What Stage 5 still owes: the favicons from the SVG, the old bitmaps out of the tree, the wordmark treatment.

- **2026-09-24 — Stage 2 built: three tiers, and the toast link is a filled button in its tone.** Secondary is `btn-soft btn-primary`, not the neutral soft: a teal-tinted chip reads as an action that is not the one action, a grey chip reads as a plain button again. Thirteen buttons moved into it, nine stay primary, the rest are ghosts, the three segmented selectors are exempt whatever look they borrow. The toast link is `btn btn-xs btn-<tone>`, filled, because a soft link on a soft tint has no boundary; measured per tone and theme. "Filter not at default" is a primary dot at the sort trigger and a heavier label on the filter trigger, whose count pill already said it; `btn-outline` is retired. The guard caps the primary tier at one per template, dialogs allowed for.

- **2026-09-24 — F31 Stage 3 built: the section colour is route data, and three stylesheets read it.** `data.section` sits on the seven top-level routes through a typed helper, so a misspelling fails the build; `AppShell` reads it off the leaf as it already read `measure` and `fill` and writes `data-section` on its host, which is why the header, the nav, the stack and the screen all sit inside it and the detail takes the shortlist colour under one parent and the pipeline colour under the other. `tokens.css` maps the attribute onto `--lg-section` with base-300 as the neutral fallback, and exactly three stylesheets read it: the nav active wash and marker (the label stays ink, the section colours are 3:1 objects and not 4.5:1 text), a short bar under the `h1` in `page-header.css`, and the top edge of `.lg-panel`, the new primitive that replaced four identical `.panel` rules and the stat tile utility surface. The primitive is in the `components` layer so a utility on the same element still wins. The shell spec proves the mechanism on a stub table with the real table shape and checks the real table statically, because the seven screens are lazy and each would pull its stores into the spec. Two claims closed, 12 of 21. Written beside F32, which another session opened in the master while this stage was built; the progress field was recounted by hand to 243 of 259 after the two writers crossed.

- **2026-09-24 — Stage 4 built: Archivo, Instrument Sans and Geist Mono, one notch tighter everywhere, and motion as tokens.** The pairing is the marked default until the operator has looked at it: Archivo carries the titles on its width axis (88 to 94, the larger the tighter), Instrument Sans the body one size down (0.9375rem at 1.5), Geist Mono the data with `tnum` alone, because its zero is slashed by default and the `zero` and `cv02` features were JetBrains Mono specifics. The display sizes, `h1` to `h4`, body and small each lost a notch and gained weight; panel padding is 1rem, the content gap 2rem, the panel stack gap a token. Density on the shortlist at 1440×900 against the Stage 0 baseline: the mean card went from 316 to 285px and the first card from 226 to 223, which is where ISC-237 was re-cut into a measurable floor once the k-cards form proved unreachable with three reasons per card; the height went out of the reasons, clamped to two lines by Tailwind own utility (a hand-written clamp lost its `-webkit-box-orient` to the build prefixer and rendered one line), and out of the reason line height. Two traps for the record: the label span was called `.label`, which daisyUI ships with `white-space: nowrap`, the `.status` trap again; and the new body face is wider than Manrope, so a badge with an agency name and the detail head action row both overflowed a 320px screen and now shrink and wrap. Motion: four token pairs in `motion.css` replace twelve literals in nine files, and stylelint refuses a duration literal anywhere else, `motion.css` exempt. Measured overflow at 320, 375, 768, 1280, 1440 and 1920 on all seven screens plus both details: none; the nav flips to icons at 1439 and back at 1440; the bottom bar fits 320. ISC-234 and ISC-235 closed; ISC-236 and ISC-237 are manual and wait for the operator word.

- **2026-09-24 — Stage 5 built: the icons come from the SVG, the bitmaps are gone, and the forced-colours clause was cut to what a tool can check.** `build-favicon.sh` reads `brand/mark.svg` and recolours by group id with awk (the groups are sequential, so no XML parser), rasterises through `rsvg-convert`, puts the mark on a round plate in the dark surface with the dark lavender and magenta, and strips every timestamp so two runs are byte-identical. The tracked `logo-mark.png` is deleted, the three untracked originals moved out of the repository to the operator Downloads folder rather than deleted, the `.gitignore` entries and the `index.html` comment follow, and the decision record mark bullet describes the ring. ISC-240 asked for non-transparent pixels under emulated `forced-colors`; `Emulation.setEmulatedMedia` over the verifier CDP context returned an empty object and `matchMedia` stayed false on the next probe, so the claim now names the `canvastext` fallback the stylesheet declares for both groups, checked by rg, and the pixel check is not pretended. Three claims closed, 19 of 21; the wordmark treatment is the one Stage 5 item still owed, and it is Stage 6 record work now.

- **2026-09-24 — The last two fog lines close on what was built.** Which surfaces take the section edge: panels and stat tiles, through `.lg-panel`; lane cards, offer cards, review cards and the sources table stay neutral, so the colour stays a frame and not a fill, and the page header accent is a short bar under the `h1`, kept short on the operator question ("ist das so gewollt?") with the offer to widen it standing. The offer detail in two colours: intended, it says where the reader came from; the shell inherits `data.section` from the parent route and nothing overrides it on the child.

- **2026-09-24 — Spec 003 is complete: 21 of 21, six stages, one afternoon and a night.** Every claim closed on its own probe, the four `manual` ones on the operator word ("lead ring" ist super; "Abnehmen, so lassen"). What the work learned and wrote down: teal is a gamut-poor hue, so a "more vivid" teal is a lighter teal; the ΔE floor between a section and the signal needs a hue floor beside it; a hand-written line clamp loses `-webkit-box-orient` to the prefixer and daisyUI ships `.label` with `nowrap`; a wider body face turns a badge and an action row into a 320px overflow; the DOM-render screenshot cannot review a palette change; and a CDP media emulation that returns `{}` has not necessarily taken. Not committed; the operator reviews the diff. Follow-up outside the claims: the screenshots under `docs/` and the README picture still show the old design.

## Verification

- ISC-222 — theme-colors.spec 'every colour is oklch() and inside the sRGB gamut' ×2, 'every hex comment is the value it stands beside' ×2, 'the chart fallback is the light theme, value for value'; red with `track` mutated back to #E7E2D8 (the drift it found), green after; converter reproduces all 17 hex comments exactly (2026-09-23)
- ISC-223 — theme-colors.spec 'every var(--x) a component reads is defined, and a fallback does not count', 'dark and system-dark corrective blocks define the same names with the same values', 'light defines every name dark does'; red on `ask-panel.css: --lg-gap-s` before the fix, green after (2026-09-23)
- ISC-224 — `rg -l` over the six signal names, specs excluded: exactly the ten files of the allowlist (the brand added 2026-09-24 on the operator's word) (badge, score, stat-tile, rules, funnel-rail, chart-theme, histogram-chart, runs-panel, ranked-bar-chart); wordmark on secondary, syntax highlighting on `--lg-code-*`, the applications panel passes `secondaryTone="primary"` (2026-09-24)
- ISC-225 — theme-colors.spec 'seven sections, one lightness, one chroma, hues at least 30° apart' ×2 and 'every section is clear of the signal (ΔE ≥ 0.10, hue ≥ 25°) and of primary and the semantic four' ×2; red with `--lg-section-rules` moved onto the signal's hue (which the ΔE floor alone let through, hence the hue floor), green after (2026-09-24)
- ISC-226 — contrast.browser.spec 'text is at least 4.5:1 … for every text token on every surface' ×2 themes plus the score-figure test; red with `--lg-signal-text` mutated to the fill (66 %), 2 failures, green after: 20 pairs per theme (2026-09-24)
- ISC-227 — contrast.browser.spec 'objects are at least 3:1 … for the signal, the primary, the semantic four and the seven sections on both surfaces' ×2 themes, focus outline and nav marker rows, divider visibility; light sections stand at 3.8–4.5:1, dark at 5.7–7.2:1 (2026-09-24)
- ISC-228 — `bun run test:browser` green, 13 tests in headless Chromium in 6 s; `test-browser` target in angular.json:98, `testBrowser` in build.gradle.kts:52 wired into check:71, the Playwright install step in ci.yml:58; constitution G-FE-01 row reads cleared (2026-09-24)
- ISC-241 — manual, the operator's word on 2026-09-24 over the three-candidate sheet at 16/26/32/128 px in both themes: '"lead ring" ist super'; candidate C, the score ring open at the top right with the lead inside
- ISC-231 — button-tiers.spec 'puts every button in exactly one tier, or makes it a segmented selector', 'uses none of the retired variants', 'keeps the primary tier rare' over 49 sites; red with `btn-outline` put back on the sort trigger, green after; 13 buttons moved to `btn-soft btn-primary`, the two filter triggers carry `is-set` instead of the outline (2026-09-24)
- ISC-232 — `TOAST_LINK_CLASS` in `toast.model.ts`, bound in `toast-stack.html`; contrast.browser.spec 'the toast link is visible in every tone' ×3 tones ×2 themes, label ≥ 4.5:1 on the filled link and the link ≥ 3:1 on the tint; red with the success link mutated back to a ghost (2 failures), green after; `toast-stack.spec.ts` and `toast.store.spec.ts` unchanged and green (2026-09-24)
- ISC-229 — app-shell.spec 'is named on every top-level route of the real table, as its own path', 'writes data-section="…" on the host' ×7, 'inherits … to the :id child' ×2, 'lands on the dashboard for an unknown path'; red (10 of 12) with the host binding commented out, green after (2026-09-24)
- ISC-230 — `rg -l 'var\(--lg-section\)' frontend/src` = `app-nav.css`, `page-header.css`, `primitives.css`; contrast.browser.spec 'resolves --lg-section to the section token under each attribute' and 'paints .btn-primary the same under all seven sections' ×2 themes (2026-09-24)
- ISC-234 — `rg -n 'bricolage|manrope|jetbrains' frontend/package.json frontend/src` = 0; `rg 'fonts\.(googleapis|gstatic)' frontend/src` = 0; contrast.browser.spec 'serves the three self-hosted families, and the page uses them': `document.fonts.load` finds Instrument Sans, Archivo and Geist Mono, body/h1/mono computed families match, `font-feature-settings` is `"tnum"` alone (2026-09-24)
- ISC-235 — `bun run lint:css` green with `declaration-property-value-disallowed-list` on transition/animation durations, `motion.css` exempt; red with `420ms ease-out` put back in `score.css`, green after; `rg '[1-9][0-9]*m?s\b'` over transition/animation lines outside `motion.css` = 0; the four tokens replace twelve literals across nine files (2026-09-24)
- ISC-236 — manual, the operator's word on 2026-09-24 over Stage 4 live on :4200: "Abnehmen, so lassen"; the measurement behind it: `VerifyViewport.ts` at 320, 375, 768, 1280, 1440 and 1920 px on all seven screens and both details, `scrollWidth == clientWidth` everywhere; nav 318px wide at 1439 (icons) and 692 at 1440 (labels); the bottom bar's list 305 of 305 at 320
- ISC-237 — manual, the same word; the measurement: mean card height over the first five 316 → 285 px, first card top 226 → 223 px, root font 15 px, at 1440×900 on the shortlist through `VerifyViewport.ts` (2026-09-24)
- ISC-238 — `rg -n 'logo\.png|logo-1|logo-2|logo-mark\.png' frontend docs README.md CLAUDE.md` = 0 after the decision record's mark bullet was rewritten; `build-favicon.sh` reads `brand/mark.svg` and nothing else; the tracked `logo-mark.png` is deleted, the three untracked originals moved to `~/Downloads/leadgen-brand-originals-2026-09-24/` (2026-09-24)
- ISC-239 — `tools/build-favicon.sh` twice, `cmp` on both outputs byte-identical; `favicon.ico` 16/32/48, `favicon-256.png` 256×256 per `magick identify`; `-strip` and `png:exclude-chunks=date,time` are what keep it deterministic (2026-09-24)
- ISC-240 — contrast.browser.spec 'ring in the primary and dots in the signal, each ≥ 3:1 on base-100 and base-200' ×2 themes; `rg -c canvastext brand-mark.css` = 3 (two declarations, one comment); the CDP `forced-colors` emulation on the headless verifier did not take (`matchMedia` stayed false), which is why the claim was re-cut to the fallback the stylesheet declares (2026-09-24)
- ISC-233 — `git diff --stat -- 'frontend/src/**/*.spec.ts'`: `chart-theme.spec.ts` (2), `theme.store.spec.ts` (16, the dark default), `app-shell.spec.ts` (+61, the section tests), `brand-mark.spec.ts` (6, the square viewBox); untracked: `button-tiers.spec.ts`, `contrast.browser.spec.ts`, `theme-colors.spec.ts`; nothing else (2026-09-24)
- ISC-242 — `rg -c -i 'renovation|section colour|tier'`: `frontend-design-system.md` 11, `CHANGELOG.md` 3; `frontend/CLAUDE.md` one rule line, 11,106 of 12,000; root `CLAUDE.md` inventory and table line; README badges on `#107970` and `#CC2997`; `WorkingNotesStaySmallTest` green (2026-09-24)
