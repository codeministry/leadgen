import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {TestBed} from '@angular/core/testing';
import {injectDispatch} from '@ngrx/signals/events';
import {IngestReport} from '@core/api/ingest.api';
import {LastRunView} from '@core/model/last-run';
import {refreshEvents} from '@core/refresh/refresh.events';
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
                    {documentId: '2026-09-02-27.eml', extracted: 26, announced: 27, complete: false},
                ],
            },
        ],
        extracted: 26,
        written: 26,
        merged: 18,
        filtered: {removed: {ABROAD: 2}, passed: 12, considered: 26},
        enriched: {considered: 12, enriched: 0, incomplete: 12, fromCache: 0, requests: 0, deferred: 0},
        scored: {considered: 12, scored: 12, unscored: 0, shortlisted: 2, review: 3, unusable: 0, submitted: 0},
        digest: null,
      opened: {standing: 12, opened: 2},
      packaged: {due: 0, built: 0, failed: 0, folders: []},
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
        enriched: 73,
        removed: {ABROAD: 13, ROLE_OR_STACK: 55},
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
        stages: [
            {
                position: 0,
                stage: 'DEDUPE',
                startedAt: '2026-09-02T04:10:00Z',
                endedAt: '2026-09-02T04:10:02Z',
                millis: 2000,
                status: 'OK',
                note: null,
            },
        ],
        ...overrides,
    };
}

describe('IngestStore', () => {
    let store: InstanceType<typeof IngestStore>;
    let http: HttpTestingController;
    let dispatch: ReturnType<typeof injectDispatch<typeof ingestEvents>>;
  let refresh: ReturnType<typeof injectDispatch<typeof refreshEvents>>;

    beforeEach(() => {
        TestBed.configureTestingModule({
            providers: [provideHttpClient(), provideHttpClientTesting()],
        });
        // Injecting the store is what creates it, and creating it is what asks the question.
        store = TestBed.inject(IngestStore);
        http = TestBed.inject(HttpTestingController);
        dispatch = TestBed.runInInjectionContext(() => injectDispatch(ingestEvents));
      refresh = TestBed.runInInjectionContext(() => injectDispatch(refreshEvents));
        // Not this store's request: its event handlers inject `ScoringModelStore`, which loads
        // its own list the moment it is created. Answered here so `verify` speaks only about
        // the requests this spec is actually about.
        http
            .expectOne('/api/v1/scoring-models')
            .flush({available: ['claude-haiku-4-5'], preferred: 'claude-haiku-4-5'});
      // The heartbeat asks once the store exists. Answered here rather than per test: no
      // case below is about a run in flight, and `verify` counts it either way. The next
      // beat is thirty seconds out, so nothing fires again inside a test.
      http.expectOne('/api/v1/ingest/current').flush(null, {status: 204, statusText: 'No Content'});
    });

    afterEach(() => {
        vi.useRealTimers();
        http.verify();
    });

    it('asks what ran last as soon as it exists, without anyone opening a screen', () => {
        // The defect this replaces: the dashboard knew about a run only if this browser had
        // started one, so a scheduled pass six minutes old read as "No run yet".
        http.expectOne('/api/v1/ingest/last').flush(lastRun());

        expect(store.lastRun()?.extracted).toBe(169);
        expect(store.showingRecordedRun()).toBe(true);
    });

    it('treats an empty answer as no run rather than as a run of zero', () => {
        // 204, which Angular hands over as a null body. A run with every count at zero is a
        // different fact, and the tiles say different things about the two.
        http.expectOne('/api/v1/ingest/last').flush(null, {status: 204, statusText: 'No Content'});

        expect(store.lastRun()).toBeNull();
        expect(store.showingRecordedRun()).toBe(false);
    });

    it('steps aside once this browser has run one itself', () => {
        // Both are kept, and the screen shows the fresher one: a report carries the
        // per-document breakdown a recorded run cannot, so replacing one with the other would
        // lose the only thing that names which document came up short.
        http.expectOne('/api/v1/ingest/last').flush(lastRun());
        dispatch.requested();
        // The request asks the heartbeat once, at once; answered so `verify` counts only
        // the run itself.
        http.expectOne('/api/v1/ingest/current').flush(null, {status: 204, statusText: 'No Content'});
        // Matched by method and path: the run carries the chosen model as a query parameter,
        // so a plain URL match misses it.
        http
            .expectOne((request) => request.method === 'POST' && request.url === '/api/v1/ingest')
            .flush(report());

        expect(store.showingRecordedRun()).toBe(false);
        expect(store.lastRun()?.extracted).toBe(169);
        expect(store.mismatches()).toHaveLength(1);
        expect(store.mismatches()[0]?.document.documentId).toBe('2026-09-02-27.eml');
    });

  it('shows the refusal the server wrote rather than one of its own', () => {
    // The store asks this on creation, and `verify` counts it. Answered first so the
    // spec speaks only about the run it is actually about.
    http.expectOne('/api/v1/ingest/last').flush(null);
    // `exhaustMap` stops a second click from this browser and nothing else. A scheduled
    // pass, another tab or a second machine all answer 409 with a sentence saying what
    // happened and what it did not do, and "The ingest run did not answer" said neither.
    dispatch.requested();
        // The request asks the heartbeat once, at once; answered so `verify` counts only
        // the run itself.
        http.expectOne('/api/v1/ingest/current').flush(null, {status: 204, statusText: 'No Content'});
    http
      .expectOne((request) => request.url === '/api/v1/ingest')
      .flush('an ingest run is already in progress; this one was not started', {
        status: 409,
        statusText: 'Conflict',
      });

    expect(store.error()).toBe('an ingest run is already in progress; this one was not started');
    expect(store.running()).toBe(false);
  });

  it('falls back to the catalog when the server said nothing at all', () => {
    // The store asks this on creation, and `verify` counts it. Answered first so the
    // spec speaks only about the run it is actually about.
    http.expectOne('/api/v1/ingest/last').flush(null);
    // A network failure carries no sentence, and a raw `null` on screen is worse than a
    // generic line. The same pair `serverMessage` is written for.
    dispatch.requested();
        // The request asks the heartbeat once, at once; answered so `verify` counts only
        // the run itself.
        http.expectOne('/api/v1/ingest/current').flush(null, {status: 204, statusText: 'No Content'});
    http
      .expectOne((request) => request.url === '/api/v1/ingest')
      .error(new ProgressEvent('network'));

    expect(store.error()).toBe('error.ingestRun');
  });

  it('asks the heartbeat the moment a run is requested, and keeps asking fast while it is out', () => {
    // The run toast is raised from the heartbeat's first sight of a run, never from the
    // request, so one path covers a click here and a CronJob elsewhere. Without this the
    // operator's own toast would arrive at the idle cadence, up to thirty seconds late.
    vi.useFakeTimers();
    http.expectOne('/api/v1/ingest/last').flush(null, {status: 204, statusText: 'No Content'});

    dispatch.requested();
    http.expectOne('/api/v1/ingest/current').flush(null, {status: 204, statusText: 'No Content'});

    // Still nothing running as far as the server says, but this browser is waiting on its
    // own request: the next beat comes at the fast cadence, not after thirty seconds.
    vi.advanceTimersByTime(5_000);
    http.expectOne('/api/v1/ingest/current').flush(null, {status: 204, statusText: 'No Content'});

    http.expectOne((request) => request.method === 'POST' && request.url === '/api/v1/ingest').flush(report());
    expect(store.running()).toBe(false);
    vi.useRealTimers();
  });

  it('knows about a pass nobody in this browser started', () => {
    // `running` says only whether this browser is waiting on its own request, so a
    // nightly pass, another tab or a second machine left the button enabled and the
    // screen silent. `busy` is what the button reads instead.
    http.expectOne('/api/v1/ingest/last').flush(null, {status: 204, statusText: 'No Content'});
    expect(store.busy()).toBe(false);

    dispatch.currentLoaded({
      id: 31,
      startedAt: '2026-09-15T07:17:35Z',
      scoreModel: 'gpt-oss:20b',
      stage: 'ENRICH',
      stagePosition: 4,
      stageTotal: 9,
      stageStartedAt: '2026-09-15T07:20:00Z',
    });

    expect(store.busy()).toBe(true);
    expect(store.current()?.stage).toBe('ENRICH');
  });

  it('reads what a pass left behind when something says the data moved', () => {
    // The transition that says a pass ended is noticed by `RefreshStore` — it is news to
    // more than this store — and raised there as one signal. This store's part is the
    // consequence: read the history back, or the dashboard shows last night's numbers
    // until somebody reloads.
    http.expectOne('/api/v1/ingest/last').flush(null, {status: 204, statusText: 'No Content'});

    refresh.requested('run-ended');

    http.expectOne('/api/v1/ingest/last').flush(lastRun());
    expect(store.lastRun()).not.toBeNull();
  });

    it('does not blank the screen when the history cannot be read', () => {
        // Deliberately not written into `error`: that one blanks the run panel, and a
        // dashboard that cannot reach the history is still a dashboard.
        http.expectOne('/api/v1/ingest/last').flush('nope', {status: 500, statusText: 'Server Error'});

        expect(store.lastRun()).toBeNull();
        expect(store.error()).toBeNull();
    });
});
