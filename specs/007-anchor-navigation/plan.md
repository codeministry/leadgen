---
spec: 007-anchor-navigation
type: feature
status: draft
updated: 2026-09-24
---

# Plan 007 — Anchor navigation on the long screens

**Purpose:** how the claims in `spec.md` get built. The what lives there; this file
holds no acceptance criterion.

## Approach

The component first, on one screen, then the other three. `shared/anchor-rail` is built with its
spec (nav, anchors, sticky column, active marker, focus landing) and wired into analytics, the
screen with the most sections, and reviewed in the browser at 1440 and 375 before rules, sources
and review follow the same shape. The observer's hand-over is measured through the live-lifecycle
verifier once, on analytics, because a backgrounded tab reports zero observer callbacks and reads
exactly like a broken feature (the trap `frontend/CLAUDE.md` records).

The obvious alternative was a rail per screen; it was not taken because four copies of a sticky
column and an observer disagree the first time one is edited.

## Affected Files and Modules

| Path | Change | Claim |
|------|--------|-------|
| `frontend/src/app/shared/anchor-rail/anchor-rail.ts`, `.html`, `.css`, `.spec.ts` (new) | the component: sections input, nav, sticky column, observer, chip row below 48rem | ISC-275, ISC-277, ISC-278, ISC-279, ISC-280, ISC-281 |
| `frontend/src/styles/tokens.css` | `--lg-anchor-w` | ISC-275 |
| `frontend/src/app/features/analytics/analytics.html`, `.css`, `.ts` (+ spec) | sections declared, headings with ids and `scroll-margin-top`, the two-column layout | ISC-276 |
| `frontend/src/app/features/rules/*`, `features/sources/*`, `features/review/*` (+ specs) | the same | ISC-276 |
| `frontend/public/i18n/en.json`, `de.json` | `nav.sections` name and the section keys where none exist | ISC-280 |
| `docs/decisions/frontend-design-system.md`, `CHANGELOG.md` | the record | ISC-282 |

## Interfaces

| Contract | Before | After | Callers |
|----------|--------|-------|---------|
| `AnchorRail` | — | `sections = input<readonly {id: string; key: string}[]>()`, `label = input<string>()` | the four screens |
| section headings | plain `h2` | `id` + `tabindex="-1"` + `scroll-margin-top: var(--lg-sticky-top)` | the rail's anchors |

## Risks

| Risk | Blast radius | Early warning | Mitigation |
|------|--------------|---------------|------------|
| the observer never fires in a backgrounded tab and the marker reads as broken | the review | `VerifyViewport.ts check` | measure through the verifier only |
| the sticky column collides with the review screen's own two-pane layout | review | the review spec's layout assertions | the rail is a third column only above 64rem on review; a chip row below |
| the column eats width the analytics panels need | analytics at 1280 | overflow probe | 11rem, and `panel-wide` panels keep their minimum |

## Conformance Impact

| Baseline entry | Effect |
|----------------|--------|
| FE-TST-05 coverage as a ratchet (grandfathered) | left |
