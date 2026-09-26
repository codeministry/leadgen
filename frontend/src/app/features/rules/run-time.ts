/**
 * Run times as a person reads them. Kept beside the run's other pure helpers rather than inside a
 * component, the way `formatElapsed` sits beside `FlowNode` — both are testable without rendering
 * anything, and this one outlived the header it was written for (see the master's 2026-09-26
 * decision on retiring `lg-run-header`).
 */
/**
 * The instant a pass started, as a time of day a person reads at a glance ("08:00", "8:00 AM")
 * rather than a calendar distance.
 *
 * `AgoPipe` was tried here first and reads wrong: its coarsest and only unit is a whole day, so
 * a pass that started ten minutes ago says "since today" — true, and useless for a header whose
 * entire point is showing how long a *live* pass has been running. The dashboard's own
 * `runStartedAt` (`dashboard.ts`) already solved this the same way for the same sentence key —
 * a locale-formatted time of day, no date — so this repeats that fix rather than inventing a
 * second one, kept beside the component and given its own cases so each is testable without
 * rendering anything, the way `formatElapsed` sits beside `FlowNode` (ISC-412's precedent).
 */
export function formatStartedAt(startedAt: string, locale: string): string {
    return new Intl.DateTimeFormat(locale, {timeStyle: 'short'}).format(new Date(startedAt));
}
