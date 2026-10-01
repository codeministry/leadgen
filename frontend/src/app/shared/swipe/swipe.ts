import {computed, DestroyRef, Directive, ElementRef, inject, input, output, signal} from '@angular/core';

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
 * `settle`; `returning`: springing back to 0; `leaving`: sliding off, and holding there until the
 * host takes the row away.
 */
type Phase = 'rest' | 'drag' | 'held' | 'returning' | 'leaving';

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
 *
 * <p>A touch engages only once its horizontal travel passes the 10px slop and beats its vertical
 * travel; until then nothing moves, and a touch that went vertical first is the list's for good.
 * The click that follows an engaged gesture — released, flung or cancelled — is swallowed on the
 * host, so a swipe never opens the offer; a tap without travel still does (ISC-494). While
 * `swipeDisabled` is set a touch starts nothing at all (ISC-497).
 *
 * <p>Markup contract: the reveal is a child with `.lg-swipe-reveal`; every other child moves.
 */
@Directive({
    selector: '[lgSwipe]',
    exportAs: 'lgSwipe',
    host: {
        class: 'lg-swipe',
        '[style.touch-action]': '"pan-y"',
        '[style.--lg-swipe-x]': 'swiping() ? offset() + "px" : null',
        '[style.--lg-swipe-uncovered]': 'swiping() ? uncovered() : null',
        '[style.--lg-swipe-height]': 'leaveHeight() === null ? null : leaveHeight() + "px"',
        '[class.is-swiping]': 'swiping()',
        '[class.is-armed]': 'armed()',
        '[class.is-returning]': 'phase() === "returning"',
        '[class.is-leaving]': 'phase() === "leaving"',
        '(pointerdown)': 'onDown($event)',
        '(pointermove)': 'onMove($event)',
        '(pointerup)': 'onEnd($event)',
        '(pointercancel)': 'onEnd($event)',
    },
})
export class Swipe {
    /** While true a touch on the row engages nothing; it simply scrolls or taps. */
    readonly swipeDisabled = input(false);
    /** Released past the threshold, or flung. The host answers with `settle`. */
    readonly swiped = output<void>();

    private readonly host = inject<ElementRef<HTMLElement>>(ElementRef).nativeElement;

    /** The row's horizontal offset in px: 0 at rest, negative to the left, never positive. */
    protected readonly offset = signal(0);
    protected readonly phase = signal<Phase>('rest');
    /** The row's height as it starts to leave, so the collapse has a length to close from. */
    protected readonly leaveHeight = signal<number | null>(null);
    private readonly threshold = signal(Number.POSITIVE_INFINITY);
    private readonly rem = signal(16);

    /** Off its place, or on its way back to it: the transform, the clip and the reveal apply. */
    protected readonly swiping = computed(() => {
        const phase = this.phase();
        return this.offset() !== 0 || phase === 'returning' || phase === 'leaving';
    });
    /** A released row that counted stays armed, a fling short of the threshold included. */
    protected readonly armed = computed(() => {
        const phase = this.phase();
        return phase === 'held' || phase === 'leaving' || (this.offset() < 0 && -this.offset() >= this.threshold());
    });
    protected readonly uncovered = computed(() => Math.min(1, -this.offset() / (FADE_IN_REM * this.rem())));

    private track: Track | null = null;
    private returnTimer: ReturnType<typeof setTimeout> | null = null;
    private clickTimer: ReturnType<typeof setTimeout> | null = null;
    /**
     * Capture phase on the host, so it runs before the title's router link and the checkbox below
     * it: the click after an engaged gesture never opens the offer and never ticks a box (ISC-494).
     */
    private readonly swallowClick = (event: Event): void => {
        event.preventDefault();
        event.stopPropagation();
        this.releaseClick();
    };

    constructor() {
        inject(DestroyRef).onDestroy(() => {
            this.clearReturnTimer();
            this.releaseClick();
        });
    }

    /** The host decides what a completed swipe means; the directive only moves. */
    settle(how: 'leave' | 'return'): void {
        this.track = null;
        if (how === 'return') {
            this.springBack();
            return;
        }
        this.clearReturnTimer();
        this.leaveHeight.set(this.host.offsetHeight);
        this.phase.set('leaving');
        // Off the row's own edge and one gap further, holding the armed state until the row goes.
        this.offset.set(-(this.host.clientWidth + this.rem()));
    }

    protected onDown(event: PointerEvent): void {
        // A new touch is a new gesture: a click from here on is its own, never the last one's.
        this.releaseClick();
        const phase = this.phase();
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
            this.phase.set('rest');
        }
        this.track = {pointerId: event.pointerId, startX: event.clientX, startY: event.clientY, engaged: false, samples: []};
    }

    protected onMove(event: PointerEvent): void {
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
                this.phase.set('drag');
                this.measure();
            } else if (horizontal || Math.abs(dy) > SLOP_PX) {
                // Rightward, or the list is being scrolled: this touch is not a swipe.
                this.track = null;
                return;
            } else {
                return;
            }
        }
        // Minus the slop, so the row does not jump by it on engaging; clamped at 0 with no give.
        const offset = Math.min(0, dx + SLOP_PX);
        this.offset.set(offset);
        track.samples.push([event.timeStamp, offset]);
        while (track.samples[0][0] < event.timeStamp - VELOCITY_WINDOW_MS) {
            track.samples.shift();
        }
    }

    protected onEnd(event: PointerEvent): void {
        const track = this.track;
        if (track === null || event.pointerId !== track.pointerId) {
            return;
        }
        this.track = null;
        if (track.engaged) {
            this.holdClick();
        }
        // Only a finger that lifted counts; a cancelled pointer (a scroll took over, a call came in) never does.
        if (event.type === 'pointerup' && track.engaged && this.counts(track, event.timeStamp)) {
            this.phase.set('held');
            this.swiped.emit();
            return;
        }
        this.springBack();
    }

    /** Past the threshold, or flung to the left fast enough once the fling floor is uncovered. */
    private counts(track: Track, releasedAt: number): boolean {
        const uncovered = -this.offset();
        if (uncovered >= this.threshold()) {
            return true;
        }
        return uncovered >= FLING_FLOOR_REM * this.rem() && this.velocity(track, releasedAt) <= -FLING_SPEED;
    }

    /**
     * The speed in px/ms over the window before the release, with the release itself as the last
     * sample: a finger that stopped before it lifted has no speed left, however fast it came.
     */
    private velocity(track: Track, releasedAt: number): number {
        const release: [number, number] = [releasedAt, this.offset()];
        const samples = [...track.samples, release].filter(([t]) => t >= releasedAt - VELOCITY_WINDOW_MS);
        if (samples.length < 2) {
            return 0;
        }
        const [t0, x0] = samples[0];
        const [t1, x1] = samples[samples.length - 1];
        return t1 > t0 ? (x1 - x0) / (t1 - t0) : 0;
    }

    /** Back to 0 on the drag pair, then at rest; at once when the row never left or motion is reduced. */
    private springBack(): void {
        this.clearReturnTimer();
        this.leaveHeight.set(null);
        if (this.offset() === 0) {
            this.phase.set('rest');
            return;
        }
        this.phase.set('returning');
        this.offset.set(0);
        const duration = this.returnDuration();
        if (duration <= 0) {
            this.phase.set('rest');
            return;
        }
        // A timer rather than `transitionend`, which never fires for a transition that was cut short.
        this.returnTimer = setTimeout(() => {
            this.returnTimer = null;
            this.phase.set('rest');
        }, duration);
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
        this.rem.set(rem);
        this.threshold.set(Math.min(THRESHOLD_SHARE * this.host.clientWidth, THRESHOLD_CAP_REM * rem));
    }
}
