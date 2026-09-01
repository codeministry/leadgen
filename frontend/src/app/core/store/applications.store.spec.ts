import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { injectDispatch } from '@ngrx/signals/events';
import { ApplicationView, PipelineLane } from '@core/model/application';
import { applicationEvents } from './applications.events';
import { ApplicationsStore } from './applications.store';

const LANES: readonly PipelineLane[] = [
  { id: 'prepared', label: 'Prepared', states: ['PACKAGED'] },
  { id: 'out', label: 'Out', states: ['SENT', 'REPLIED'] },
];

function application(overrides: Partial<ApplicationView> = {}): ApplicationView {
  return {
    id: 1,
    offerId: 7,
    status: 'PACKAGED',
    title: 'Senior Java Entwickler (m/w/d)',
    agency: 'Etengo AG',
    portal: 'FreelancerMap',
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

describe('ApplicationsStore', () => {
  let store: InstanceType<typeof ApplicationsStore>;
  let http: HttpTestingController;
  let dispatch: ReturnType<typeof injectDispatch<typeof applicationEvents>>;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    store = TestBed.inject(ApplicationsStore);
    http = TestBed.inject(HttpTestingController);
    dispatch = TestBed.runInInjectionContext(() => injectDispatch(applicationEvents));
  });

  afterEach(() => http.verify());

  function open(applications: readonly ApplicationView[]): void {
    dispatch.opened();
    http.expectOne('/api/applications').flush(applications);
    http.expectOne('/api/applications/lanes').flush(LANES);
  }

  it('groups the board by the lanes the server states, not by a copy of the enum', () => {
    open([application(), application({ id: 2, status: 'SENT' })]);

    expect(store.columns().map((column) => column.lane.id)).toEqual(['prepared', 'out']);
    expect(store.columns()[0]?.applications.map((a) => a.id)).toEqual([1]);
    expect(store.columns()[1]?.applications.map((a) => a.id)).toEqual([2]);
  });

  it('offers every state the lanes contain, in the order the usual path runs', () => {
    open([]);

    expect(store.statusChoices()).toEqual([
      { value: 'PACKAGED', label: 'Packaged' },
      { value: 'SENT', label: 'Sent' },
      { value: 'REPLIED', label: 'Replied' },
    ]);
  });

  it('replaces the row with the answer rather than with what was asked for', () => {
    open([application()]);

    dispatch.changed({ id: 1, update: { status: 'SENT' } });
    const request = http.expectOne('/api/applications/1');
    expect(request.request.method).toBe('PATCH');
    expect(store.saving()).toBe(1);

    // The server dated the send itself; a local guess would have shown yesterday's date
    // until the next reload.
    request.flush(application({ status: 'SENT', sentOn: '2026-09-01' }));

    expect(store.applications()[0]?.sentOn).toBe('2026-09-01');
    expect(store.saving()).toBeNull();
  });

  it('counts only what the server called due, and says so when it cannot count', () => {
    open([application({ followUpOn: '2026-08-30', followUpDue: true }), application({ id: 2 })]);
    expect(store.followUpsDue()).toBe(1);

    dispatch.changed({ id: 1, update: { status: 'SENT' } });
    http.expectOne('/api/applications/1').flush('nope', { status: 500, statusText: 'Error' });

    expect(store.error()).toContain('not saved');
    expect(store.saving()).toBeNull();
  });
});
