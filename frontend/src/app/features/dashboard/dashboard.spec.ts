import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {ComponentFixture, TestBed} from '@angular/core/testing';
import {provideRouter} from '@angular/router';
import {LastRunStage, LastRunView} from '@core/model/last-run';
import {Dashboard} from './dashboard';

function stage(position: number, name: string, millis: number, status = 'OK', note: string | null = null): LastRunStage {
    const startedAt = Date.parse('2026-09-24T04:10:00Z') + position * 60_000;
    return {
        position,
        stage: name,
        startedAt: new Date(startedAt).toISOString(),
        endedAt: new Date(startedAt + millis).toISOString(),
        millis,
        status,
        note,
    };
}

function lastRun(overrides: Partial<LastRunView> = {}): LastRunView {
    return {
        finishedAt: '2026-09-24T04:13:00Z',
        status: 'COMPLETE',
        scoreModel: 'some-model',
        extracted: 169,
        written: 151,
        merged: 18,
        removed: {},
        filterConsidered: 169,
        filterPassed: 73,
        scored: 67,
        shortlisted: 7,
        review: 13,
        packaged: 7,
        digestWritten: true,
        sources: [],
        stages: [stage(0, 'DEDUPE', 200), stage(1, 'FILTER', 9_000), stage(2, 'ARCHIVE', 50)],
        ...overrides,
    };
}

/**
 * The last-run panel's stage table and its failure line.
 *
 * <p>The first spec this screen has had, and it exists for the branching the compiler cannot
 * check: which row is the slowest, which one failed, and whether the panel says where a failed
 * run stopped. Every other request the stores fire on start is answered empty.
 */
describe('Dashboard', () => {
    let http: HttpTestingController;

    beforeEach(() => {
        TestBed.configureTestingModule({
            providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
        });
        http = TestBed.inject(HttpTestingController);
    });

    function render(run: LastRunView): ComponentFixture<Dashboard> {
        const fixture = TestBed.createComponent(Dashboard);
        fixture.detectChanges();
        for (const request of http.match(() => true)) {
            const url = request.request.url;
            if (url === '/api/v1/ingest/last') {
                request.flush(run);
            } else if (url === '/api/v1/ingest/current') {
                request.flush(null, {status: 204, statusText: 'No Content'});
            } else if (url === '/api/v1/scoring-models') {
                request.flush({default: null, available: []});
            } else if (url === '/api/v1/applications/transitions') {
                request.flush({});
            } else if (url.startsWith('/api/v1/applications')) {
                request.flush([]);
            } else {
                request.flush(null, {status: 204, statusText: 'No Content'});
            }
        }
        fixture.detectChanges();
        return fixture;
    }

    function stageRows(fixture: ComponentFixture<Dashboard>): HTMLTableRowElement[] {
        return Array.from(fixture.nativeElement.querySelectorAll('tbody tr') as NodeListOf<HTMLTableRowElement>).filter(
            (row) => /^(DEDUPE|FILTER|ARCHIVE|ENRICH)$/.test(row.querySelector('th')?.textContent?.trim() ?? ''),
        );
    }

    it('lists every stage in run order and marks the slowest', () => {
        const fixture = render(lastRun());

        const rows = stageRows(fixture);
        expect(rows.map((row) => row.querySelector('th')?.textContent?.trim())).toEqual(['DEDUPE', 'FILTER', 'ARCHIVE']);
        expect(rows[1].classList).toContain('slowest-stage');
        expect(rows[1].textContent).toContain('slowest');
        expect(rows[0].classList).not.toContain('slowest-stage');
        // Milliseconds below a second, seconds above it: the unit a person reads it in.
        expect(rows[0].textContent).toContain('200');
        expect(rows[1].textContent).toContain('9');
    });

    it('says where a failed run stopped, and shows the reason on the stage that threw', () => {
        const fixture = render(
            lastRun({
                status: 'FAILED',
                stages: [
                    stage(0, 'DEDUPE', 200),
                    stage(1, 'FILTER', 9_000),
                    stage(2, 'ENRICH', 50, 'FAILED', 'portal down'),
                ],
            }),
        );

        const alert = fixture.nativeElement.querySelector('[role="alert"]') as HTMLElement;
        expect(alert.textContent).toContain('This run stopped in stage ENRICH');
        expect(alert.textContent).toContain('portal down');

        const failed = stageRows(fixture)[2];
        expect(failed.textContent).toContain('failed');
        expect(failed.textContent).toContain('portal down');
    });

    it('does not call a completed run failed because one of its sources failed', () => {
        // A dead mailbox is caught per source: a FAILED row under a run that went on to
        // complete. The run's own status decides the alert, never the timings.
        const fixture = render(
            lastRun({stages: [stage(0, 'DEDUPE', 200, 'FAILED', 'mailbox unreachable'), stage(1, 'FILTER', 300)]}),
        );

        expect(fixture.nativeElement.textContent).not.toContain('This run stopped');
        expect(stageRows(fixture)[0].textContent).toContain('mailbox unreachable');
    });

    it('draws no stage table for a run recorded before timings existed', () => {
        const fixture = render(lastRun({stages: []}));

        expect(fixture.nativeElement.textContent).not.toContain('Where the time went');
    });
});
