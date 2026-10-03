import {TestBed} from '@angular/core/testing';
import {provideRouter} from '@angular/router';
import {injectDispatch} from '@ngrx/signals/events';
import {cdp} from 'vitest/browser';
import {shortlistEvents} from '@core/store/shortlist.events';
import {toastEvents} from '@core/toast/toast.events';
import {actionToast, toast} from '@core/toast/toast.model';
import {ToastStack} from './toast-stack';

/**
 * ISC-500: a toast read on a touch screen is sized for a finger, not for a mouse. Touch emulation
 * is what turns `(pointer: coarse)` on in headless Chromium; the guard below fails loudly if it
 * ever stops doing so, rather than letting the coarse half pass on fine-pointer sizes.
 */
interface Session {
    send(method: string, params: Record<string, unknown>): Promise<unknown>;
}

async function touch(enabled: boolean): Promise<void> {
    const session = cdp() as unknown as Session;
    await session.send('Emulation.setTouchEmulationEnabled', enabled ? {enabled, maxTouchPoints: 5} : {enabled});
    await session.send('Emulation.setEmitTouchEventsForMouse', {enabled, configuration: 'mobile'});
}

interface Measured {
    readonly fontSize: number;
    readonly actionHeight: number;
    readonly closeHeight: number;
    readonly maxWidth: string;
}

/** Layout sizes, not `getBoundingClientRect`: the stack scales a toast in from 0.9, so a rect read during the reveal is 10 % short. */
function measure(alert: HTMLElement): Measured {
    const line = alert.querySelector<HTMLElement>('.line')!;
    const buttons = alert.querySelectorAll<HTMLElement>('.btn');
    const action = buttons[0];
    const close = buttons[buttons.length - 1];
    return {
        fontSize: parseFloat(getComputedStyle(line).fontSize),
        actionHeight: action.offsetHeight,
        closeHeight: close.offsetHeight,
        maxWidth: getComputedStyle(alert).width,
    };
}

describe('ToastStack sizes (ISC-500)', () => {
    let dispatch: ReturnType<typeof injectDispatch<typeof toastEvents>>;

    beforeEach(() => {
        document.documentElement.style.width = '1280px';
        TestBed.configureTestingModule({providers: [provideRouter([])]});
        dispatch = TestBed.runInInjectionContext(() => injectDispatch(toastEvents));
    });

    afterEach(async () => {
        await touch(false);
        document.documentElement.style.width = '';
    });

    function raiseBoth(): HTMLElement[] {
        const fixture = TestBed.createComponent(ToastStack);
        fixture.detectChanges();
        dispatch.raised(
            actionToast(
                'warning',
                'toast.archived',
                {key: 'toast.restore', event: shortlistEvents.archiveRequested({id: 7, archived: false})},
                {title: 'Senior Java Developer – payment platform modernisation (m/w/d)'},
            ),
        );
        dispatch.raised(toast('info', 'toast.archived', {title: 'Backend engineer'}, '/shortlist/8'));
        fixture.detectChanges();
        return Array.from(fixture.nativeElement.querySelectorAll('.alert'));
    }

    it('reads at the body size with finger-sized controls on a coarse pointer', async () => {
        await touch(true);
        expect(matchMedia('(pointer: coarse)').matches).toBe(true);
        const rootPx = parseFloat(getComputedStyle(document.documentElement).fontSize);

        for (const alert of raiseBoth()) {
            const m = measure(alert);
            expect(m.fontSize).toBeCloseTo(0.9375 * rootPx, 1);
            expect(m.actionHeight).toBeGreaterThanOrEqual(32);
            expect(m.closeHeight).toBeGreaterThanOrEqual(32);
            expect(parseFloat(m.maxWidth)).toBeLessThanOrEqual(32 * rootPx + 0.5);
            expect(parseFloat(m.maxWidth)).toBeGreaterThan(28 * rootPx);
        }
    });

    it('keeps the toast of today on a fine pointer', async () => {
        await touch(false);
        expect(matchMedia('(pointer: coarse)').matches).toBe(false);
        const rootPx = parseFloat(getComputedStyle(document.documentElement).fontSize);

        for (const alert of raiseBoth()) {
            const m = measure(alert);
            expect(m.fontSize).toBeCloseTo(0.8125 * rootPx, 1);
            expect(m.actionHeight).toBeLessThan(32);
            expect(m.closeHeight).toBeLessThan(32);
            expect(parseFloat(m.maxWidth)).toBeLessThanOrEqual(28 * rootPx + 0.5);
        }
    });
});
