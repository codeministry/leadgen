import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {TestBed} from '@angular/core/testing';
import {Dispatcher, Events} from '@ngrx/signals/events';
import {WorkflowView} from '@core/model/workflow';
import {configEvents} from './config.events';
import {ConfigStore} from './config.store';
import {ingestEvents} from './ingest.events';
import {shortlistEvents} from './shortlist.events';

const WORKFLOW: WorkflowView = {
    phases: [{id: 'sort', stages: []}],
    unread: [],
};

describe('ConfigStore', () => {
    let http: HttpTestingController;

    beforeEach(() => {
        TestBed.configureTestingModule({
            providers: [provideHttpClient(), provideHttpClientTesting()],
        });
        http = TestBed.inject(HttpTestingController);
    });

    afterEach(() => http.verify());

    function open(): {store: InstanceType<typeof ConfigStore>; asked: string[]} {
        const store = TestBed.inject(ConfigStore);
        const events = TestBed.inject(Events);
        const asked: string[] = [];
        events.on(ingestEvents.lastRunRequested).subscribe(() => asked.push('lastRun'));
        events.on(shortlistEvents.funnelOpened).subscribe(() => asked.push('funnel'));
        TestBed.inject(Dispatcher).dispatch(configEvents.rulesOpened());
        http.expectOne('/api/v1/rules').flush({});
        http.expectOne('/api/v1/prompts').flush([]);
        return {store, asked};
    }

    it('loads the workflow when the rules screen opens', () => {
        const {store} = open();

        http.expectOne('/api/v1/workflow').flush(WORKFLOW);

        expect(store.workflow()).toEqual(WORKFLOW);
    });

    it('asks for the last run and the funnel through their own stores, not with a request of its own', () => {
        const {asked} = open();
        http.expectOne('/api/v1/workflow').flush(WORKFLOW);

        expect(asked.sort()).toEqual(['funnel', 'lastRun']);
        // No `/ingest/last` or `/offers/funnel` here: `verify()` fails on either.
    });

    it('keeps a failed workflow as a catalog key on the rules screen', () => {
        const {store} = open();

        http.expectOne('/api/v1/workflow').flush('down', {status: 500, statusText: 'Server Error'});

        expect(store.workflow()).toBeNull();
        expect(store.rulesError()).toBe('error.workflowLoad');
    });
});
