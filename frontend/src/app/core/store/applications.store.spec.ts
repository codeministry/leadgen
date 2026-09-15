import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {TestBed} from '@angular/core/testing';
import {injectDispatch} from '@ngrx/signals/events';
import {ApplicationView, PipelineLane} from '@core/model/application';
import {ShortlistEntry} from '@core/model/shortlist-entry';
import {applicationEvents} from './applications.events';
import {shortlistEvents} from './shortlist.events';
import {ApplicationsStore} from './applications.store';

const LANES: readonly PipelineLane[] = [
    {id: 'prepared', label: 'Prepared', states: ['PACKAGED']},
    {id: 'out', label: 'Out', states: ['SENT', 'REPLIED']},
];

function application(overrides: Partial<ApplicationView> = {}): ApplicationView {
    return {
        id: 1,
        offerId: 7,
        status: 'PACKAGED',
        title: 'Senior Java Entwickler (m/w/d)',
        agency: 'Acme Consulting GmbH',
        portal: 'portal-a',
        url: 'https://example.invalid/x',
        scoreValue: 88,
        rateEur: 95,
        packageDir: null,
        sentOn: null,
        followUpOn: null,
        followUpDue: false,
        outcome: null,
        note: null,
        updatedAt: '2026-09-01T10:00:00Z',
        ...overrides,
    };
}

/**
 * Only what the board reads off an archive answer. The rest of `ShortlistEntry` is another
 * store's business, and spelling it out here would tie this spec to a shape it never touches.
 */
function archiveAnswer(offerId: number, archivedAt: string | null): ShortlistEntry {
  return {offer: {id: offerId, archivedAt}} as unknown as ShortlistEntry;
}

describe('ApplicationsStore', () => {
    let store: InstanceType<typeof ApplicationsStore>;
    let http: HttpTestingController;
    let dispatch: ReturnType<typeof injectDispatch<typeof applicationEvents>>;
  let shortlist: ReturnType<typeof injectDispatch<typeof shortlistEvents>>;

    beforeEach(() => {
        TestBed.configureTestingModule({
            providers: [provideHttpClient(), provideHttpClientTesting()],
        });
        store = TestBed.inject(ApplicationsStore);
        http = TestBed.inject(HttpTestingController);
        dispatch = TestBed.runInInjectionContext(() => injectDispatch(applicationEvents));
      shortlist = TestBed.runInInjectionContext(() => injectDispatch(shortlistEvents));
    });

    afterEach(() => http.verify());

    function open(applications: readonly ApplicationView[]): void {
        dispatch.opened();
        http.expectOne('/api/applications').flush(applications);
        http.expectOne('/api/applications/lanes').flush(LANES);
    }

    it('groups the board by the lanes the server states, not by a copy of the enum', () => {
        open([application(), application({id: 2, status: 'SENT'})]);

        expect(store.columns().map((column) => column.lane.id)).toEqual(['prepared', 'out']);
        expect(store.columns()[0]?.applications.map((a) => a.id)).toEqual([1]);
        expect(store.columns()[1]?.applications.map((a) => a.id)).toEqual([2]);
    });

    it('offers every state the lanes contain, in the order the usual path runs', () => {
        open([]);

        expect(store.statusChoices()).toEqual([
            {value: 'PACKAGED', label: 'Packaged'},
            {value: 'SENT', label: 'Sent'},
            {value: 'REPLIED', label: 'Replied'},
        ]);
    });

    it('replaces the row with the answer rather than with what was asked for', () => {
        open([application()]);

        dispatch.changed({id: 1, update: {status: 'SENT'}});
        const request = http.expectOne('/api/applications/1');
        expect(request.request.method).toBe('PATCH');
        expect(store.saving()).toBe(1);

        // The server dated the send itself; a local guess would have shown yesterday's date
        // until the next reload.
        request.flush(application({status: 'SENT', sentOn: '2026-09-01'}));

        expect(store.applications()[0]?.sentOn).toBe('2026-09-01');
        expect(store.saving()).toBeNull();
    });

  it('drops a card the moment its offer is archived, without waiting for a reload', () => {
    // The archive button sits in the offer detail, which writes through `ShortlistStore`.
    // The server already leaves an archived offer off the board; this store never heard
    // about it, so the card stayed until a reload — and kept counting towards the
    // dashboard's follow-up tile while it did.
    open([application({followUpDue: true}), application({id: 2, offerId: 8, status: 'SENT'})]);

    shortlist.archived(archiveAnswer(7, '2026-09-15T10:54:16Z'));

    expect(store.applications().map((a) => a.id)).toEqual([2]);
    expect(store.followUpsDue()).toBe(0);
  });

  it('reads the board again when an offer is restored, because it threw the row away', () => {
    open([application()]);
    shortlist.archived(archiveAnswer(7, '2026-09-15T10:54:16Z'));
    expect(store.applications()).toEqual([]);

    shortlist.archived(archiveAnswer(7, null));

    http.expectOne('/api/applications').flush([application()]);
    http.expectOne('/api/applications/lanes').flush(LANES);
    expect(store.applications().map((a) => a.offerId)).toEqual([7]);
  });

  it('drops every card a bulk archive named, and asks for nothing', () => {
    open([application(), application({id: 2, offerId: 8}), application({id: 3, offerId: 9})]);

    shortlist.bulkArchived({ids: [7, 9, 404], archived: 2, unscored: 0});

    expect(store.applications().map((a) => a.offerId)).toEqual([8]);
  });

    it('counts only what the server called due, and says so when it cannot count', () => {
        open([application({followUpOn: '2026-08-30', followUpDue: true}), application({id: 2})]);
        expect(store.followUpsDue()).toBe(1);

        dispatch.changed({id: 1, update: {status: 'SENT'}});
        http.expectOne('/api/applications/1').flush('nope', {status: 500, statusText: 'Error'});

        expect(store.error()).toContain('not saved');
        expect(store.saving()).toBeNull();
    });
});
