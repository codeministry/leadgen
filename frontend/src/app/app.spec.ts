import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {TestBed} from '@angular/core/testing';
import {provideRouter} from '@angular/router';
import {App} from './app';
import {routes} from './app.routes';

describe('App', () => {
    let httpMock: HttpTestingController;

    beforeEach(async () => {
        await TestBed.configureTestingModule({
            imports: [App],
            providers: [provideRouter(routes), provideHttpClient(), provideHttpClientTesting()],
        }).compileComponents();

        httpMock = TestBed.inject(HttpTestingController);
    });

    it('asks the API for its status on init and renders it', async () => {
        const fixture = TestBed.createComponent(App);
        fixture.detectChanges();

      httpMock.expectOne('/api/v1/status').flush({application: 'lead-generation', version: '0.5.3'});
        // Run ingest and the model choice left the header for the workflow screen (operator,
        // 2026-09-27), so the shell does not create the scoring-model store: the model list is
        // not asked for here. The ingest store is created at the root (fix 2F-5), so its
        // heartbeat and its last run are asked once on every screen. `verify()` holds that too.
        httpMock.expectNone('/api/v1/scoring-models');
        httpMock.expectOne('/api/v1/ingest/last').flush(null);
        httpMock.expectOne('/api/v1/ingest/current').flush(null);
        // The header asks once whether a chat model is configured (spec 019); absent is the shipped answer.
        httpMock.expectOne('/api/v1/chat/capability').flush({present: false});
        await fixture.whenStable();
        fixture.detectChanges();

      expect(fixture.nativeElement.textContent).toContain('lead-generation 0.5.3');
        httpMock.verify();
    });

    // Fix 2F-5: the run control moved to the workflow screen, and with it the only injector of
    // `IngestStore` outside the dashboard — so on every other screen the heartbeat, the
    // run-ended refresh and the run toast never started.
    it('starts the run heartbeat on a screen without a run control', async () => {
        const fixture = TestBed.createComponent(App);
        fixture.detectChanges();
        await fixture.whenStable();

        expect(httpMock.match('/api/v1/ingest/current')).toHaveLength(1);
    });
});
