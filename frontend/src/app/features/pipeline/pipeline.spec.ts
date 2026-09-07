import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {ChangeDetectionStrategy, Component, signal} from '@angular/core';
import {TestBed} from '@angular/core/testing';
import {provideRouter, Router} from '@angular/router';
import {RouterTestingHarness} from '@angular/router/testing';
import {ApplicationView, PipelineLane} from '@core/model/application';
import {SCORE_THRESHOLDS} from '@shared/shared.ports';

/**
 * Stands in for `OfferDetail` on the child route. The board's reuse is what is under test
 * here, and the real detail would fetch an offer of its own and say nothing about it.
 */
@Component({
  selector: 'lg-detail-stub',
  template: '<p>detail</p>',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
class DetailStub {
}

const LANES: readonly PipelineLane[] = [
  {id: 'open', label: 'Open', states: ['NEW', 'SHORTLISTED', 'PACKAGED']},
  {id: 'out', label: 'Out', states: ['SENT', 'REPLIED']},
];

const APPLICATION: ApplicationView = {
  id: 4,
  offerId: 7,
  status: 'NEW',
  title: 'Senior Java Entwickler',
  agency: null,
  portal: 'portal-a',
  url: 'https://example.invalid/7',
  scoreValue: 82,
  rateEur: null,
  packageDir: null,
  sentOn: null,
  followUpOn: null,
  followUpDue: false,
  outcome: null,
  note: null,
  updatedAt: '2026-09-01T08:00:00Z',
};

describe('Pipeline', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      providers: [
        provideRouter([
          {
            path: 'pipeline',
            loadComponent: () => import('./pipeline').then((m) => m.Pipeline),
            children: [{path: ':id', component: DetailStub}],
          },
        ]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {provide: SCORE_THRESHOLDS, useValue: signal({shortlistAt: 70, reviewAt: 50})},
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  /** The one pair of requests the board makes when it opens. */
  function flushBoard(): void {
    http.expectOne('/api/applications').flush([APPLICATION]);
    http.expectOne('/api/applications/lanes').flush(LANES);
  }

  async function openBoard(): Promise<RouterTestingHarness> {
    const harness = await RouterTestingHarness.create('/pipeline');
    flushBoard();
    harness.detectChanges();
    return harness;
  }

  it('keeps the board when a card is opened', async () => {
    // A child route rather than a second flat route on the same component: two `Route`
    // objects are two configurations, so the default reuse strategy destroys the board on
    // the first click and refetches it. The board is fetched in `ngOnInit`, so a second
    // request here is exactly that defect.
    const harness = await openBoard();

    await harness.navigateByUrl('/pipeline/7');
    harness.detectChanges();

    http.expectNone('/api/applications');
    http.expectNone('/api/applications/lanes');
    expect(harness.routeNativeElement?.textContent).toContain('detail');
  });

  it('leaves the status picker operable above the stretched card link', async () => {
    // The title link covers the whole card, so the picker has to sit above it. The CSS
    // half of that (`position: relative; z-index: 1`) cannot be asserted in jsdom; what
    // can is the structure it depends on — the picker is a sibling of the link and not a
    // descendant of it, because a `<select>` inside an anchor is unreachable whatever the
    // stacking order says.
    const harness = await openBoard();
    const card: HTMLElement = harness.routeNativeElement!.querySelector('.lane-card')!;
    const link: HTMLAnchorElement = card.querySelector('a')!;
    const picker: HTMLElement = card.querySelector('lg-status-picker')!;

    expect(link.contains(picker)).toBe(false);

    const select: HTMLSelectElement = picker.querySelector('select')!;
    select.value = 'SENT';
    select.dispatchEvent(new Event('change'));
    harness.detectChanges();

    // The picked status is saved, and picking one is not a navigation: the card's link
    // must not have been followed on the way.
    const patch = http.expectOne('/api/applications/4');
    expect(patch.request.method).toBe('PATCH');
    expect(patch.request.body).toEqual({status: 'SENT'});
    expect(TestBed.inject(Router).url).toBe('/pipeline');
  });

  afterEach(() => http.verify());
});
