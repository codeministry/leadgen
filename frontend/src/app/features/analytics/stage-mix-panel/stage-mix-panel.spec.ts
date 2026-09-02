import { TestBed } from '@angular/core/testing';
import { AnalyticsFunnel, ScaleInUse, StageDay } from '@core/model/analytics';
import { provideChartPalette } from '@core/theme/chart-theme';
import { Granularity } from '../analytics-aggregate';
import { StageMixPanel } from './stage-mix-panel';

const FUNNEL: AnalyticsFunnel = {
  total: 100,
  survived: 40,
  stages: [
    { id: 'abroad', label: 'Abroad', removed: 30 },
    { id: 'stale', label: 'Older than the freshness limit', removed: 30 },
  ],
};

const MIX: readonly StageDay[] = [
  { day: '2026-08-24', stage: 'abroad', removed: 10 },
  { day: '2026-08-31', stage: 'abroad', removed: 20 },
  { day: '2026-08-31', stage: 'stale', removed: 30 },
];

function scale(ruleset: string, model: string): ScaleInUse {
  return { rulesetVersion: ruleset, scoreModel: model, offers: 10, firstScoredAt: null, lastScoredAt: null };
}

describe('StageMixPanel', () => {
  beforeEach(() => TestBed.configureTestingModule({ providers: [provideChartPalette()] }));

  function render(scales: readonly ScaleInUse[], mix: readonly StageDay[] = MIX, granularity: Granularity = 'day') {
    const fixture = TestBed.createComponent(StageMixPanel);
    fixture.componentRef.setInput('stageMix', mix);
    fixture.componentRef.setInput('funnel', FUNNEL);
    fixture.componentRef.setInput('scales', scales);
    fixture.componentRef.setInput('granularity', granularity);
    fixture.detectChanges();
    return fixture;
  }

  it('says on the chart itself that it is not history', () => {
    // A footnote at the bottom of the page is read by nobody who is looking at the bars.
    const fixture = render([scale('1', 'a')]);

    expect(fixture.nativeElement.textContent).toContain('today');
  });

  it('takes its stages and their order from the funnel, not from a list of its own', () => {
    const fixture = render([scale('1', 'a')]);

    const headers = [...fixture.nativeElement.querySelectorAll('thead th')].map((h: HTMLElement) =>
      h.textContent?.trim(),
    );
    expect(headers).toEqual(['From', 'Abroad', 'Older than the freshness limit']);
  });

  it('buckets the same way the intake chart does', () => {
    // Aggregated in SQL by week, this panel showed one bar while the chart above it showed
    // two days of the same archive. One rule, applied in one place.
    const fixture = render([scale('1', 'a')], MIX, 'week');
    const rows = [...fixture.nativeElement.querySelectorAll('tbody tr th')].map((h: HTMLElement) =>
      h.textContent?.trim(),
    );

    expect(rows).toEqual(['2026-08-24 – 2026-08-30', '2026-08-31 – 2026-09-06']);
  });

  it('fills a week a stage removed nothing in', () => {
    // A missing cell and a zero are the same fact, and only one of them can be read.
    const fixture = render([scale('1', 'a')]);
    const firstRow = fixture.nativeElement.querySelectorAll('tbody tr')[0];

    expect([...firstRow.querySelectorAll('td')].map((c: HTMLElement) => c.textContent?.trim())).toEqual(['10', '0']);
  });

  it('warns when the archive already holds two scales', () => {
    // Two rulesets or two judges mean every comparison across time reads two rulers.
    const fixture = render([scale('1', 'a'), scale('2', 'b')]);

    expect(fixture.nativeElement.textContent).toContain('2 different scales');
    expect(fixture.nativeElement.querySelector('.scales.mixed')).not.toBeNull();
  });

  it('says the caveat is harmless when there is only one scale', () => {
    const fixture = render([scale('1', 'claude-haiku-4-5')]);

    expect(fixture.nativeElement.textContent).toContain('One scale in the archive');
    expect(fixture.nativeElement.querySelector('.scales.mixed')).toBeNull();
  });
});
