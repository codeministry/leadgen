import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {TestBed} from '@angular/core/testing';
import {injectDispatch} from '@ngrx/signals/events';
import {IngestReport} from '@core/api/ingest.api';
import {LastRunView} from '@core/model/last-run';
import {ingestEvents} from './ingest.events';
import {IngestStore} from './ingest.store';

/** A run this browser started, carrying the one thing a recorded run cannot: the document. */
function report(): IngestReport {
  return {
    sources: [
      {
        sourceId: 'demo-newsletter',
        documents: 1,
        extracted: 26,
        written: 26,
        details: [
          { documentId: '2026-09-02-27.eml', extracted: 26, announced: 27, complete: false },
        ],
      },
    ],
    extracted: 26,
    written: 26,
    merged: 18,
    filtered: { removed: { ABROAD: 2 }, passed: 12, considered: 26 },
      enriched: {considered: 12, enriched: 0, incomplete: 12, fromCache: 0, requests: 0, deferred: 0},
    scored: { considered: 12, scored: 12, unscored: 0, shortlisted: 2, review: 3, submitted: 0 },
    digest: null,
    packaged: { due: 2, built: 2, failed: 0, folders: [] },
      finishedAt: '2026-09-05T06:12:00Z',
  };
}

function lastRun(overrides: Partial<LastRunView> = {}): LastRunView {
  return {
    finishedAt: '2026-09-02T04:12:00Z',
    status: 'COMPLETE',
    scoreModel: 'claude-haiku-4-5',
    extracted: 169,
    written: 151,
    merged: 18,
    removed: { ABROAD: 13, ROLE_OR_STACK: 55 },
    filterConsidered: 169,
    filterPassed: 73,
    scored: 67,
    shortlisted: 7,
    review: 13,
    packaged: 7,
    digestWritten: true,
    sources: [
      {
        sourceId: 'demo-newsletter',
        documents: 5,
        extracted: 169,
        written: 151,
        announced: 169,
        complete: true,
      },
    ],
    ...overrides,
  };
}

describe('IngestStore', () => {
  let store: InstanceType<typeof IngestStore>;
  let http: HttpTestingController;
  let dispatch: ReturnType<typeof injectDispatch<typeof ingestEvents>>;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    // Injecting the store is what creates it, and creating it is what asks the question.
    store = TestBed.inject(IngestStore);
    http = TestBed.inject(HttpTestingController);
    dispatch = TestBed.runInInjectionContext(() => injectDispatch(ingestEvents));
    // Not this store's request: its event handlers inject `ScoringModelStore`, which loads
    // its own list the moment it is created. Answered here so `verify` speaks only about
    // the requests this spec is actually about.
    http
      .expectOne('/api/scoring-models')
      .flush({ available: ['claude-haiku-4-5'], preferred: 'claude-haiku-4-5' });
  });

  afterEach(() => http.verify());

  it('asks what ran last as soon as it exists, without anyone opening a screen', () => {
    // The defect this replaces: the dashboard knew about a run only if this browser had
    // started one, so a scheduled pass six minutes old read as "No run yet".
    http.expectOne('/api/ingest/last').flush(lastRun());

    expect(store.lastRun()?.extracted).toBe(169);
    expect(store.showingRecordedRun()).toBe(true);
  });

  it('treats an empty answer as no run rather than as a run of zero', () => {
    // 204, which Angular hands over as a null body. A run with every count at zero is a
    // different fact, and the tiles say different things about the two.
    http.expectOne('/api/ingest/last').flush(null, { status: 204, statusText: 'No Content' });

    expect(store.lastRun()).toBeNull();
    expect(store.showingRecordedRun()).toBe(false);
  });

  it('steps aside once this browser has run one itself', () => {
    // Both are kept, and the screen shows the fresher one: a report carries the
    // per-document breakdown a recorded run cannot, so replacing one with the other would
    // lose the only thing that names which document came up short.
    http.expectOne('/api/ingest/last').flush(lastRun());
    dispatch.requested();
    // Matched by method and path: the run carries the chosen model as a query parameter,
    // so a plain URL match misses it.
    http
      .expectOne((request) => request.method === 'POST' && request.url === '/api/ingest')
      .flush(report());

    expect(store.showingRecordedRun()).toBe(false);
    expect(store.lastRun()?.extracted).toBe(169);
    expect(store.mismatches()).toHaveLength(1);
    expect(store.mismatches()[0]?.document.documentId).toBe('2026-09-02-27.eml');
  });

  it('does not blank the screen when the history cannot be read', () => {
    // Deliberately not written into `error`: that one blanks the run panel, and a
    // dashboard that cannot reach the history is still a dashboard.
    http.expectOne('/api/ingest/last').flush('nope', { status: 500, statusText: 'Server Error' });

    expect(store.lastRun()).toBeNull();
    expect(store.error()).toBeNull();
  });
});
