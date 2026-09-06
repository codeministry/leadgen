import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import {Router, provideRouter} from '@angular/router';
import { signal } from '@angular/core';
import { ShortlistEntry } from '@core/model/shortlist-entry';
import { ShortlistPage as ShortlistPayload } from '@core/model/shortlist-page';
import { SCORE_THRESHOLDS } from '@shared/shared.ports';
import { ShortlistPage } from './shortlist-page';

function entry(id: number, title: string, value: number | null, portal: string): ShortlistEntry {
  return {
    offer: {
      id,
      externalId: `https://example.invalid/${id}`,
      title,
      description: 'Ablösung eines Monolithen.',
      url: `https://example.invalid/${id}`,
      location: 'Köln',
      portal,
      agency: null,
      publishedOn: '2026-09-01',
      tags: ['Java'],
      rateEur: null,
      remotePercent: null,
      startsOn: null,
      duration: null,
      workload: null,
      language: 'de',
      fullText: null,
      packageDir: null,
      archivedAt: null,
      archiveSource: null,
    },
    score: { value, hardPass: true, reasons: [], model: null, rulesetVersion: '1' },
    flags: { incomplete: false, remoteUnknown: true },
    sources: [{ portal, agency: null, url: `https://example.invalid/${id}` }],
  };
}

const ENTRIES: readonly ShortlistEntry[] = [
  entry(1, 'Senior Java Entwickler', 88, 'portal-a'),
  entry(2, 'Java Entwickler', 64, 'portal-b'),
  entry(3, 'Angular Entwickler', null, 'portal-a'),
];

function page(over: Partial<ShortlistPayload> = {}): ShortlistPayload {
  return {
    entries: ENTRIES,
    nextCursor: null,
    matched: ENTRIES.length,
    unscored: 1,
    total: ENTRIES.length,
    portals: ['portal-a', 'portal-b'],
    ...over,
  };
}

describe('ShortlistPage', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: SCORE_THRESHOLDS, useValue: signal({ shortlistAt: 70, reviewAt: 50 }) },
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  /** The one request the screen makes on open, whatever the filters put in the query. */
  function expectPage(): ReturnType<HttpTestingController['expectOne']> {
    return http.expectOne((request) => request.url === '/api/offers');
  }

  function render(payload: ShortlistPayload = page()): ComponentFixture<ShortlistPage> {
    const fixture = TestBed.createComponent(ShortlistPage);
    fixture.detectChanges();
    expectPage().flush(payload);
    fixture.detectChanges();
    return fixture;
  }

  function cards(fixture: ComponentFixture<ShortlistPage>): number {
    return fixture.nativeElement.querySelectorAll('lg-offer-card').length;
  }

  it('renders with no query parameters at all', () => {
    // Router input binding writes `undefined` for an absent parameter rather than leaving
    // the declared default, and the resulting `undefined.trim()` fails inside the
    // template — half a page, no useful console error.
    const fixture = TestBed.createComponent(ShortlistPage);
    fixture.componentRef.setInput('q', undefined);
    fixture.componentRef.setInput('band', undefined);
    fixture.componentRef.setInput('portal', undefined);
    fixture.detectChanges();
    expectPage().flush(page());
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Shortlist');
    expect(cards(fixture)).toBe(3);
  });

  it('asks the server for the filtered page rather than filtering what it has', () => {
    // The band boundaries are the configured thresholds and the list is paginated, so a
    // page filtered in the browser would be a page of nothing in particular.
    const fixture = render();

    fixture.componentRef.setInput('band', 'shortlist');
    fixture.componentRef.setInput('q', 'java');
    fixture.detectChanges();

    const request = expectPage();
    expect(request.request.params.get('band')).toBe('shortlist');
    expect(request.request.params.get('q')).toBe('java');
    request.flush(page({ entries: [ENTRIES[0]!], matched: 1 }));
    fixture.detectChanges();

    expect(cards(fixture)).toBe(1);
  });

  it('offers every portal on the shortlist, not only those on this page', () => {
    // Derived from the loaded entries, the dropdown offered fewer choices the further you
    // scrolled — so the server sends the whole set beside the page.
    const fixture = render(page({ entries: [ENTRIES[0]!], matched: 3 }));

    const options: HTMLOptionElement[] = Array.from(
      fixture.nativeElement.querySelectorAll('select option'),
    );
    expect(options.map((option) => option.value)).toEqual(['', 'portal-a', 'portal-b']);
  });

  it('appends the next page and stops when the cursor runs out', () => {
    const fixture = render(page({ entries: [ENTRIES[0]!], nextCursor: '88|1|1', matched: 3 }));

    fixture.componentInstance['loadMore']();
    const request = expectPage();
    expect(request.request.params.get('cursor')).toBe('88|1|1');
    request.flush(page({ entries: [ENTRIES[1]!, ENTRIES[2]!], nextCursor: null, matched: 3 }));
    fixture.detectChanges();

    expect(cards(fixture)).toBe(3);

    // Nothing left to ask for. A sentinel stays in the DOM until the cursor runs out, so
    // without this guard the last crossing would re-request the final page forever.
    fixture.componentInstance['loadMore']();
    http.expectNone((request) => request.url === '/api/offers');
  });

  it('reads the archive as its own side rather than as a fourth band', () => {
    // Not a band: a band is a range of scores, and this decides which set the bands apply
    // to. The server counts `total` and the portals over the same side, so the sentence
    // beside the list is about what is on screen.
    const fixture = render();

    fixture.componentRef.setInput('archived', '1');
    fixture.componentRef.setInput('band', 'shortlist');
    fixture.detectChanges();

    const request = expectPage();
    expect(request.request.params.get('archived')).toBe('true');
    expect(request.request.params.get('band')).toBe('shortlist');
    request.flush(page({ entries: [ENTRIES[0]!], matched: 1, total: 400 }));
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('archived');
  });

  it('says how many of the archive the filters matched', () => {
    const fixture = render(page({ matched: 12, total: 2219 }));

    expect(fixture.nativeElement.textContent).toContain('12');
    expect(fixture.nativeElement.textContent).toContain('2219');
  });

    /**
     * jsdom implements no `matchMedia` at all, which is why the component guards it — and
     * why the width has to be stated here for either case to be reachable.
     */
    function widthAllowsBothColumns(matches: boolean): void {
        Object.defineProperty(window, 'matchMedia', {
            configurable: true,
            writable: true,
            value: (query: string) => ({
                matches,
                media: query,
                addEventListener: () => undefined,
                removeEventListener: () => undefined,
            }),
        });
    }

    it('opens the first offer by itself when both columns fit', () => {
        // An empty right column beside a full list is a page waiting for a click it does not
        // need: the first entry is the highest-scoring one the filters produced.
        widthAllowsBothColumns(true);
        const router = TestBed.inject(Router);
        const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);

        render();

        expect(navigate).toHaveBeenCalledWith(
            ['/shortlist', ENTRIES[0]!.offer.id],
            // `replaceUrl`, or the back button would undo a selection nobody made.
            expect.objectContaining({replaceUrl: true, queryParamsHandling: 'preserve'}),
        );
    });

    it('leaves the list alone where the detail would replace it', () => {
        // Below the stylesheet's breakpoint the detail takes the whole screen, so opening one
        // by itself answers "show me the shortlist" with a single offer.
        widthAllowsBothColumns(false);
        const router = TestBed.inject(Router);
        const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);

        render();

        expect(navigate).not.toHaveBeenCalled();
    });
});
