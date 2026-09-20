import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {CdkDragDrop, CdkDropList} from '@angular/cdk/drag-drop';
import {ChangeDetectionStrategy, Component, DebugElement, signal} from '@angular/core';
import {TestBed} from '@angular/core/testing';
import {By} from '@angular/platform-browser';
import {provideRouter, Router} from '@angular/router';
import {RouterTestingHarness} from '@angular/router/testing';
import {ApplicationStatus, ApplicationView, PipelineLane} from '@core/model/application';
import {TRANSITIONS} from '@core/model/transitions.fixture';
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
    http.expectOne('/api/v1/applications').flush([APPLICATION]);
    http.expectOne('/api/v1/applications/lanes').flush(LANES);
    http.expectOne('/api/v1/applications/transitions').flush(TRANSITIONS);
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

    http.expectNone('/api/v1/applications');
    http.expectNone('/api/v1/applications/lanes');
    expect(harness.routeNativeElement?.textContent).toContain('detail');
  });

  it('closes the detail on Escape by deselecting the offer', async () => {
    // The reading column exists only while something is being read, so closing it is what
    // gives the lanes their width back. A navigation and not a flag: the selection is the
    // URL, and a second copy would disagree with it the first time the back button is used.
    const harness = await openBoard();

    await harness.navigateByUrl('/pipeline/7');
    harness.detectChanges();

    harness.routeNativeElement!.dispatchEvent(
      new KeyboardEvent('keydown', {key: 'Escape', bubbles: true}),
    );
    await harness.fixture.whenStable();
    harness.detectChanges();

    expect(TestBed.inject(Router).url).toBe('/pipeline');
    // Closing is a navigation on this screen and nothing else: the board is not refetched.
    http.expectNone('/api/v1/applications');
  });

  it('leaves Escape alone while nothing is open, and inside a status picker', async () => {
    const harness = await openBoard();

    harness.routeNativeElement!.dispatchEvent(
      new KeyboardEvent('keydown', {key: 'Escape', bubbles: true}),
    );
    await harness.fixture.whenStable();
    expect(TestBed.inject(Router).url).toBe('/pipeline');

    await harness.navigateByUrl('/pipeline/7');
    harness.detectChanges();

    // Every card carries a native select, and a browser showing its dropdown has its own
    // answer for Escape. The detail must not be closed out from under it.
    const select: HTMLSelectElement = harness
      .routeNativeElement!.querySelector('lg-status-picker select')!;
    select.dispatchEvent(new KeyboardEvent('keydown', {key: 'Escape', bubbles: true}));
    await harness.fixture.whenStable();

    expect(TestBed.inject(Router).url).toBe('/pipeline/7');
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
    const patch = http.expectOne('/api/v1/applications/4');
    expect(patch.request.method).toBe('PATCH');
    expect(patch.request.body).toEqual({status: 'SENT'});
    expect(TestBed.inject(Router).url).toBe('/pipeline');
  });

  it('offers one drop zone per state, not one per lane', async () => {
    // Four of the five real lanes hold more than one state and `closed` begins at `WON`, so
    // a lane-wide target would have to guess — and would mark a dropped card won.
    const harness = await openBoard();
    const zones = harness.fixture.debugElement.queryAll(By.directive(CdkDropList));

    expect(zones.length).toBe(5);
    expect(zones.map((zone) => zone.injector.get(CdkDropList).data)).toEqual([
      'NEW',
      'SHORTLISTED',
      'PACKAGED',
      'SENT',
      'REPLIED',
    ]);
  });

  it('writes the zone\'s state when a card is let go of in another one', async () => {
    // jsdom cannot produce a real CDK drag, so the drop is emitted on the directive the
    // template binds to. What is under test is the write it causes, and that is real.
    const harness = await openBoard();
    const zones = harness.fixture.debugElement.queryAll(By.directive(CdkDropList));

    drop(zones[0]!, zones[3]!);
    harness.detectChanges();

    const patch = http.expectOne('/api/v1/applications/4');
    expect(patch.request.method).toBe('PATCH');
    expect(patch.request.body).toEqual({status: 'SENT'});
    // The card is in the target zone before the answer is flushed.
    expect(zones[3]!.nativeElement.textContent).toContain('Senior Java Entwickler');
    patch.flush({...APPLICATION, status: 'SENT', sentOn: '2026-09-17'});
  });

  it('asks for nothing when a card is let go of in the zone it came from', async () => {
    // The server records no event row for a status that did not change, so the round trip
    // would buy a card greying out and nothing else. `http.verify()` in `afterEach` is the
    // assertion.
    const harness = await openBoard();
    const zones = harness.fixture.debugElement.queryAll(By.directive(CdkDropList));

    drop(zones[0]!, zones[0]!);
    harness.detectChanges();

    http.expectNone('/api/v1/applications/4');
  });

  it('opens the state zones on the press, not on the drag', async () => {
    // The order is the whole point and it is measured, not preferred: the CDK caches every
    // container's rectangle at the first move past the threshold and never asks again, so
    // zones revealed on `cdkDragStarted` are measured collapsed and a drop over one produces
    // no request at all. `pointerdown` is unconditionally before the first move.
    //
    // Asserted without a `detectChanges()` in between on purpose: the class is written
    // straight onto the element rather than through a binding, because change detection runs
    // a frame later and a frame later is the race this exists to avoid.
    const harness = await openBoard();
    const board: HTMLElement = harness.routeNativeElement!.querySelector('.board')!;
    const card: HTMLElement = board.querySelector('.lane-card')!;
    const grip: HTMLElement = card.querySelector('.card-grip')!;

    expect(board.classList.contains('picking')).toBe(false);

    // On the grip and nowhere else: pressing a card to read it must not open the zones.
    card.dispatchEvent(new PointerEvent('pointerdown', {bubbles: true}));
    expect(board.classList.contains('picking')).toBe(false);

    grip.dispatchEvent(new PointerEvent('pointerdown', {bubbles: true}));
    expect(board.classList.contains('picking')).toBe(true);

    window.dispatchEvent(new PointerEvent('pointerup'));
    expect(board.classList.contains('picking')).toBe(false);
  });

  /** The drop the CDK would emit, reduced to what the handler reads. */
  function drop(from: DebugElement, to: DebugElement): void {
    const container = to.injector.get(CdkDropList);
    to.injector.get(CdkDropList).dropped.emit({
      previousContainer: from.injector.get(CdkDropList),
      container,
      item: {data: APPLICATION},
    } as unknown as CdkDragDrop<ApplicationStatus>);
  }

  afterEach(() => http.verify());
});
