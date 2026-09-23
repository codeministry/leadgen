import {TestBed} from '@angular/core/testing';
import {injectDispatch} from '@ngrx/signals/events';
import {ShortlistEntry} from '@core/model/shortlist-entry';
import {shortlistEvents} from '@core/store/shortlist.events';
import {toastEvents} from './toast.events';
import {TOAST_CAP, TOAST_LIFETIME_MS, toast} from './toast.model';
import {ToastStore} from './toast.store';

/**
 * Only what the toast reads off an archive answer. The rest of `ShortlistEntry` is
 * another store's business.
 */
function archiveAnswer(id: number, title: string, archivedAt: string | null): ShortlistEntry {
    return {offer: {id, title, archivedAt}} as unknown as ShortlistEntry;
}

describe('ToastStore', () => {
    let store: InstanceType<typeof ToastStore>;
    let dispatch: ReturnType<typeof injectDispatch<typeof toastEvents>>;
    let shortlist: ReturnType<typeof injectDispatch<typeof shortlistEvents>>;

    beforeEach(() => {
        // Only this store is created. The shortlist's own store is never injected, so its
        // events are dispatched into a room where the toast store is the only listener.
        store = TestBed.inject(ToastStore);
        dispatch = TestBed.runInInjectionContext(() => injectDispatch(toastEvents));
        shortlist = TestBed.runInInjectionContext(() => injectDispatch(shortlistEvents));
    });

    describe('an archive or a restore', () => {
        it('raises one toast per answer, naming the direction and the offer, linking to it', () => {
            shortlist.archived(archiveAnswer(7, 'Senior Java Entwickler (m/w/d)', '2026-09-23T10:00:00Z'));
            shortlist.archived(archiveAnswer(9, 'Angular Frontend Developer', null));

            const [archived, restored] = store.toasts();
            expect(store.toasts().length).toBe(2);
            expect(archived).toMatchObject({
                tone: 'success',
                key: 'toast.archived',
                params: {title: 'Senior Java Entwickler (m/w/d)'},
                link: '/shortlist/7',
            });
            expect(restored).toMatchObject({
                key: 'toast.restored',
                params: {title: 'Angular Frontend Developer'},
                link: '/shortlist/9',
            });
        });
    });

    describe('the lifetime', () => {
        beforeEach(() => vi.useFakeTimers());
        afterEach(() => vi.useRealTimers());

        it('leaves by itself once the lifetime has passed', () => {
            dispatch.raised(toast('info', 'toast.archived'));
            expect(store.toasts().length).toBe(1);

            vi.advanceTimersByTime(TOAST_LIFETIME_MS - 1);
            expect(store.toasts().length).toBe(1);

            vi.advanceTimersByTime(1);
            expect(store.toasts().length).toBe(0);
        });

        it('stays while held, and starts a fresh lifetime when released', () => {
            const held = toast('info', 'toast.archived');
            dispatch.raised(held);
            dispatch.held(held.id);

            vi.advanceTimersByTime(TOAST_LIFETIME_MS * 3);
            expect(store.toasts().length).toBe(1);

            dispatch.released(held.id);
            vi.advanceTimersByTime(TOAST_LIFETIME_MS - 1);
            expect(store.toasts().length).toBe(1);
            vi.advanceTimersByTime(1);
            expect(store.toasts().length).toBe(0);
        });

        it('closes on the button and does not expire a second time', () => {
            const closed = toast('info', 'toast.archived');
            dispatch.raised(closed);
            dispatch.raised(toast('info', 'toast.restored'));
            dispatch.dismissed(closed.id);
            expect(store.toasts().map((standing) => standing.key)).toEqual(['toast.restored']);

            vi.advanceTimersByTime(TOAST_LIFETIME_MS);
            expect(store.toasts().length).toBe(0);
        });

        it('keeps no more than the cap, the oldest leaving first', () => {
            const first = toast('info', 'toast.archived');
            dispatch.raised(first);
            for (let i = 0; i < TOAST_CAP; i += 1) {
                dispatch.raised(toast('info', 'toast.restored'));
            }

            expect(store.toasts().length).toBe(TOAST_CAP);
            expect(store.toasts().some((standing) => standing.id === first.id)).toBe(false);
        });
    });
});
