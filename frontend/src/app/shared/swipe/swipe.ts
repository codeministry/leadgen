import {DestroyRef, Directive, ElementRef, inject, input, output} from '@angular/core';

/** Horizontal travel a touch must make, and win over vertical travel, before the row follows it. */
const SLOP_PX = 10;
/** The threshold's share of the row's width, and its cap in rem. */
const THRESHOLD_SHARE = 0.4;
const THRESHOLD_CAP_REM = 12;
/** How much of the row has to be uncovered before the reveal's content is fully shown. */
const FADE_IN_REM = 3;
/** A fling counts once this much of the row is uncovered, and never before: a flick is not a swipe. */
const FLING_FLOOR_REM = 3;
/** The leftward speed, in px/ms, from which a release counts as a fling. */
const FLING_SPEED = 0.5;
/** How far back from the release the speed is measured: the finger's last motion, not its whole path. */
const VELOCITY_WINDOW_MS = 100;
/**
 * How long after an engaged gesture a click is still taken to be its own. The browser fires it
 * right after the lift or not at all (a touch past its own tap slop makes none); the window only
 * keeps a swallower that found no click from eating a keyboard Enter on the title much later.
 */
const CLICK_GRACE_MS = 400;
/** How much of an open row stays covered at least, so the card is still there to tap closed. */
const OPEN_KEEP_REM = 3;

interface Track {
    readonly pointerId: number;
    readonly startX: number;
    readonly startY: number;
    engaged: boolean;
    /** `[timeStamp, offset]` of the engaged moves within the velocity window of the latest one. */
    readonly samples: [number, number][];
}

/**
 * Where the row is between gestures. `held`: released past the threshold, waiting for the host's
 * `settle`; `open`: held aside on what the reveal's hold needs uncovered, until the host settles
 * again or a tap on the row closes it; `returning`: springing back to 0; `leaving`: sliding off,
 * and holding there until the host takes the row away.
 */
type Phase = 'rest' | 'drag' | 'held' | 'open' | 'returning' | 'leaving';

/**
 * A row that follows a finger to the left (spec 024). Generic on purpose: it knows a pointer
 * stream, an offset and a threshold, and nothing of what a completed swipe means — the host
 * screen decides that on `swiped` and answers with `settle`.
 *
 * <p>Touch only. A mouse, a trackpad or a pen never starts it, at any width: the device's input
 * decides, not the viewport (ISC-492). `touch-action: pan-y` hands vertical panning to the
 * browser, so the list still scrolls natively and the gesture only ever sees the horizontal axis.
 * Under touch the browser captures the pointer to the element it landed on implicitly, so the
 * moves keep bubbling to this host without `setPointerCapture`.
 *
 * <p>Only `pointerdown` is a host listener. Move, up and cancel are attached with
 * `addEventListener` once a touch starts a track and removed when it ends, and the state below is
 * written straight onto the element: a host listener or a signal read by a host binding schedules
 * change detection, and a mouse moving over the list would run it on every move.
 *
 * <p>The state is written onto the host as `--lg-swipe-x` (the offset, px, absent at rest),
 * `--lg-swipe-uncovered` (0 → 1 over the first 3rem uncovered), `.is-swiping` while the row is
 * off its place and `.is-armed` while a release would count. `swipe.css` turns those into the
 * transform, the clip and the reveal; the host screen's stylesheet paints the reveal.
 *
 * <p>A release past the threshold — `min(40 %, 12rem)`, or a leftward fling once 3rem are
 * uncovered — emits `swiped` once and holds the row where the finger left it, armed, until the
 * host answers with `settle` (ISC-493). A release short of it, or a cancelled pointer, springs
 * back (`.is-returning`). `settle('leave')` slides the row off (`.is-leaving`) and writes its
 * height as `--lg-swipe-height`, the length the collapse on the host's leave animation closes.
 * `settle('open')` holds the row aside on what the reveal's `.lg-swipe-hold` needs uncovered plus a
 * rem, keeping 3rem of the row covered, armed and `.is-open`; from there `settle` again, or a
 * touch or click on the row outside its reveal closes it — that click swallowed, and `closed`
 * emitted so the host can forget it (ISC-501).
 *
 * <p>A touch engages only once its horizontal travel passes the 10px slop and beats its vertical
 * travel; until then nothing moves, and a touch that went vertical first is the list's for good.
 * The click that follows an engaged gesture — released, flung or cancelled — is swallowed on the
 * host, so a swipe never opens the offer; a tap without travel still does (ISC-494). While
 * `swipeDisabled` is set a touch starts nothing at all (ISC-497).
 *
 * <p>Markup contract: the reveal is a child with `.lg-swipe-reveal`; every other child moves. A
 * host that settles open marks what the open row uncovers with `.lg-swipe-hold` inside the reveal.
 */
@Directive({
    selector: '[lgSwipe]',
    exportAs: 'lgSwipe',
    host: {
        class: 'lg-swipe',
        '[style.touch-action]': '"pan-y"',
        '(pointerdown)': 'onDown($event)',
    },
})
export class Swipe {
    /** While true a touch on the row engages nothing; it simply scrolls or taps. */
    readonly swipeDisabled = input(false);
    /** Released past the threshold, or flung. The host answers with `settle`. */
    readonly swiped = output<void>();
    /** An open row closed itself, on a tap outside its reveal; a host's own `settle` emits nothing. */
    readonly closed = output<void>();

    private readonly host = inject<ElementRef<HTMLElement>>(ElementRef).nativeElement;

    /** The row's horizontal offset in px: 0 at rest, negative to the left, never positive. */
    private offset = 0;
    private phase: Phase = 'rest';
    private threshold = Number.POSITIVE_INFINITY;
    private rem = 16;

    private track: Track | null = null;
    private readonly move = (event: PointerEvent): void => this.onMove(event);
    private readonly end = (event: PointerEvent): void => this.onEnd(event);
    private returnTimer: ReturnType<typeof setTimeout> | null = null;
    private clickTimer: ReturnType<typeof setTimeout> | null = null;
    /**
     * Capture phase on the host, so it runs before the title's router link and the checkbox below
     * it: the click after an engaged gesture never opens the offer and never ticks a box (ISC-494).
     */
    private readonly swallowClick = (event: Event): void => {
        // A press on an open row's reveal is a button's own click, never the gesture's: the reveal
        // takes no pointer while the finger is down, so the gesture's click cannot land there.
        if (event.target instanceof Element && event.target.closest('.lg-swipe-reveal') !== null) {
            return;
        }
        event.preventDefault();
        event.stopPropagation();
        this.releaseClick();
    };

    constructor() {
        inject(DestroyRef).onDestroy(() => {
            this.untrack();
            this.clearReturnTimer();
            this.releaseClick();
        });
    }

    /** The host decides what a completed swipe means; the directive only moves. */
    settle(how: 'leave' | 'return' | 'open'): void {
        this.untrack();
        if (how === 'return') {
            this.springBack();
            return;
        }
        if (how === 'open') {
            this.open();
            return;
        }
        this.clearReturnTimer();
        this.host.style.setProperty('--lg-swipe-height', `${this.host.offsetHeight}px`);
        this.phase = 'leaving';
        // Off the row's own edge and one gap further, holding the armed state until the row goes.
        this.offset = -(this.host.clientWidth + this.rem);
        this.paint();
    }

    protected onDown(event: PointerEvent): void {
        // A new touch is a new gesture: a click from here on is its own, never the last one's.
        this.releaseClick();
        const phase = this.phase;
        if (phase === 'open') {
            this.onDownOpen(event);
            return;
        }
        if (event.pointerType !== 'touch' || this.swipeDisabled() || this.track !== null) {
            return;
        }
        // A row that counted waits for its host, and a leaving one is gone; a returning one may be caught.
        if (phase === 'held' || phase === 'leaving') {
            return;
        }
        // A touch on the checkbox belongs to the checkbox.
        if (event.target instanceof Element && event.target.closest('.pick') !== null) {
            return;
        }
        if (phase === 'returning') {
            this.clearReturnTimer();
            this.phase = 'rest';
            this.paint();
        }
        this.track = {pointerId: event.pointerId, startX: event.clientX, startY: event.clientY, engaged: false, samples: []};
        this.host.addEventListener('pointermove', this.move);
        this.host.addEventListener('pointerup', this.end);
        this.host.addEventListener('pointercancel', this.end);
    }

    private onMove(event: PointerEvent): void {
        const track = this.track;
        if (track === null || event.pointerId !== track.pointerId) {
            return;
        }
        const dx = event.clientX - track.startX;
        const dy = event.clientY - track.startY;
        if (!track.engaged) {
            const horizontal = Math.abs(dx) > SLOP_PX && Math.abs(dx) > Math.abs(dy);
            if (horizontal && dx < 0) {
                track.engaged = true;
                this.phase = 'drag';
                this.measure();
            } else if (horizontal || Math.abs(dy) > SLOP_PX) {
                // Rightward, or the list is being scrolled: this touch is not a swipe.
                this.untrack();
                return;
            } else {
                return;
            }
        }
        // Minus the slop, so the row does not jump by it on engaging; clamped at 0 with no give.
        const offset = Math.min(0, dx + SLOP_PX);
        this.offset = offset;
        this.paint();
        track.samples.push([event.timeStamp, offset]);
        while (track.samples[0][0] < event.timeStamp - VELOCITY_WINDOW_MS) {
            track.samples.shift();
        }
    }

    private onEnd(event: PointerEvent): void {
        const track = this.track;
        if (track === null || event.pointerId !== track.pointerId) {
            return;
        }
        this.untrack();
        if (track.engaged) {
            this.holdClick();
        }
        // Only a finger that lifted counts; a cancelled pointer (a scroll took over, a call came in) never does.
        if (event.type === 'pointerup' && track.engaged && this.counts(track, event.timeStamp)) {
            this.phase = 'held';
            this.paint();
            this.swiped.emit();
            return;
        }
        this.springBack();
    }

    /** Past the threshold, or flung to the left fast enough once the fling floor is uncovered. */
    private counts(track: Track, releasedAt: number): boolean {
        const uncovered = -this.offset;
        if (uncovered >= this.threshold) {
            return true;
        }
        return uncovered >= FLING_FLOOR_REM * this.rem && this.velocity(track, releasedAt) <= -FLING_SPEED;
    }

    /**
     * The speed in px/ms over the window before the release, with the release itself as the last
     * sample: a finger that stopped before it lifted has no speed left, however fast it came.
     */
    private velocity(track: Track, releasedAt: number): number {
        const release: [number, number] = [releasedAt, this.offset];
        const samples = [...track.samples, release].filter(([t]) => t >= releasedAt - VELOCITY_WINDOW_MS);
        if (samples.length < 2) {
            return 0;
        }
        const [t0, x0] = samples[0];
        const [t1, x1] = samples[samples.length - 1];
        return t1 > t0 ? (x1 - x0) / (t1 - t0) : 0;
    }

    /**
     * Held aside on the hold's width plus a rem, never more than leaves 3rem of the row covered,
     * on the spring-back's motion. Without a hold the row stays where the finger left it.
     */
    private open(): void {
        this.clearReturnTimer();
        this.host.style.removeProperty('--lg-swipe-height');
        this.measure();
        const hold = this.host.querySelector<HTMLElement>(':scope > .lg-swipe-reveal .lg-swipe-hold');
        if (hold !== null) {
            const needed = this.host.getBoundingClientRect().right - hold.getBoundingClientRect().left + this.rem;
            const most = this.host.clientWidth - OPEN_KEEP_REM * this.rem;
            this.offset = -Math.max(0, Math.min(needed, most));
        }
        this.phase = 'open';
        this.paint();
    }

    /**
     * A pointer on an open row: on its reveal it belongs to the buttons there; anywhere else it
     * closes the row, and the click it makes opens nothing.
     */
    private onDownOpen(event: PointerEvent): void {
        if (event.target instanceof Element && event.target.closest('.lg-swipe-reveal') !== null) {
            return;
        }
        this.holdClick();
        this.springBack();
        this.closed.emit();
    }

    /** Back to 0 on the drag pair, then at rest; at once when the row never left or motion is reduced. */
    private springBack(): void {
        this.clearReturnTimer();
        this.host.style.removeProperty('--lg-swipe-height');
        if (this.offset === 0) {
            this.phase = 'rest';
            this.paint();
            return;
        }
        this.phase = 'returning';
        this.offset = 0;
        this.paint();
        const duration = this.returnDuration();
        if (duration <= 0) {
            this.phase = 'rest';
            this.paint();
            return;
        }
        // A timer rather than `transitionend`, which never fires for a transition that was cut short.
        this.returnTimer = setTimeout(() => {
            this.returnTimer = null;
            this.phase = 'rest';
            this.paint();
        }, duration);
    }

    /**
     * The offset and the phase, written onto the element directly: `--lg-swipe-x` and
     * `--lg-swipe-uncovered` while the row is off its place or on its way back, and the four state
     * classes. No signal and no host binding, so a gesture never schedules change detection.
     */
    private paint(): void {
        const phase = this.phase;
        const swiping = this.offset !== 0 || phase === 'returning' || phase === 'leaving';
        const style = this.host.style;
        if (swiping) {
            style.setProperty('--lg-swipe-x', `${this.offset}px`);
            style.setProperty('--lg-swipe-uncovered', String(Math.min(1, -this.offset / (FADE_IN_REM * this.rem))));
        } else {
            style.removeProperty('--lg-swipe-x');
            style.removeProperty('--lg-swipe-uncovered');
        }
        // A released row that counted stays armed, a fling short of the threshold included.
        const armed = phase === 'held' || phase === 'open' || phase === 'leaving' || (this.offset < 0 && -this.offset >= this.threshold);
        const classes = this.host.classList;
        classes.toggle('is-swiping', swiping);
        classes.toggle('is-armed', armed);
        classes.toggle('is-open', phase === 'open');
        classes.toggle('is-returning', phase === 'returning');
        classes.toggle('is-leaving', phase === 'leaving');
    }

    /** The track ends, and with it the three listeners its touch attached. */
    private untrack(): void {
        this.track = null;
        this.host.removeEventListener('pointermove', this.move);
        this.host.removeEventListener('pointerup', this.end);
        this.host.removeEventListener('pointercancel', this.end);
    }

    /** Swallows the one click an engaged gesture may still produce. */
    private holdClick(): void {
        this.releaseClick();
        this.host.addEventListener('click', this.swallowClick, {capture: true});
        this.clickTimer = setTimeout(() => this.releaseClick(), CLICK_GRACE_MS);
    }

    private releaseClick(): void {
        this.host.removeEventListener('click', this.swallowClick, {capture: true});
        if (this.clickTimer !== null) {
            clearTimeout(this.clickTimer);
            this.clickTimer = null;
        }
    }

    private clearReturnTimer(): void {
        if (this.returnTimer !== null) {
            clearTimeout(this.returnTimer);
            this.returnTimer = null;
        }
    }

    /** `--lg-swipe-return-duration` in ms, as the cascade resolved it on this host; 0 when unset. */
    private returnDuration(): number {
        const value = getComputedStyle(this.host).getPropertyValue('--lg-swipe-return-duration').trim();
        const amount = parseFloat(value);
        if (!Number.isFinite(amount)) {
            return 0;
        }
        if (value.endsWith('ms')) {
            return amount;
        }
        return value.endsWith('s') ? amount * 1000 : 0;
    }

    /** Read once per gesture: the row's width and the root font size do not change mid-drag. */
    private measure(): void {
        const rootSize = parseFloat(getComputedStyle(this.host.ownerDocument.documentElement).fontSize);
        const rem = Number.isFinite(rootSize) && rootSize > 0 ? rootSize : 16;
        this.rem = rem;
        this.threshold = Math.min(THRESHOLD_SHARE * this.host.clientWidth, THRESHOLD_CAP_REM * rem);
    }
}
