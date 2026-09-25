import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {ComponentFixture, TestBed} from '@angular/core/testing';
import {provideRouter} from '@angular/router';
import {TranslocoService} from '@jsverse/transloco';
import {LastRunStage, LastRunView} from '@core/model/last-run';
import de from '../../../../public/i18n/de.json';
import {Dashboard} from './dashboard';

function stage(
    position: number,
    name: string,
    millis: number,
    status = 'OK',
    note: string | null = null,
    width: number | null = null,
): LastRunStage {
    const startedAt = Date.parse('2026-09-24T04:10:00Z') + position * 60_000;
    return {
        position,
        stage: name,
        startedAt: new Date(startedAt).toISOString(),
        endedAt: new Date(startedAt + millis).toISOString(),
        millis,
        status,
        note,
        width,
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
        enriched: 73,
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

    describe('the width a stage ran at', () => {
        function status(row: HTMLTableRowElement): string {
            return row.querySelector('.stage-status')?.textContent?.replace(/\s+/g, ' ').trim() ?? '';
        }

        it('shows the width instead of the dash on an OK row that ran wider than one', () => {
            const fixture = render(
                lastRun({stages: [stage(0, 'DEDUPE', 200, 'OK', 'width=4', 4), stage(1, 'FILTER', 9_000)]}),
            );

            const cell = status(stageRows(fixture)[0]);
            expect(cell).toBe('4 at once');
            expect(cell).not.toContain('—');
        });

        it('puts the width after the slowest badge when both apply', () => {
            const fixture = render(lastRun({stages: [stage(0, 'DEDUPE', 200), stage(1, 'FILTER', 9_000, 'OK', 'width=4', 4)]}));

            // The flex gap spaces them, so read the order off the children, not the joined text.
            const parts = Array.from(stageRows(fixture)[1].querySelector('.stage-status')!.children).map((el) =>
                el.textContent?.trim(),
            );
            expect(parts).toEqual(['slowest', '4 at once']);
        });

        it('changes nothing at width one or without a width', () => {
            const fixture = render(
                lastRun({
                    stages: [stage(0, 'DEDUPE', 200, 'OK', 'width=1', 1), stage(1, 'FILTER', 9_000), stage(2, 'ARCHIVE', 50)],
                }),
            );

            const rows = stageRows(fixture);
            expect(status(rows[0])).toBe('—');
            expect(status(rows[2])).toBe('—');
            expect(fixture.nativeElement.textContent).not.toContain('at once');
        });

        it('keeps a failed row on its reason and never shows a width there', () => {
            const fixture = render(
                lastRun({stages: [stage(0, 'DEDUPE', 200, 'FAILED', 'width=4 then the portal went down'), stage(1, 'FILTER', 9_000)]}),
            );

            const cell = status(stageRows(fixture)[0]);
            expect(cell).toContain('failed');
            expect(cell).toContain('width=4 then the portal went down');
            expect(cell).not.toContain('at once');
        });

        it('says it in German', () => {
            const transloco = TestBed.inject(TranslocoService);
            transloco.setTranslation(de, 'de');
            transloco.setActiveLang('de');
            const fixture = render(lastRun({stages: [stage(0, 'DEDUPE', 200, 'OK', 'width=4', 4), stage(1, 'FILTER', 9_000)]}));

            expect(status(stageRows(fixture)[0])).toBe('4 gleichzeitig');
            transloco.setActiveLang('en');
        });
    });

    it('draws no stage table for a run recorded before timings existed', () => {
        const fixture = render(lastRun({stages: []}));

        expect(fixture.nativeElement.textContent).not.toContain('Where the time went');
    });

    /**
     * The control room (spec 006): the hero, its quiet morning, the machine room's fold,
     * and the one request the small cells make.
     */
    describe('the control room', () => {
        const funnel = {
            total: 510,
            survived: 64,
            archived: 0,
            stages: [
                {id: 'abroad', label: 'Abroad', removed: 8},
                {id: 'remote', label: 'Remote share below the minimum', removed: 0},
                {id: 'reach', label: 'Beyond reach, not remote', removed: 293},
                {id: 'stack', label: 'Foreign stack or wrong role', removed: 52},
                {id: 'skill', label: 'No core skill', removed: 90},
                {id: 'contract', label: 'Contract form rejected', removed: 3},
            ],
        };
        const summary = {
            intake: Array.from({length: 14}, (_, i) => ({day: `2026-09-${String(11 + i).padStart(2, '0')}`, extracted: i, shortlisted: i % 3})),
            scoreBands: {shortlisted: 64, review: 3, discarded: 120, unscored: 14},
            lastRun: {finishedAt: '2026-09-24T04:13:00Z', failedStage: null, mismatches: 0},
        };

        function renderRoom(run: LastRunView | null, funnelView = funnel): ComponentFixture<Dashboard> {
            const fixture = TestBed.createComponent(Dashboard);
            fixture.detectChanges();
            for (const request of http.match(() => true)) {
                const url = request.request.url;
                if (url === '/api/v1/ingest/last') {
                    request.flush(run);
                } else if (url === '/api/v1/offers/funnel') {
                    request.flush(funnelView);
                } else if (url === '/api/v1/analytics/summary') {
                    request.flush(summary);
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

        it('leads with the survivor count in the signal and one primary button to the shortlist', () => {
            const fixture = renderRoom(lastRun());
            const figure = fixture.nativeElement.querySelector('lg-dashboard-hero .figure') as HTMLElement;
            expect(figure.textContent?.trim()).toBe('64');
            expect(figure.classList).toContain('text-signal');
            expect(fixture.nativeElement.querySelector('lg-dashboard-hero .sentence')?.textContent).toContain('510');
            const primaries = fixture.nativeElement.querySelectorAll('.btn-primary') as NodeListOf<HTMLAnchorElement>;
            expect(primaries.length).toBe(1);
            expect(primaries[0].getAttribute('href')).toBe('/shortlist');
            expect(fixture.nativeElement.querySelector('lg-dashboard-hero .chain')?.textContent).toContain('64');
        });

        it('says a quiet night in words and never as a number', () => {
            const fixture = renderRoom(lastRun({extracted: 0, written: 0}));
            const morning = fixture.nativeElement.querySelector('lg-dashboard-hero .morning') as HTMLElement;
            expect(morning.textContent).toContain('Nothing new overnight');
            expect(morning.classList).toContain('quiet');
            expect(morning.textContent).not.toMatch(/\b0\b/);
            // The standing count is still the standing count, labelled as such.
            expect(fixture.nativeElement.querySelector('lg-dashboard-hero .figure')?.textContent?.trim()).toBe('64');
        });

        it('asks for the summary and never for the analytics screen payload', () => {
            const fixture = TestBed.createComponent(Dashboard);
            fixture.detectChanges();
            const urls = http.match(() => true).map((request) => request.request.url);
            expect(urls).toContain('/api/v1/analytics/summary');
            expect(urls).not.toContain('/api/v1/analytics');
        });

        it('keeps the machine room closed on a healthy run', () => {
            // The stores are root singletons, so one run per test: a second render in the same
            // test reads the first run back and the fold does not move.
            const healthy = renderRoom(lastRun());
            expect((healthy.nativeElement.querySelector('details.machine-room') as HTMLDetailsElement).open).toBe(false);
            expect(healthy.nativeElement.querySelector('lg-stat-tile:last-of-type')?.textContent).toContain('Healthy');
            // The closed fold still says what it holds: the run's own numbers, one line.
            const preview = healthy.nativeElement.querySelector('.machine-summary .preview') as HTMLElement;
            expect(preview.textContent).toContain('169 new offers');
            expect(preview.textContent).toContain('some-model');
            expect(preview.textContent).toContain('9.3');
            // And four lines of the run's numbers under it, so the fold is not one thin bar.
            const facts = (healthy.nativeElement.querySelector('.machine-summary .facts') as HTMLElement).textContent;
            expect(facts).toContain('169 considered');
            expect(facts).toContain('slowest FILTER at 9');
            expect(facts).toContain('7 shortlisted');
            expect(facts).toContain('digest written');
        });

        it('opens the machine room on a failed run', () => {
            const failed = renderRoom(
                lastRun({status: 'FAILED', stages: [stage(0, 'DEDUPE', 200), stage(1, 'ENRICH', 50, 'FAILED', 'portal down')]}),
            );
            expect((failed.nativeElement.querySelector('details.machine-room') as HTMLDetailsElement).open).toBe(true);
            expect(failed.nativeElement.querySelector('lg-stat-tile:last-of-type')?.textContent).toContain('Failed');
        });

        it('opens the machine room when a source came up short', () => {
            const fixture = renderRoom(
                lastRun({sources: [{sourceId: 'portal-a', documents: 2, extracted: 3, written: 3, announced: 5, complete: false}]}),
            );
            expect((fixture.nativeElement.querySelector('details.machine-room') as HTMLDetailsElement).open).toBe(true);
            expect(fixture.nativeElement.querySelector('lg-stat-tile:last-of-type')?.textContent).toContain('1 source short');
        });

        it('names no pipeline word as a cell label', () => {
            const fixture = renderRoom(lastRun());
            const labels = Array.from(fixture.nativeElement.querySelectorAll('.bento .cell-label, .bento lg-stat-tile p.type-caption') as NodeListOf<HTMLElement>)
                .map((label) => label.textContent?.trim().toLowerCase() ?? '');
            expect(labels.length).toBeGreaterThanOrEqual(4);
            for (const label of labels) {
                expect(label).not.toMatch(/extracted|written|rows|documents|announced/);
            }
        });
    });
});
