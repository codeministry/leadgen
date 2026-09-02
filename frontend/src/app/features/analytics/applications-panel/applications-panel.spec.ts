import { TestBed } from '@angular/core/testing';
import { ApplicationAnalytics } from '@core/model/analytics';
import { provideChartPalette } from '@core/theme/chart-theme';
import { ApplicationsPanel } from './applications-panel';

function analytics(sent: number, answered: number, median: number | null): ApplicationAnalytics {
  return {
    byStatus: [
      { status: 'SENT', applications: sent },
      { status: 'WON', applications: 0 },
    ],
    transitions: [],
    response: {
      sent,
      answered,
      backdated: 0,
      medianDaysToFirstReply: median,
      p90DaysToFirstReply: median,
      won: 0,
      lost: 0,
      rejected: 0,
    },
  };
}

describe('ApplicationsPanel', () => {
  beforeEach(() => TestBed.configureTestingModule({ providers: [provideChartPalette()] }));

  function render(data: ApplicationAnalytics) {
    const fixture = TestBed.createComponent(ApplicationsPanel);
    fixture.componentRef.setInput('applications', data);
    fixture.detectChanges();
    return fixture;
  }

  it('withholds a median computed over a handful', () => {
    // Four answers is a fact about four applications, not about the market. Printed as a
    // median it would be read as the second thing.
    const fixture = render(analytics(9, 4, 3));

    expect(fixture.nativeElement.textContent).not.toContain('median 3 days');
    expect(fixture.nativeElement.textContent).toContain('too few answers to average');
  });

  it('states the median once there is enough behind it', () => {
    const fixture = render(analytics(20, 8, 5));

    expect(fixture.nativeElement.textContent).toContain('median 5 days');
  });

  it('says the board is empty rather than drawing eleven zeroes', () => {
    const fixture = render(analytics(0, 0, null));

    expect(fixture.nativeElement.textContent).toContain('No application has been opened yet.');
  });
});
