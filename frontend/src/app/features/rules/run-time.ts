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

/**
 * The instant a run finished, as a person places it: a time of day when it was today, and a date
 * with it otherwise. "Two hours ago" says how stale the numbers are and not when the run was, and
 * a bare "23:00" under a run from last night would read as tonight.
 */
export function formatFinishedAt(finishedAt: string, locale: string, now: Date = new Date()): string {
    const at = new Date(finishedAt);
    const sameDay = at.toDateString() === now.toDateString();
    return new Intl.DateTimeFormat(locale, sameDay ? {timeStyle: 'short'} : {dateStyle: 'short', timeStyle: 'short'}).format(at);
}
