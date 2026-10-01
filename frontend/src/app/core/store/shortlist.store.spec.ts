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
  topic: '',
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
            enrichmentNote: null,
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
        http.expectOne('/api/v1/scoring-models').flush({available: [], preferred: null});
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

    it('leaves the detail on its own offer when another row is archived (ISC-499)', () => {
        // A swipe archives any row, not only the open one; the detail keeps what it shows.
        openList();
        dispatch.offerRequested(1);
        http.expectOne('/api/v1/offers/1').flush(entry(1));

        dispatch.archiveRequested({id: 2, archived: true});
        const archived = entry(2);
        http.expectOne('/api/v1/offers/2').flush({
            ...archived,
            offer: {...archived.offer, archivedAt: '2026-09-06T08:00:00Z', archiveSource: 'MANUAL'},
        });

        expect(store.entries().map((row) => row.offer.id)).toEqual([1]);
        expect(store.selected()?.offer.id).toBe(1);
        expect(store.selected()?.offer.archivedAt).toBeNull();
    });

    it('keeps a refused inline write out of the detail (ISC-495)', () => {
        // A swipe shows its refusal inside the row; the detail must not repeat it beside it.
        openList();
        dispatch.offerRequested(1);
        http.expectOne('/api/v1/offers/1').flush(entry(1));

        dispatch.archiveRequested({id: 1, archived: true, inline: true});
        http.expectOne('/api/v1/offers/1').flush('boom', {status: 500, statusText: 'Server Error'});
        expect(store.archiving()).toBeNull();
        expect(store.rescoreError()).toBeNull();
        expect(store.archiveError()).toEqual({id: 1, message: 'boom', inline: true});
        expect(store.inlineArchiveError()).toEqual({id: 1, message: 'boom', inline: true});
        expect(store.detailArchiveError()).toBeNull();

        dispatch.archiveRequested({id: 1, archived: true});
        http.expectOne('/api/v1/offers/1').flush('boom', {status: 500, statusText: 'Server Error'});
        expect(store.detailArchiveError(), 'the detail button still reads its own').toBe('boom');
        expect(store.inlineArchiveError()).toBeNull();
        expect(store.rescoreError(), 'an archive refusal is not a rescore refusal').toBeNull();
    });

    describe('a refused restore from the archive toast (review findings 5, 8)', () => {
        function refuseRestore(id: number): void {
            dispatch.archiveRequested({id, archived: false, inline: true});
            http.expectOne(`/api/v1/offers/${id}`).flush('locked', {status: 409, statusText: 'Conflict'});
        }

        it('is never shown under another offer', () => {
            openList([entry(1), entry(2)]);
            dispatch.offerRequested(1);
            http.expectOne('/api/v1/offers/1').flush(entry(1));

            refuseRestore(7);

            expect(store.archiveError()).toEqual({id: 7, message: 'locked', inline: true});
            expect(store.detailArchiveError(), 'the detail shows offer 1').toBeNull();
            expect(store.inlineArchiveError(), 'offer 7 has no row on screen').toBeNull();
            expect(store.rescoreError()).toBeNull();
        });

        it('falls back to the detail when the detail shows that offer and its row is not on screen', () => {
            openList([entry(1), entry(2)]);
            dispatch.offerRequested(7);
            http.expectOne('/api/v1/offers/7').flush(entry(7));

            refuseRestore(7);

            expect(store.detailArchiveError()).toBe('locked');
            expect(store.inlineArchiveError()).toBeNull();
        });

        it('stands in the row when the row is on screen', () => {
            openList([entry(1), entry(7)]);
            dispatch.offerRequested(7);
            http.expectOne('/api/v1/offers/7').flush(entry(7));

            refuseRestore(7);

            expect(store.inlineArchiveError()?.id).toBe(7);
            expect(store.detailArchiveError(), 'said once, in the row').toBeNull();
        });
    });

    describe('a second single write while one is out (review finding 1)', () => {
        it('is queued behind the first rather than dropped, and the state names the write in flight', () => {
            openList([entry(1), entry(2)]);
            dispatch.archiveRequested({id: 1, archived: true});
            const first = http.expectOne('/api/v1/offers/1');

            dispatch.archiveRequested({id: 9, archived: false, inline: true});
            expect(store.archiving(), 'still the write that is out').toBe(1);
            http.expectNone('/api/v1/offers/9');

            const one = entry(1);
            first.flush({...one, offer: {...one.offer, archivedAt: '2026-09-06T08:00:00Z'}});
            const second = http.expectOne('/api/v1/offers/9');
            expect(second.request.body).toEqual({archived: false});
            expect(store.archiving()).toBe(9);
            second.flush(entry(9));
            expect(store.archiving()).toBeNull();
        });

        it('still sends a repeat of the write that is out only once', () => {
            openList([entry(1), entry(2)]);
            dispatch.archiveRequested({id: 1, archived: true});
            dispatch.archiveRequested({id: 1, archived: true});

            const requests = http.match('/api/v1/offers/1');
            expect(requests.length).toBe(1);
            requests[0].flush(entry(1));
            http.expectNone('/api/v1/offers/1');
        });
    });

    describe('the counts after a single answer (review finding 4)', () => {
        const archivedAt = '2026-09-06T08:00:00Z';

        it('moves no count for an answer whose row was not on the list', () => {
            openList([entry(1), entry(2), entry(3)]);
            dispatch.archiveRequested({id: 9, archived: true});
            const nine = entry(9);
            http.expectOne('/api/v1/offers/9').flush({...nine, offer: {...nine.offer, archivedAt}});

            expect(store.matched()).toBe(3);
            expect(store.total()).toBe(3);
        });

        it('never lowers the working list for a restore', () => {
            openList([entry(1), entry(2), entry(3)]);
            dispatch.archiveRequested({id: 2, archived: false});
            http.expectOne('/api/v1/offers/2').flush(entry(2));

            expect(store.entries().map((row) => row.offer.id)).toEqual([1, 2, 3]);
            expect(store.matched()).toBe(3);
        });

        it('takes a restored row off the archive side, with the count', () => {
            dispatch.opened({...NO_FILTERS, archived: true});
            const archivedRows = [entry(1), entry(2)].map((row) => ({...row, offer: {...row.offer, archivedAt}}));
            http.expectOne((request) => request.url === '/api/v1/offers').flush(page(archivedRows));

            dispatch.archiveRequested({id: 2, archived: false});
            http.expectOne('/api/v1/offers/2').flush(entry(2));

            expect(store.entries().map((row) => row.offer.id)).toEqual([1]);
            expect(store.matched()).toBe(1);
        });
    });

    describe('a restore from the archive toast', () => {
        const archivedAt = '2026-09-06T08:00:00Z';

        /** Archive one offer through the store's own request, answered as the server would. */
        function archive(id: number): void {
            dispatch.archiveRequested({id, archived: true});
            const answer = entry(id);
            http.expectOne(`/api/v1/offers/${id}`).flush({
                ...answer,
                offer: {...answer.offer, archivedAt, archiveSource: 'MANUAL'},
            });
        }

        function restore(id: number): void {
            dispatch.archiveRequested({id, archived: false});
            http.expectOne(`/api/v1/offers/${id}`).flush(entry(id));
        }

        it('puts the row back where it was, with the counts, while the list is unchanged', () => {
            openList([entry(1), entry(2), entry(3)]);
            archive(2);
            expect(store.entries().map((row) => row.offer.id)).toEqual([1, 3]);
            expect(store.matched()).toBe(2);

            restore(2);

            expect(store.entries().map((row) => row.offer.id)).toEqual([1, 2, 3]);
            expect(store.entries()[1].offer.archivedAt).toBeNull();
            expect(store.matched()).toBe(3);
            expect(store.total()).toBe(3);
        });

        it('inserts nothing once the list was loaded again', () => {
            openList([entry(1), entry(2), entry(3)]);
            archive(2);
            openList([entry(1), entry(3)]);

            restore(2);

            expect(store.entries().map((row) => row.offer.id)).toEqual([1, 3]);
        });

        it('inserts nothing once the filters changed', () => {
            openList([entry(1), entry(2), entry(3)]);
            archive(2);
            dispatch.opened({...NO_FILTERS, q: 'java'});
            http.expectOne((request) => request.url === '/api/v1/offers').flush(page([entry(1), entry(3)]));

            restore(2);

            expect(store.entries().map((row) => row.offer.id)).toEqual([1, 3]);
        });

        it('inserts nothing for another offer\'s restore', () => {
            openList([entry(1), entry(2), entry(3)]);
            archive(2);

            restore(5);

            expect(store.entries().map((row) => row.offer.id)).toEqual([1, 3]);
        });
    });

    it('keeps a fetch that lands late off the offer the reader moved on to', () => {
        // A fetch runs a portal request and three model stages, so the reader may well open
        // another offer before it answers. The list row is still replaced; the detail is not,
        // or the first offer's advert would stand under the second one's title.
        openList();
        dispatch.offerRequested(1);
        http.expectOne('/api/v1/offers/1').flush(entry(1));
        dispatch.fetchRequested(1);
        const fetching = http.expectOne({method: 'POST', url: '/api/v1/offers/1/fetch'});

        dispatch.offerRequested(2);
        http.expectOne('/api/v1/offers/2').flush(entry(2));
        const fetched = entry(1);
        fetching.flush({...fetched, offer: {...fetched.offer, fullText: 'Das Inserat, spaet.'}});

        expect(store.selected()?.offer.id).toBe(2);
        expect(store.entries().find((row) => row.offer.id === 1)?.offer.fullText).toBe('Das Inserat, spaet.');
        expect(store.fetching()).toBeNull();
    });

    it('does not carry a refused fetch to the next offer', () => {
        openList();
        dispatch.offerRequested(1);
        http.expectOne('/api/v1/offers/1').flush(entry(1));
        dispatch.fetchRequested(1);
        http.expectOne({method: 'POST', url: '/api/v1/offers/1/fetch'})
            .flush('the fetch rate limit of 20 ads a minute is spent', {status: 429, statusText: 'Too Many Requests'});
        expect(store.fetchError()).toContain('rate limit');

        dispatch.offerRequested(2);
        http.expectOne('/api/v1/offers/2').flush(entry(2));

        expect(store.fetchError()).toBeNull();
    });

    it('keeps a rescore that lands late off the offer the reader moved on to', () => {
        // The same race as the fetch, one button over: a rescore is a model call and takes
        // seconds, and the reader may open another offer before it answers. The list row is
        // still replaced; the detail is not, or the first offer's score would stand under the
        // second one's title.
        openList();
        dispatch.offerRequested(1);
        http.expectOne('/api/v1/offers/1').flush(entry(1));
        dispatch.rescoreRequested(1);
        const rescoring = http.expectOne({method: 'POST', url: '/api/v1/offers/1/score'});

        dispatch.offerRequested(2);
        http.expectOne('/api/v1/offers/2').flush(entry(2));
        const rescored = entry(1);
        rescoring.flush({...rescored, score: {...rescored.score, value: 42}});

        expect(store.selected()?.offer.id).toBe(2);
        expect(store.selected()?.score.value).toBe(88);
        expect(store.entries().find((row) => row.offer.id === 1)?.score.value).toBe(42);
        expect(store.rescoring()).toBeNull();
    });

    it('keeps a refused rescore that lands late off the offer the reader moved on to', () => {
        // The refusal names the offer that was asked about, not the one on screen now. Under
        // the next offer's title it would read as that offer's problem.
        openList();
        dispatch.offerRequested(1);
        http.expectOne('/api/v1/offers/1').flush(entry(1));
        dispatch.rescoreRequested(1);
        const rescoring = http.expectOne({method: 'POST', url: '/api/v1/offers/1/score'});

        dispatch.offerRequested(2);
        http.expectOne('/api/v1/offers/2').flush(entry(2));
        rescoring.flush('no scoring model is configured', {status: 409, statusText: 'Conflict'});

        expect(store.rescoreError()).toBeNull();
        expect(store.rescoring()).toBeNull();
    });

    it('does not carry a refused rescore to the next offer', () => {
        openList();
        dispatch.offerRequested(1);
        http.expectOne('/api/v1/offers/1').flush(entry(1));
        dispatch.rescoreRequested(1);
        http.expectOne({method: 'POST', url: '/api/v1/offers/1/score'})
            .flush('no scoring model is configured', {status: 409, statusText: 'Conflict'});
        expect(store.rescoreError()).toContain('scoring model');

        dispatch.offerRequested(2);
        http.expectOne('/api/v1/offers/2').flush(entry(2));

        expect(store.rescoreError()).toBeNull();
    });
});
