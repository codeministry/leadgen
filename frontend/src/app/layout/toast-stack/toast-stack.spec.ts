import {TestBed} from '@angular/core/testing';
import {provideRouter} from '@angular/router';
import {injectDispatch} from '@ngrx/signals/events';
import {toastEvents} from '@core/toast/toast.events';
import {toast} from '@core/toast/toast.model';
import {ToastStore} from '@core/toast/toast.store';
import {ToastStack} from './toast-stack';

describe('ToastStack', () => {
    let dispatch: ReturnType<typeof injectDispatch<typeof toastEvents>>;

    beforeEach(() => {
        TestBed.configureTestingModule({providers: [provideRouter([])]});
        dispatch = TestBed.runInInjectionContext(() => injectDispatch(toastEvents));
    });

    it('is a polite live region that is in the DOM before it has anything to say', () => {
        const fixture = TestBed.createComponent(ToastStack);
        fixture.detectChanges();

        const region: HTMLElement = fixture.nativeElement.querySelector('[role="status"]');
        expect(region).not.toBeNull();
        expect(region.getAttribute('aria-live')).toBe('polite');
        expect(region.getAttribute('aria-label')).toBe('Notifications');
        expect(region.children.length).toBe(0);
    });

    it('paints a raised toast with its sentence, its link and a named close button, and steals no focus', () => {
        const input = document.createElement('input');
        document.body.appendChild(input);
        input.focus();

        const fixture = TestBed.createComponent(ToastStack);
        fixture.detectChanges();
        dispatch.raised(toast('success', 'toast.archived', {title: 'Senior Java Entwickler'}, '/shortlist/7'));
        fixture.detectChanges();

        const alert: HTMLElement = fixture.nativeElement.querySelector('.alert');
        expect(alert.classList.contains('alert-success')).toBe(true);
        expect(alert.textContent).toContain('Archived: Senior Java Entwickler');
        expect(alert.querySelector('a')?.getAttribute('href')).toBe('/shortlist/7');
        expect(alert.querySelector('button')?.getAttribute('aria-label')).toBe('Dismiss');
        expect(document.activeElement).toBe(input);

        input.remove();
    });

    it('carries only the two DaisyUI classes it means to, and its own prefixed ones', () => {
        // The container was `.stack` once, and DaisyUI 5 ships a `stack` component that lays
        // its children over one another in one grid cell — three toasts showed as one card
        // with two edges behind it. jsdom lays nothing out, so the guard is the class list.
        const fixture = TestBed.createComponent(ToastStack);
        fixture.detectChanges();
        dispatch.raised(toast('success', 'toast.archived', {title: 'x'}));
        fixture.detectChanges();

        const region: HTMLElement = fixture.nativeElement.querySelector('[role="status"]');
        const alert: HTMLElement = fixture.nativeElement.querySelector('.alert');
        const allowed = (name: string) => name.startsWith('lg-') || name === 'toast' || name.startsWith('alert');
        expect([...region.classList].filter((name) => !allowed(name))).toEqual([]);
        expect([...alert.classList].filter((name) => !allowed(name))).toEqual([]);
    });

    it('closes on the button, and the close is the only thing it dispatches', () => {
        const fixture = TestBed.createComponent(ToastStack);
        const store = TestBed.inject(ToastStore);
        fixture.detectChanges();
        dispatch.raised(toast('info', 'toast.restored', {title: 'x'}));
        fixture.detectChanges();

        (fixture.nativeElement.querySelector('button') as HTMLButtonElement).click();
        fixture.detectChanges();

        expect(store.toasts().length).toBe(0);
        expect(fixture.nativeElement.querySelectorAll('.alert').length).toBe(0);
    });

    it('holds the timer under the pointer and releases it on leave', () => {
        vi.useFakeTimers();
        const fixture = TestBed.createComponent(ToastStack);
        const store = TestBed.inject(ToastStore);
        fixture.detectChanges();
        dispatch.raised(toast('info', 'toast.restored', {title: 'x'}));
        fixture.detectChanges();

        const alert: HTMLElement = fixture.nativeElement.querySelector('.alert');
        alert.dispatchEvent(new Event('pointerenter'));
        vi.advanceTimersByTime(60_000);
        expect(store.toasts().length).toBe(1);

        alert.dispatchEvent(new Event('pointerleave'));
        vi.advanceTimersByTime(60_000);
        expect(store.toasts().length).toBe(0);
        vi.useRealTimers();
    });
});
