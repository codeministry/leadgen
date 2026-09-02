import { TestBed } from '@angular/core/testing';
import { ScoreDistribution } from '@core/model/analytics';
import { provideChartPalette } from '@core/theme/chart-theme';
import { ScoresPanel } from './scores-panel';

function distribution(counts: number[], unscored = 0): ScoreDistribution {
  return {
    bucketSize: 10,
    buckets: counts.map((count, index) => ({ floor: index * 10, count })),
    unscored,
    shortlistAt: 70,
    reviewAt: 50,
  };
}

describe('ScoresPanel', () => {
  beforeEach(() => TestBed.configureTestingModule({ providers: [provideChartPalette()] }));

  function render(scores: ScoreDistribution) {
    const fixture = TestBed.createComponent(ScoresPanel);
    fixture.componentRef.setInput('scores', scores);
    fixture.detectChanges();
    return fixture;
  }

  it('names every band, including the ones nothing fell into', () => {
    // A gap in the middle of a distribution is information: it says the judge never awards
    // that range, which is a fact about the judge.
    const fixture = render(distribution([2, 0, 0, 0, 0, 0, 0, 0, 0, 1]));

    expect(fixture.nativeElement.textContent).toContain('0–9');
    expect(fixture.nativeElement.textContent).toContain('90–99');
  });

  it('keeps the unscored out of the histogram and says so in words', () => {
    // Unscored is not a low score. Folded into the first bucket it would look like one.
    const fixture = render(distribution([1, 0, 0, 0, 0, 0, 0, 0, 0, 0], 12));

    expect(fixture.nativeElement.textContent).toContain('12 offers were left unscored');
  });

  it('says nothing has been scored rather than drawing an empty histogram', () => {
    const fixture = render(distribution([0, 0, 0, 0, 0, 0, 0, 0, 0, 0]));

    expect(fixture.nativeElement.querySelector('lg-histogram-chart')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('Nothing has been scored yet.');
  });
});
