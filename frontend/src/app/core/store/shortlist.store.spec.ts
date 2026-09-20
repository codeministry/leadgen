import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {TestBed} from '@angular/core/testing';
import {injectDispatch} from '@ngrx/signals/events';
import {ShortlistEntry} from '@core/model/shortlist-entry';
import {ShortlistFilters, ShortlistPage} from '@core/model/shortlist-page';
import {refreshEvents} from '@core/refresh/refresh.events';
import {shortlistEvents} from './shortlist.events';
import {ShortlistStore} from './shortlist.store';

/** The defaults the store itself starts from, so a spec names only what it is changing. */
const NO_FILTERS: ShortlistFilters = {
  q: '',
  band: 'all',
  minScore: null,
  maxScore: null,
  scoreState: 'any',
  portals: [],
  archived: false,
  sort: 'score',
  startWindow: 'any',
  minMonths: 0,
  deadlineOpen: false,
  possibleDuplicates: false,
  semantic: '',
  similarTo: null,
};

function entry(id: number): ShortlistEntry {
    return {
        offer: {
            id,
            sourceName: 'sample-newsletter',
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
          startText: null,
          durationMonths: null,
          applyBy: null,
          applyByText: null,
            duration: null,
            workload: null,
            language: 'de',
            fullText: null,
            packageDir: null,
          ingestedAt: '2026-09-02T05:12:00Z',
            archivedAt: null,
            archiveSource: null,
        },
        score: {value: 88, hardPass: true, reasons: [], model: null, rulesetVersion: '1'},
      flags: {incomplete: false, remoteUnknown: true, possibleDuplicate: false},
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
        related: null,
        relatedTo: null,
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
  let refresh: ReturnType<typeof injectDispatch<typeof refreshEvents>>;

    beforeEach(() => {
        TestBed.configureTestingModule({
            providers: [provideHttpClient(), provideHttpClientTesting()],
        });
        store = TestBed.inject(ShortlistStore);
        http = TestBed.inject(HttpTestingController);
        dispatch = TestBed.runInInjectionContext(() => injectDispatch(shortlistEvents));
      refresh = TestBed.runInInjectionContext(() => injectDispatch(refreshEvents));

        // The store injects `ScoringModelStore`, which asks the server which judges it may
        // offer as soon as it exists. Nothing here is about that, but it is a real request and
        // `verify()` counts it.
        http.expectOne('/api/v1/scoring-models').flush({effective: null, options: []});
    });

    afterEach(() => http.verify());

    function openList(entries: readonly ShortlistEntry[] = [entry(1), entry(2)]): void {
      dispatch.opened(NO_FILTERS);
        http.expectOne((request) => request.url === '/api/v1/offers').flush(page(entries));
    }

  it('starts a new list when the sort changes, so a cursor cannot cross sorts', () => {
    // The server composes the ORDER BY and the keyset comparison from one expression and
    // refuses a cursor minted under another sort. That refusal is a guard against
    // hand-written links; what stops the browser ever sending one is this — the sort is
    // one of the filters, and `opened` already empties the entries and the cursor.
    dispatch.opened(NO_FILTERS);
    http
      .expectOne((request) => request.url === '/api/v1/offers')
      .flush({...page([entry(1), entry(2)]), nextCursor: 'score|88|1|1'});
    expect(store.cursor()).toBe('score|88|1|1');

    dispatch.opened({...NO_FILTERS, sort: 'start'});

    expect(store.entries()).toEqual([]);
    expect(store.cursor()).toBeNull();
    const request = http.expectOne((call) => call.url === '/api/v1/offers');
    expect(request.request.params.get('sort')).toBe('start');
    expect(request.request.params.has('cursor')).toBe(false);
    request.flush(page([entry(3)]));
  });

  it('leaves the match count alone when the list only gets longer', () => {
    // A longer list is the same match. The count belongs to the filters and not to how
    // far somebody has scrolled — which is exactly the defect that moved it to the server
    // in the first place, and it came back on the other side of the wire.
    dispatch.opened(NO_FILTERS);
    http
      .expectOne((request) => request.url === '/api/v1/offers')
      .flush({...page([entry(1)]), matched: 120, unscored: 7, nextCursor: 'score|88|1|1'});

    dispatch.moreRequested();
    // What a server with the page clause bleeding into the count would answer.
    http
      .expectOne((request) => request.url === '/api/v1/offers')
      .flush({...page([entry(2)]), matched: 1, unscored: 0, nextCursor: null});

    expect(store.entries().length).toBe(2);
    expect(store.matched()).toBe(120);
    expect(store.unscored()).toBe(7);
  });

  it('flags the list rather than yanking the reader back to page one', () => {
    // Every other screen re-reads silently because nothing there is lost. This list is
    // keyset-paged, so re-reading it means `opened`, which empties the entries and starts
    // again at the top — news the reader did not ask about, delivered by moving them.
    dispatch.opened(NO_FILTERS);
    http
      .expectOne((request) => request.url === '/api/v1/offers')
      .flush({...page([entry(1), entry(2)]), nextCursor: 'score|88|1|1'});
    expect(store.stale()).toBe(false);

    refresh.requested('run-ended');

    expect(store.stale()).toBe(true);
    expect(store.entries().length).toBe(2);
    expect(store.cursor()).toBe('score|88|1|1');
    // The funnel is read again either way: four numbers above the column, and nothing
    // about them is lost by reading them twice.
    http.expectOne('/api/v1/offers/funnel').flush({stages: [], survived: 2, considered: 2});
  });

  it('clears the flag when a page actually arrives, whoever asked for it', () => {
    // Self-correcting, and it is what stops this browser's own run from leaving the hint
    // behind: the reload `ingestEvents.finished` starts clears it with no sequencing.
    dispatch.opened(NO_FILTERS);
    http.expectOne((request) => request.url === '/api/v1/offers').flush(page([entry(1)]));

    refresh.requested('tab-focused');
    http.expectOne('/api/v1/offers/funnel').flush({stages: [], survived: 1, considered: 1});
    expect(store.stale()).toBe(true);

    dispatch.opened(NO_FILTERS);
    http.expectOne((request) => request.url === '/api/v1/offers').flush(page([entry(1), entry(2)]));

    expect(store.stale()).toBe(false);
  });

    it('keeps the list on screen when a detail fetch fails', () => {
        // The whole reason the two pairs exist. Before the split there was one `error`, the
        // shortlist template branched on it first, and one bad id blanked the list beside it.
        openList();

        dispatch.offerRequested(999);
        http.expectOne('/api/v1/offers/999').flush('no such offer', {
            status: 404,
            statusText: 'Not Found',
        });

        expect(store.entries().length).toBe(2);
        expect(store.listError()).toBeNull();
        expect(store.detailError()).toBe('error.offerLoad');
    });

    it('keeps the detail on screen when the list fetch fails', () => {
        dispatch.offerRequested(1);
        http.expectOne('/api/v1/offers/1').flush(entry(1));

      dispatch.opened({...NO_FILTERS, q: 'java'});
        http
            .expectOne((request) => request.url === '/api/v1/offers')
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
        http.expectOne('/api/v1/offers/1').flush(entry(1));

        dispatch.archiveRequested({id: 1, archived: true});
        const archived = entry(1);
        http.expectOne('/api/v1/offers/1').flush({
            ...archived,
            offer: {...archived.offer, archivedAt: '2026-09-06T08:00:00Z', archiveSource: 'MANUAL'},
        });

        expect(store.entries().map((row) => row.offer.id)).toEqual([2]);
        expect(store.selected()?.offer.archivedAt).toBe('2026-09-06T08:00:00Z');
        expect(store.matched()).toBe(1);
    });
});
