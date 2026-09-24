---
spec: 007-anchor-navigation
plan: plan.md
updated: 2026-09-24
---

# Tasks 007 — Anchor navigation on the long screens

**Purpose:** atomic, checkable steps. Each task hangs on exactly one claim ID from
`spec.md`. This file defines nothing, it decomposes.

## Legend

`[P]` = parallelizable. `(after: T…)` = must run after that task.

`[P]` was derived from `IsaFrontier.ts frontier` on 2026-09-24: only ISC-275 is takeable, and its two tasks touch
different files. The four screens are parallel with one another once the component exists, but each names its
own files; the record waits for all of them.

## Tasks

- [x] T1 · ISC-275 · [P] — `--lg-anchor-w` beside the list widths · `frontend/src/styles/tokens.css`
- [x] T2 · ISC-275 · [P] — the component: `sections` and `label` inputs, a `nav` of anchors, sticky column reading the two tokens · `frontend/src/app/shared/anchor-rail/anchor-rail.ts`, `anchor-rail.html`, `anchor-rail.css`
- [x] T3 · ISC-275 — the spec: three sections render three anchors; the stylesheet names both tokens (after: T1, T2) · `frontend/src/app/shared/anchor-rail/anchor-rail.spec.ts`
- [x] T4 · ISC-279 — the marker on `--lg-section`, nothing else; the ISC-230 probe row grows by the file (after: T2) · `frontend/src/app/shared/anchor-rail/anchor-rail.css`, `specs/007-anchor-navigation/spec.md`
- [x] T5 · ISC-280 — accessible name from the input, `tabindex="-1"` headings focused on navigation, no focus return (after: T2) · `frontend/src/app/shared/anchor-rail/anchor-rail.ts`, `anchor-rail.spec.ts`
- [x] T6 · ISC-281 — the empty input renders nothing; the rg over shortlist and pipeline (after: T2) · `frontend/src/app/shared/anchor-rail/anchor-rail.spec.ts`
- [x] T7 · ISC-277 — the `IntersectionObserver` sets `aria-current` on the link of the section in view (after: T2) · `frontend/src/app/shared/anchor-rail/anchor-rail.ts`
- [x] T8 · ISC-278 — below 48rem the chip row under the page header (after: T2) · `frontend/src/app/shared/anchor-rail/anchor-rail.css`
- [x] T9 · ISC-276 — analytics: sections declared, heading ids, `scroll-margin-top`, the two-column layout; the spec (after: T3) · `frontend/src/app/features/analytics/analytics.html`, `analytics.css`, `analytics.ts`, `analytics.spec.ts`
- [x] T10 · ISC-277 — the hand-over measured on analytics through `VerifyViewport.ts` (after: T7, T9) · (evidence only)
- [x] T11 · ISC-276 — rules, the same shape (after: T9) · `frontend/src/app/features/rules/rules.html`, `rules.css`, `rules.ts`, `rules.spec.ts`
- [x] T12 · ISC-276 — sources, the same shape (after: T9) · `frontend/src/app/features/sources/sources.html`, `sources.css`, `sources.ts`, `sources.spec.ts`
- [x] T13 · ISC-276 — review, the same shape, the rail as a third column above 64rem (after: T9) · `frontend/src/app/features/review/review.html`, `review.css`, `review.ts`, `review.spec.ts`
- [x] T14 · ISC-278 — `VerifyViewport.ts` at 320 and 375 on the four screens (after: T8, T11, T12, T13) · (evidence only)
- [x] T15 · ISC-282 — the record and the changelog; `WorkingNotesStaySmallTest` (after: T14) · `docs/decisions/frontend-design-system.md`, `CHANGELOG.md`

## Probe Mapping

| Task | Claim | Probe (from `spec.md` § Test Strategy) |
|------|-------|----------------------------------------|
| T1–T3 | ISC-275 | render with three sections; rg for the two tokens |
| T4 | ISC-279 | `rg -l 'var\(--lg-section\)'` at four files |
| T5 | ISC-280 | activate a link, read `document.activeElement` |
| T6 | ISC-281 | rg over shortlist and pipeline; render with no sections |
| T7, T10 | ISC-277 | `VerifyViewport.ts` scroll past the second section |
| T8, T14 | ISC-278 | `VerifyViewport.ts` at 320 and 375 |
| T9, T11–T13 | ISC-276 | the four screen specs |
| T15 | ISC-282 | `WorkingNotesStaySmallTest`; rg over the records |
