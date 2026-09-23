/**
 * One line at the edge of the screen, saying what just happened.
 *
 * <p>Three tones, one per action family, so an archive and its restore are told apart at a
 * glance and not by reading. `success` is something brought back, confirmed or moved
 * forward: a restore, a confirmed document, a scored rescore, a status change into any state
 * but the three that close against us. `warning` is something taken off the list or closed
 * against us: an archive, a bulk archive, a rejected document, LOST, REJECTED, EXPIRED. Amber
 * meant "wants attention" elsewhere in this app; here it means "taken away", the operator's
 * choice over the neutral alert (2026-09-23). `info` is news nobody in this browser
 * necessarily asked for: a run beginning or ending, a rescore that is still unscored.
 *
 * <p>Never `error`: every refused write already has a `role="alert"` paragraph beside the
 * control that can retry it, a toast on top would be one failure said twice, and the stack
 * is a polite `status` region in which nothing is an error.
 */
export type ToastTone = 'success' | 'warning' | 'info';

export interface Toast {
    /** Monotonic, the stack's track key and what the timer and the close button name. */
    readonly id: number;
    readonly tone: ToastTone;
    /** A catalog key under `toast.`, never a sentence. */
    readonly key: string;
    /** The title, the count, the state — whatever the key's ICU message reads. */
    readonly params?: Readonly<Record<string, unknown>>;
    /** A configured route the toast points at. Navigation only; a toast never writes. */
    readonly link?: string;
}

/**
 * The DaisyUI tone class per tone, spelled out. Tailwind scans source *text* for class
 * names, so a class assembled from a prefix and the tone at runtime would exist in the DOM
 * and never in the stylesheet — the trap `frontend/CLAUDE.md` records for the badge. Both
 * variants are literals here.
 */
export const TOAST_TONE_CLASS: Readonly<Record<ToastTone, string>> = {
    success: 'alert-success',
    warning: 'alert-warning',
    info: 'alert-info',
};

/**
 * How long a toast stands before it leaves by itself, and how many stand at once.
 *
 * <p>Six seconds because a run toast carries two numbers and a link, and the common
 * four-second default is tuned for "saved". Three at once because the stack sits over the
 * page's corner and a fourth would cover content; the oldest leaves first. Both are the
 * spec's marks and unmeasured — they are here as the one place to change them.
 */
export const TOAST_LIFETIME_MS = 6_000;
export const TOAST_CAP = 3;

let nextId = 0;

/** A fresh toast with the next id. The counter is process-wide, which is all uniqueness needs. */
export function toast(tone: ToastTone, key: string, params?: Toast['params'], link?: string): Toast {
    nextId += 1;
    return {id: nextId, tone, key, params, link};
}
