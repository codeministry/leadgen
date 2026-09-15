import {TestBed} from '@angular/core/testing';
import {Events, injectDispatch} from '@ngrx/signals/events';
import {beforeEach, describe, expect, it} from 'vitest';
import {ingestEvents} from '@core/store/ingest.events';
import {CurrentRunView} from '@core/model/current-run';
import {refreshEvents, RefreshReason} from './refresh.events';
import {RefreshStore} from './refresh.store';

function inFlight(stage: string): CurrentRunView {
  return {
    id: 31,
    startedAt: '2026-09-15T07:17:35Z',
    scoreModel: 'gpt-oss:20b',
    stage,
    stagePosition: 4,
    stageTotal: 11,
    stageStartedAt: null,
  };
}

/**
 * The one place that decides when the data has probably moved. Five stores acted on
 * `ingestEvents.finished` already and the machinery was never the problem: that event fires
 * only for a pass this browser started, so a nightly CronJob, another tab or a second machine
 * left every screen showing what it had read once.
 */
describe('RefreshStore', () => {
  let dispatch: ReturnType<typeof injectDispatch<typeof ingestEvents>>;
  let seen: RefreshReason[];

  beforeEach(() => {
    TestBed.configureTestingModule({});
    TestBed.inject(RefreshStore);
    dispatch = TestBed.runInInjectionContext(() => injectDispatch(ingestEvents));
    seen = [];
    TestBed.inject(Events)
      .on(refreshEvents.requested)
      .subscribe(({payload}) => seen.push(payload));
  });

  it('says the data moved when a pass ends', () => {
    dispatch.currentLoaded(inFlight('ENRICH'));
    expect(seen).toEqual([]);

    dispatch.currentLoaded(null);

    expect(seen).toEqual(['run-ended']);
  });

  it('says nothing while a pass is merely getting on with it', () => {
    // The transition is the news, not the state. A stage change every five seconds must
    // not make every store on every screen re-read its data.
    dispatch.currentLoaded(inFlight('ENRICH'));
    dispatch.currentLoaded(inFlight('CONTENT'));
    dispatch.currentLoaded(inFlight('SCORE'));

    expect(seen).toEqual([]);
  });

  it('says nothing when nothing was running to begin with', () => {
    // The heartbeat answers null on a quiet browser every thirty seconds, forever.
    dispatch.currentLoaded(null);
    dispatch.currentLoaded(null);

    expect(seen).toEqual([]);
  });
});
