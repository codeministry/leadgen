import {ChangeDetectionStrategy, Component, signal, viewChild} from '@angular/core';
import {ComponentFixture, TestBed} from '@angular/core/testing';
import {Swipe} from './swipe';

@Component({
    imports: [Swipe],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <div lgSwipe [swipeDisabled]="disabled()" (swiped)="swiped = swiped + 1" data-testid="track">
            <div class="lg-swipe-reveal"></div>
            <p class="body">row <span class="pick"><input type="checkbox"/></span></p>
        </div>
    `,
})
class Host {
    readonly disabled = signal(false);
    readonly swipe = viewChild.required(Swipe);
    swiped = 0;
}

type Pointer = 'touch' | 'mouse' | 'pen';

describe('lgSwipe', () => {
    let fixture: ComponentFixture<Host>;
    let track: HTMLElement;
    let id = 0;
    /** A virtual clock for the events' `timeStamp`, so a drag's speed is the test's choice. */
    let now = 0;

    beforeEach(() => {
        fixture = TestBed.createComponent(Host);
        fixture.detectChanges();
        track = fixture.nativeElement.querySelector('[data-testid="track"]');
    });

    /** Dispatches one pointer event, stamped `stepMs` after the previous one. */
    function fire(target: Element, type: string, pointerType: Pointer, x: number, y: number, pointerId: number, stepMs = 50): void {
        now += stepMs;
        const event = new PointerEvent(type, {pointerType, pointerId, clientX: x, clientY: y, button: 0, bubbles: true, cancelable: true});
        Object.defineProperty(event, 'timeStamp', {value: now});
        target.dispatchEvent(event);
    }

    /**
     * A pointer down at (200, 100) that travels to (200 + dx, 100 + dy) in ten steps of `stepMs`
     * each, not released. The default is a slow drag: 50ms a step is far under the fling speed.
     */
    function drag(pointerType: Pointer, dx: number, dy = 0, target: Element = track.querySelector('.body')!, stepMs = 50): number {
        const pointerId = ++id;
        fire(target, 'pointerdown', pointerType, 200, 100, pointerId);
        for (let i = 1; i <= 10; i++) {
            fire(target, 'pointermove', pointerType, 200 + (dx * i) / 10, 100 + (dy * i) / 10, pointerId, stepMs);
        }
        fixture.detectChanges();
        return pointerId;
    }

    /** Lifts the finger right where the last move left it: `offset` px uncovered, the slop added back. */
    function lift(pointerId: number, offset: number, stepMs = 50): void {
        fire(track, 'pointerup', 'touch', 200 - offset - 10, 100, pointerId, stepMs);
        fixture.detectChanges();
    }

    const width = (px: number) => Object.defineProperty(track, 'clientWidth', {configurable: true, value: px});
    const swiped = () => fixture.componentInstance.swiped;

    const x = () => track.style.getPropertyValue('--lg-swipe-x');

    it('sets --lg-swipe-x from a left touch drag and marks the host as swiping', () => {
        drag('touch', -80);
        expect(x()).toBe('-70px');
        expect(track.classList).toContain('is-swiping');
        expect(track.classList).toContain('lg-swipe');
    });

    it('follows the finger one to one once engaged', () => {
        const pointerId = drag('touch', -80);
        fire(track, 'pointermove', 'touch', 200 - 130, 100, pointerId);
        fixture.detectChanges();
        expect(x()).toBe('-120px');
    });

    it('stays at 0 for a right touch drag', () => {
        drag('touch', 120);
        expect(x()).toBe('');
        expect(track.classList).not.toContain('is-swiping');
    });

    it('clamps at 0 when a left drag comes back past where it started', () => {
        const pointerId = drag('touch', -80);
        fire(track, 'pointermove', 'touch', 300, 100, pointerId);
        fixture.detectChanges();
        expect(x()).toBe('');
        expect(track.classList).not.toContain('is-swiping');
    });

    for (const pointer of ['mouse', 'pen'] as const) {
        it(`starts nothing for a ${pointer}`, () => {
            drag(pointer, -120);
            expect(x()).toBe('');
            expect(track.classList).not.toContain('is-swiping');
        });
    }

    it('starts nothing for a mostly vertical touch movement', () => {
        drag('touch', -30, 90);
        expect(x()).toBe('');
    });

    it('ignores a touch that starts on the checkbox', () => {
        drag('touch', -120, 0, track.querySelector('.pick input')!);
        expect(x()).toBe('');
    });

    it('starts nothing while disabled', () => {
        fixture.componentInstance.disabled.set(true);
        fixture.detectChanges();
        drag('touch', -120);
        expect(x()).toBe('');
    });

    it('returns to rest when the finger lifts', () => {
        width(300);
        const pointerId = drag('touch', -80);
        fire(track, 'pointerup', 'touch', 120, 100, pointerId);
        fixture.detectChanges();
        expect(x()).toBe('');
        expect(track.classList).not.toContain('is-swiping');
        expect(track.classList).not.toContain('is-armed');
    });

    it('arms past min(40 % of the row, 12rem)', () => {
        Object.defineProperty(track, 'clientWidth', {configurable: true, value: 300});
        // 40 % of 300 is 120, under 12rem; the slop is taken off the travel.
        const pointerId = drag('touch', -125);
        expect(track.classList).not.toContain('is-armed');
        fire(track, 'pointermove', 'touch', 200 - 140, 100, pointerId);
        fixture.detectChanges();
        expect(track.classList).toContain('is-armed');
    });

    describe('on release (ISC-493)', () => {
        it('springs back and emits nothing short of the threshold', () => {
            width(300);
            // 40 % of 300 is 120; 100px uncovered is short of it.
            const pointerId = drag('touch', -110);
            lift(pointerId, 100);
            expect(swiped()).toBe(0);
            expect(x()).toBe('');
            expect(track.classList).not.toContain('is-swiping');
        });

        it('emits swiped exactly once past the threshold and holds until the host settles', () => {
            width(300);
            const pointerId = drag('touch', -150);
            lift(pointerId, 140);
            expect(swiped()).toBe(1);
            // Waits for the host's answer where the finger left it, still armed.
            expect(x()).toBe('-140px');
            expect(track.classList).toContain('is-armed');

            // A second lift of the same pointer, or a new touch before the host answered, counts for nothing.
            lift(pointerId, 140);
            const again = drag('touch', -150);
            lift(again, 140);
            expect(swiped()).toBe(1);

            fixture.componentInstance.swipe().settle('return');
            fixture.detectChanges();
            expect(x()).toBe('');
            expect(track.classList).not.toContain('is-swiping');
        });

        it("slides off by the row's width and one rem on settle('leave'), and holds there", () => {
            width(300);
            const pointerId = drag('touch', -150);
            lift(pointerId, 140);
            fixture.componentInstance.swipe().settle('leave');
            fixture.detectChanges();
            expect(x()).toBe('-316px');
            expect(track.classList).toContain('is-leaving');
            expect(track.classList).toContain('is-armed');
        });

        it('caps the threshold at 12rem on a wide row', () => {
            width(1000);
            // 40 % of 1000 is 400; the cap of 12rem is 192px.
            let pointerId = drag('touch', -195);
            lift(pointerId, 185);
            expect(swiped(), '185px is short of the 192px cap').toBe(0);

            pointerId = drag('touch', -210);
            lift(pointerId, 200);
            expect(swiped(), '200px is past the cap').toBe(1);
        });

        it('takes a leftward flick as a swipe once 3rem are uncovered, and not before', () => {
            width(1000);
            // 2ms a step: several px/ms, far past the fling speed.
            let pointerId = drag('touch', -42, 0, undefined, 2);
            lift(pointerId, 32, 2);
            expect(swiped(), 'a flick with 2rem uncovered').toBe(0);
            expect(x()).toBe('');

            pointerId = drag('touch', -74, 0, undefined, 2);
            lift(pointerId, 64, 2);
            expect(swiped(), 'a flick with 4rem uncovered').toBe(1);
        });

        it('does not take a slow drag to 4rem as a fling', () => {
            width(1000);
            const pointerId = drag('touch', -74);
            lift(pointerId, 64);
            expect(swiped()).toBe(0);
        });

        it('does not count a fling whose finger stopped before it lifted', () => {
            width(1000);
            const pointerId = drag('touch', -74, 0, undefined, 2);
            lift(pointerId, 64, 400);
            expect(swiped()).toBe(0);
        });

        it('springs back on a pointercancel, however far the row went', () => {
            width(300);
            const pointerId = drag('touch', -200);
            fire(track, 'pointercancel', 'touch', 0, 100, pointerId);
            fixture.detectChanges();
            expect(swiped()).toBe(0);
            expect(x()).toBe('');
        });
    });

    describe('tap versus swipe (ISC-494)', () => {
        /** Clicks the row's body the way a browser does after a lift; says whether the body saw it. */
        function click(): {reached: boolean; prevented: boolean} {
            const body = track.querySelector('.body')!;
            let reached = false;
            const seen = () => (reached = true);
            body.addEventListener('click', seen);
            const event = new MouseEvent('click', {bubbles: true, cancelable: true});
            body.dispatchEvent(event);
            body.removeEventListener('click', seen);
            return {reached, prevented: event.defaultPrevented};
        }

        /** A touch that lands on the body and lifts where it landed. */
        function tap(): void {
            const pointerId = ++id;
            const body = track.querySelector('.body')!;
            fire(body, 'pointerdown', 'touch', 200, 100, pointerId);
            fire(body, 'pointerup', 'touch', 200, 100, pointerId);
        }

        it('moves nothing while the travel stays under the 10px slop', () => {
            drag('touch', -9, 3);
            expect(x()).toBe('');
            expect(track.classList).not.toContain('is-swiping');
        });

        it('moves nothing when the touch went vertical first, however far left it goes after', () => {
            const pointerId = ++id;
            const body = track.querySelector('.body')!;
            fire(body, 'pointerdown', 'touch', 200, 100, pointerId);
            fire(body, 'pointermove', 'touch', 198, 115, pointerId);
            fire(body, 'pointermove', 'touch', 60, 118, pointerId);
            fixture.detectChanges();
            expect(x()).toBe('');
        });

        it('lets the click of a tap without travel through', () => {
            tap();
            expect(click()).toEqual({reached: true, prevented: false});
        });

        it('lets the click of a sub-slop touch through', () => {
            const pointerId = drag('touch', -8);
            fire(track, 'pointerup', 'touch', 192, 100, pointerId);
            expect(click().reached).toBe(true);
        });

        it('swallows the click that follows an engaged gesture, once', () => {
            width(300);
            const pointerId = drag('touch', -60);
            lift(pointerId, 50);
            expect(x()).toBe('');
            expect(click(), 'the engaged gesture opens nothing').toEqual({reached: false, prevented: true});
            expect(click().reached, 'the next click is a click again').toBe(true);
        });

        it('swallows the click after an engaged gesture that counted, and after a cancelled one', () => {
            width(300);
            let pointerId = drag('touch', -200);
            lift(pointerId, 190);
            expect(swiped()).toBe(1);
            expect(click().reached).toBe(false);
            fixture.componentInstance.swipe().settle('return');
            fixture.detectChanges();

            pointerId = drag('touch', -60);
            fire(track, 'pointercancel', 'touch', 150, 100, pointerId);
            expect(click().reached).toBe(false);
        });

        it('does not swallow a later tap when no click followed the engaged gesture', () => {
            width(300);
            const pointerId = drag('touch', -60);
            lift(pointerId, 50);
            tap();
            expect(click().reached).toBe(true);
        });
    });
});
