import {TestBed} from '@angular/core/testing';
import {RunSeries} from '@core/model/analytics';
import {provideChartPalette} from '@core/theme/chart-theme';
import {RunsPanel} from './runs-panel';

function series(passes: RunSeries['passes'], since: string | null): RunSeries {
    return {days: [], passes, historySince: since};
}

function pass(finishedAt: string, extracted: number): RunSeries['passes'][number] {
    return {
        finishedAt,
        status: 'COMPLETE',
        rulesetVersion: '1',
        scoreModel: 'a-model',
        extracted,
        written: extracted,
        filterConsidered: extracted,
        filterPassed: 10,
        scored: 10,
        shortlisted: 2,
        packaged: 2,
    };
}

describe('RunsPanel', () => {
    beforeEach(() => TestBed.configureTestingModule({providers: [provideChartPalette()]}));

    function render(runs: RunSeries) {
        const fixture = TestBed.createComponent(RunsPanel);
        fixture.componentRef.setInput('runs', runs);
        fixture.detectChanges();
        return fixture;
    }

    it('says when the history starts, because it cannot be filled in backwards', () => {
        // A chart that silently starts three weeks ago implies nothing happened before it.
        const fixture = render(series([pass('2026-09-01T10:00:00Z', 100)], '2026-09-01T10:00:00Z'));

        expect(fixture.nativeElement.textContent).toContain('Run history starts on 2026-09-01');
    });

    it('states that the next run begins the record, rather than showing an empty chart', () => {
        const fixture = render(series([], null));

        expect(fixture.nativeElement.querySelector('lg-chart-surface')).toBeNull();
        expect(fixture.nativeElement.textContent).toContain('cannot be filled in backwards');
    });

    it("shows a run in the reader's own clock, not in the database's", () => {
        // finishedAt is UTC. Sliced rather than formatted, a run at 08:47 local read as 06:47,
        // while every other time on the screen was local — the pipeline looked two hours early.
        const fixture = render(series([pass('2026-09-02T06:47:00Z', 100)], '2026-09-02T06:47:00Z'));
        const local = new Intl.DateTimeFormat('en', {dateStyle: 'short', timeStyle: 'short'}).format(
            new Date('2026-09-02T06:47:00Z'),
        );

        expect(fixture.nativeElement.textContent).toContain(local);
        expect(fixture.nativeElement.textContent).not.toContain('2026-09-02 06:47');
    });

    it('lists the newest run first, while the chart keeps running forwards in time', () => {
        // Two different orders on purpose: a reader scanning the table wants today's run at the
        // top, and a line chart drawn newest-first shows a rise as a decline.
        const fixture = render(
            series(
                [
                    pass('2026-09-01T10:00:00Z', 100),
                    pass('2026-09-03T10:00:00Z', 300),
                    pass('2026-09-02T10:00:00Z', 200),
                ],
                '2026-09-01T10:00:00Z',
            ),
        );

        const extracted = Array.from(
            fixture.nativeElement.querySelectorAll('tbody tr td:first-of-type'),
        ).map((cell) => (cell as HTMLElement).textContent?.trim());

        expect(extracted).toEqual(['300', '200', '100']);
    });

    it('names the scale each run ran under', () => {
        // Two runs under two rulesets on one line look comparable and are not.
        const fixture = render(series([pass('2026-09-01T10:00:00Z', 100)], '2026-09-01T10:00:00Z'));

        expect(fixture.nativeElement.textContent).toContain('1 · a-model');
    });
});
