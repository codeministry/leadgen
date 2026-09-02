import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AnalyticsView, IntakeDay } from '@core/model/analytics';
import { provideChartPalette } from '@core/theme/chart-theme';
import { Analytics } from './analytics';

function day(date: string, primaries: number, passed: number): IntakeDay {
  return {
    day: date,
    primaries,
    duplicates: 0,
    passed,
    shortlisted: passed,
    review: 0,
    discarded: 0,
    unscored: 0,
  };
}

const VIEW: AnalyticsView = {
  zone: 'Europe/Berlin',
  generatedAt: '2026-09-02T00:00:00Z',
  from: '2026-08-31',
  to: '2026-09-07',
  funnel: { total: 241, survived: 36, stages: [{ id: 'abroad', label: 'Abroad', removed: 9 }] },
  intake: {
    byIngestedAt: [day('2026-08-31', 100, 10), day('2026-09-01', 141, 26)],
    byPublishedOn: [day('2026-08-31', 90, 9), day('2026-09-07', 151, 27)],
    byReceivedAt: [day('2026-08-30', 60, 6), day('2026-09-06', 181, 30)],
    withoutPublishedOn: 0,
    publishedOutOfRange: 0,
    withoutReceivedAt: 0,
  },
  market: { portals: [], tags: [], locations: [], reach: { outOfReach: 0, abroad: 0, remoteShare: 0 }, stageMix: [] },
  scores: { bucketSize: 10, buckets: [], unscored: 0, shortlistAt: 70, reviewAt: 50 },
  applications: {
    byStatus: [],
    transitions: [],
    response: {
      sent: 4,
      answered: 1,
      backdated: 0,
      medianDaysToFirstReply: 2,
      p90DaysToFirstReply: 2,
      won: 0,
      lost: 0,
      rejected: 1,
    },
  },
  runs: { days: [], passes: [], historySince: null },
  scales: [],
};

describe('Analytics', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting(), provideChartPalette()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  function render(view: AnalyticsView = VIEW): ComponentFixture<Analytics> {
    const fixture = TestBed.createComponent(Analytics);
    fixture.detectChanges();
    http.expectOne('/api/analytics').flush(view);
    fixture.detectChanges();
    return fixture;
  }

  it('reads the mail axis by default, because that is the market and not the reader', () => {
    // The ingest axis counts how often the tool was run and moves for every row when the
    // database is refilled; the published axis is only as good as what each advert states.
    const fixture = render();

    // Two arrival days a week apart, bucketed by week: two bars, 60 and 181.
    const text = fixture.nativeElement.textContent;
    expect(text).toContain('60');
    expect(text).toContain('181');
  });

  it('says how much did not come in the post when that axis is chosen', () => {
    const fixture = render({ ...VIEW, intake: { ...VIEW.intake, withoutReceivedAt: 4 } });

    expect(fixture.nativeElement.textContent).toContain('4 did not come in the post');
  });

  it('follows the axis in the query string', () => {
    const fixture = render();
    fixture.componentRef.setInput('axis', 'ingested');
    fixture.componentRef.setInput('by', 'week');
    fixture.detectChanges();

    // Both ingest days fall in one week, so they sum: 241.
    expect(fixture.nativeElement.textContent).toContain('241');
  });

  it('survives a URL with no parameters at all', () => {
    // Router input binding writes undefined over the declared default, and the first read
    // of it throws inside the template, leaving the page half-rendered.
    const fixture = render();
    fixture.componentRef.setInput('axis', undefined);
    fixture.componentRef.setInput('by', undefined);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('lg-intake-panel')).not.toBeNull();
  });

  it('says nothing rather than zero when no application has been sent', () => {
    // A rate over nothing is unknown, not zero — and a zero would read as "nobody answers".
    const fixture = render({
      ...VIEW,
      applications: { ...VIEW.applications, response: { ...VIEW.applications.response, sent: 0, answered: 0 } },
    });

    expect(fixture.nativeElement.textContent).toContain('—');
  });

  it('names what the window leaves off the published axis', () => {
    // A window that drops offers without saying so is the same failure as a selector that
    // quietly stopped matching: the chart looks right and the archive is bigger than it.
    const fixture = render({
      ...VIEW,
      intake: { ...VIEW.intake, withoutPublishedOn: 3, publishedOutOfRange: 9 },
    });
    fixture.componentRef.setInput('axis', 'published');
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('3 state no date and 9 are older');
  });

  it('says nothing about exclusions on the arrival axis, where there are none', () => {
    const fixture = render({
      ...VIEW,
      intake: { ...VIEW.intake, withoutPublishedOn: 3, publishedOutOfRange: 9 },
    });
    fixture.componentRef.setInput('axis', 'ingested');
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).not.toContain('older than the window');
  });

  it('offers the other axis when the chosen one carries no dates', () => {
    const fixture = render({
      ...VIEW,
      intake: { ...VIEW.intake, byReceivedAt: [], withoutReceivedAt: 241 },
    });

    expect(fixture.nativeElement.querySelector('lg-empty-state')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('lg-intake-panel')).toBeNull();
  });

  it('asks somebody to run the pipeline when nothing has run at all', () => {
    const fixture = TestBed.createComponent(Analytics);
    fixture.detectChanges();
    http.expectOne('/api/analytics').flush(null, { status: 500, statusText: 'nope' });
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('The analytics did not load.');
  });
});
