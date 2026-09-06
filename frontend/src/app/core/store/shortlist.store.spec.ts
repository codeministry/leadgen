import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {TestBed} from '@angular/core/testing';
import {injectDispatch} from '@ngrx/signals/events';
import {ShortlistEntry} from '@core/model/shortlist-entry';
import {ShortlistPage} from '@core/model/shortlist-page';
import {shortlistEvents} from './shortlist.events';
import {ShortlistStore} from './shortlist.store';

function entry(id: number): ShortlistEntry {
    return {
        offer: {
            id,
            externalId: `https://example.invalid/${id}`,
            title: `Senior Java Entwickler ${id}`,
            description: 'Ablösung eines Monolithen.',
            url: `https://example.invalid/${id}`,
            location: 'Köln',
            portal: 'portal-a',
            agency: null,
            publishedOn: '2026-09-01',
            tags: ['Java'],
            rateEur: null,
            remotePercent: null,
            startsOn: null,
            duration: null,
            workload: null,
            language: 'de',
            fullText: null,
            packageDir: null,
            archivedAt: null,
            archiveSource: null,
        },
        score: {value: 88, hardPass: true, reasons: [], model: null, rulesetVersion: '1'},
        flags: {incomplete: false, remoteUnknown: true},
        sources: [{portal: 'portal-a', agency: null, url: `https://example.invalid/${id}`}],
      content: [],
    };
}

function page(entries: readonly ShortlistEntry[]): ShortlistPage {
    return {
        entries,
        nextCursor: null,
        matched: entries.length,
        unscored: 0,
        total: entries.length,
        portals: ['portal-a'],
    };
}

/**
 * The list and the detail share this store and, since the split view, a screen. They used
 * to share one `loading` and one `error` as well — which is what these specs are about.
 */
describe('ShortlistStore', () => {
    let store: InstanceType<typeof ShortlistStore>;
    let http: HttpTestingController;
    let dispatch: ReturnType<typeof injectDispatch<typeof shortlistEvents>>;

    beforeEach(() => {
        TestBed.configureTestingModule({
            providers: [provideHttpClient(), provideHttpClientTesting()],
        });
        store = TestBed.inject(ShortlistStore);
        http = TestBed.inject(HttpTestingController);
        dispatch = TestBed.runInInjectionContext(() => injectDispatch(shortlistEvents));

        // The store injects `ScoringModelStore`, which asks the server which judges it may
        // offer as soon as it exists. Nothing here is about that, but it is a real request and
        // `verify()` counts it.
        http.expectOne('/api/scoring-models').flush({effective: null, options: []});
    });

    afterEach(() => http.verify());

    function openList(entries: readonly ShortlistEntry[] = [entry(1), entry(2)]): void {
        dispatch.opened({q: '', band: 'all', portal: '', archived: false});
        http.expectOne((request) => request.url === '/api/offers').flush(page(entries));
    }

    it('keeps the list on screen when a detail fetch fails', () => {
        // The whole reason the two pairs exist. Before the split there was one `error`, the
        // shortlist template branched on it first, and one bad id blanked the list beside it.
        openList();

        dispatch.offerRequested(999);
        http.expectOne('/api/offers/999').flush('no such offer', {
            status: 404,
            statusText: 'Not Found',
        });

        expect(store.entries().length).toBe(2);
        expect(store.listError()).toBeNull();
        expect(store.detailError()).toBe('error.offerLoad');
    });

    it('keeps the detail on screen when the list fetch fails', () => {
        dispatch.offerRequested(1);
        http.expectOne('/api/offers/1').flush(entry(1));

        dispatch.opened({q: 'java', band: 'all', portal: '', archived: false});
        http
            .expectOne((request) => request.url === '/api/offers')
            .flush('boom', {status: 500, statusText: 'Server Error'});

        expect(store.listError()).toBe('error.shortlistLoad');
        expect(store.detailError()).toBeNull();
        expect(store.selected()?.offer.id).toBe(1);
    });

    it('takes an archived offer off the list and leaves it in the detail', () => {
        // Archiving is done from the detail, after reading the ad, so the confirmation and the
        // way back are the detail itself. The row is no longer part of the side being read.
        openList();
        dispatch.offerRequested(1);
        http.expectOne('/api/offers/1').flush(entry(1));

        dispatch.archiveRequested({id: 1, archived: true});
        const archived = entry(1);
        http.expectOne('/api/offers/1').flush({
            ...archived,
            offer: {...archived.offer, archivedAt: '2026-09-06T08:00:00Z', archiveSource: 'MANUAL'},
        });

        expect(store.entries().map((row) => row.offer.id)).toEqual([2]);
        expect(store.selected()?.offer.archivedAt).toBe('2026-09-06T08:00:00Z');
        expect(store.matched()).toBe(1);
    });
});
