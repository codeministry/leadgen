import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {TestBed} from '@angular/core/testing';
import {provideRouter, Router, withComponentInputBinding} from '@angular/router';
import {RouterTestingHarness} from '@angular/router/testing';
import {SourceDetail} from '@core/model/source-detail';
import {SourcesView} from '@core/model/source-summary';

const SOURCES: SourcesView = {
  file: 'sources.yaml',
  layer: 'config-dir',
  sources: [
    {
      id: 'demo-newsletter',
      kind: 'file',
      enabled: true,
      lastRunAt: '2026-09-15T06:00:00Z',
      documents: 5,
      extracted: 169,
      announced: 169,
      survived: 67,
    },
    {
      id: 'mailbox-primary',
      kind: 'imap',
      enabled: false,
      lastRunAt: null,
      documents: 0,
      extracted: 0,
      announced: null,
      survived: 0,
    },
  ],
};

const DETAIL: SourceDetail = {
  id: 'demo-newsletter',
  kind: 'file',
  enabled: true,
  file: {name: 'sources.yaml', layer: 'config-dir', origin: '/config/sources.yaml'},
  block: {text: '- id: demo-newsletter\n  type: file\n', firstLine: 21, lastLine: 22},
  connection: null,
  runs: [
    {ranOn: '2026-09-15', documents: 5, extracted: 169, written: 169, announced: 169, missing: 0},
    {ranOn: '2026-09-14', documents: 5, extracted: 157, written: 157, announced: 169, missing: 12},
  ],
  trend: {
    extractedChangedOn: '2026-09-15',
    extractedBefore: 157,
    extractedNow: 169,
    divergedOn: '2026-09-14',
    missing: 12,
    announcedStated: true,
  },
};

describe('Sources', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      providers: [
        // `withComponentInputBinding`, as `app.config.ts` provides it: without it the panel's
        // `:id` input is never bound, it keeps its declared default, and the panel quietly
        // fetches nothing at all.
        provideRouter(
          [
            {
              path: 'sources',
              loadComponent: () => import('./sources').then((m) => m.Sources),
              children: [
                {
                  path: ':id',
                  loadComponent: () => import('./source-panel/source-panel').then((m) => m.SourcePanel),
                },
              ],
            },
          ],
          withComponentInputBinding(),
        ),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  /** The table, and the first source, which opens by itself once the table has rows. */
  async function openList(view: SourcesView = SOURCES): Promise<RouterTestingHarness> {
    const harness = await RouterTestingHarness.create('/sources');
    http.expectOne('/api/v1/sources').flush(view);
    harness.detectChanges();
    await harness.fixture.whenStable();
    if (view.sources.length > 0) {
      flushDetail();
      harness.detectChanges();
    }
    return harness;
  }

  /** The panel's own request, matched on the path so the `runs` parameter cannot fail it. */
  function flushDetail(): void {
    http.expectOne((request) => request.url === '/api/v1/sources/demo-newsletter').flush(DETAIL);
  }

  it('states the file once rather than in every row', async () => {
    // The layer is one probe for the whole file — the two layers override each other file by
    // file and never key by key — so a badge per row asserted something that cannot differ
    // between two rows. It was a column until this change.
    const harness = await openList();
    const text = harness.routeNativeElement!.textContent ?? '';

    expect(text).toContain('sources.yaml');
    expect(harness.routeNativeElement!.querySelectorAll('.file-line')).toHaveLength(1);
  });

  it('shows the error instead of pretending nothing is configured', async () => {
    // A failed request left the table hidden and the empty state showing, so a network fault
    // read as "no sources configured" — on the screen whose whole job is to make a
    // misconfigured source visible.
    const harness = await RouterTestingHarness.create('/sources');
    http.expectOne('/api/v1/sources').flush('nope', {status: 500, statusText: 'Server Error'});
    harness.detectChanges();

    expect(harness.routeNativeElement!.querySelector('[role="alert"]')).not.toBeNull();
    expect(harness.routeNativeElement!.querySelector('lg-empty-state')).toBeNull();
  });

  it('opens the first source by itself, without a history entry', async () => {
    // An empty space under a full table is a click nobody needs to make; `replaceUrl`, or the
    // back button would undo a selection nobody made.
    const harness = await openList();
    const router = TestBed.inject(Router);

    expect(router.url).toBe('/sources/demo-newsletter');
    expect(harness.routeNativeElement!.querySelector('#lg-source-panel-title')?.textContent).toContain('demo-newsletter');
  });

  it('keeps a deep link rather than opening the first source over it', async () => {
    const harness = await RouterTestingHarness.create('/sources/mailbox-primary');
    http.expectOne('/api/v1/sources').flush(SOURCES);
    http.expectOne((request) => request.url === '/api/v1/sources/mailbox-primary').flush({
      ...DETAIL,
      id: 'mailbox-primary'
    });
    harness.detectChanges();
    await harness.fixture.whenStable();

    expect(TestBed.inject(Router).url).toBe('/sources/mailbox-primary');
  });

  it('keeps the table mounted when a source is opened', async () => {
    // A child route on a real component: two flat routes would be two configurations, so the
    // default reuse strategy destroys the table on the first click and refetches it.
    const harness = await openList();

    http.expectNone('/api/v1/sources');
    expect(harness.routeNativeElement!.textContent).toContain('demo-newsletter');
  });

  it('marks the open row for a screen reader as well as for the eye', async () => {
    const harness = await openList();

    const link: HTMLAnchorElement = harness.routeNativeElement!.querySelector('.source-link')!;
    expect(link.getAttribute('aria-expanded')).toBe('true');
    expect(link.getAttribute('aria-current')).toBe('true');
    expect(link.getAttribute('aria-controls')).toBe('lg-source-panel');
  });

  it('asks again for the source that is open when a run finishes', async () => {
    // A run writes a new row into that source's history, and the configuration is
    // hot-reloadable, so the block beside it may have been rewritten since it was fetched.
    await openList();

    TestBed.inject(Router); // the harness owns the navigation; this keeps the injector warm
    const {injectDispatch} = await import('@ngrx/signals/events');
    const {ingestEvents} = await import('@core/store/ingest.events');
    TestBed.runInInjectionContext(() =>
      injectDispatch(ingestEvents).finished({
        results: [],
        merged: 0,
        finishedAt: '2026-09-15T07:00:00Z',
      } as never),
    );
    TestBed.tick();

    http.expectOne('/api/v1/sources').flush(SOURCES);
    flushDetail();
  });

  it('declares the table as a section, and an open panel adds its two headings', async () => {
    const harness = await openList();
    const root = harness.fixture.nativeElement as HTMLElement;
    const hrefs = () => Array.from(root.querySelectorAll('lg-anchor-rail nav a'), (a) => (a.getAttribute('href') ?? '').replace(/^[^#]*/, ''));
    expect(root.querySelector('h2#sources.lg-anchor-target')).not.toBeNull();

    expect(hrefs()).toEqual(['#sources', '#runs', '#block']);
    for (const id of ['runs', 'block']) {
      expect(root.querySelector(`h3#${id}.lg-anchor-target`)?.getAttribute('tabindex'), id).toBe('-1');
    }
  });

  afterEach(() => http.verify());
});
