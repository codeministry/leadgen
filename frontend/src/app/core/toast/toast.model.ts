import {EventInstance} from '@ngrx/signals/events';

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

/**
 * The one thing a toast may offer besides a link: a button that dispatches an event.
 *
 * <p>It carries the event instance itself rather than a creator, so the stack dispatches
 * what it was handed and never learns a payload. A toast with an action has no lifetime —
 * the person decides, and an offer that vanished while they read it is the offer never
 * made — but the close button and the cap still remove it. The first and so far only
 * producer is the update store's "a new version is ready", whose action is the reload.
 * Still never an undo: the event goes to a store that acts under its own rules.
 */
export interface ToastAction {
    /** A catalog key under `toast.`, the button's label. */
    readonly key: string;
    /** The event the button dispatches, made when the toast is raised. */
    readonly event: EventInstance<string, unknown>;
}

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
    /** A button that dispatches an event. Exempt from the timer; see `ToastAction`. */
    readonly action?: ToastAction;
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

/** A fresh toast that offers an action instead of a link, on the same counter. */
export function actionToast(tone: ToastTone, key: string, action: ToastAction, params?: Toast['params']): Toast {
    nextId += 1;
    return {id: nextId, tone, key, params, action};
}

/**
 * The link's class per tone, spelled out for the same reason as the tone class. The link is
 * the one thing a toast offers, so it is the filled button in the toast's own tone rather
 * than a ghost: a ghost inside a tinted alert reads as decoration (the "Open" the operator
 * could not find, spec 003), and a soft button on a soft tint has no boundary to speak of.
 * The tone's content colour on the tone's fill is what `contrast.browser.spec.ts` measures.
 */
export const TOAST_LINK_CLASS: Readonly<Record<ToastTone, string>> = {
    success: 'btn btn-xs btn-success',
    warning: 'btn btn-xs btn-warning',
    info: 'btn btn-xs btn-info',
};
