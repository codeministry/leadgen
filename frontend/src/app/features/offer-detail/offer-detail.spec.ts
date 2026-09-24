import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {ComponentFixture, TestBed} from '@angular/core/testing';
import {signal} from '@angular/core';
import {provideRouter} from '@angular/router';
import {ContentBlock} from '@core/model/offer';
import {ShortlistEntry} from '@core/model/shortlist-entry';
import {SCORE_THRESHOLDS} from '@shared/shared.ports';
import {OfferDetail} from './offer-detail';

/** Long enough to be folded; the exact length is what the component decides on. */
const LONG_AD = 'Wir suchen einen Senior Java-Entwickler mit Spring Boot. '.repeat(40);

function block(index: number, text: string, kind: string, reason: string | null = null): ContentBlock {
  return {index, text, kind, reason, by: kind === 'CONTENT' ? 'MODEL' : 'RULE'};
}

function entry(
  id: number,
  fullText: string | null,
  content: readonly ContentBlock[] = [],
): ShortlistEntry {
    return {
        offer: {
            id,
            sourceName: 'sample-newsletter',
            externalId: `https://example.invalid/${id}`,
            title: 'Senior Java Entwickler',
            description: 'Ablösung eines Monolithen.',
            url: `https://example.invalid/${id}`,
            location: 'Köln',
            portal: 'portal-a',
            agency: null,
            publishedOn: '2026-09-01',
            tags: ['Java'],
            rateEur: null,
            remotePercent: null,
            startsOn: null,
          startText: null,
          durationMonths: null,
          applyBy: null,
          applyByText: null,
            duration: null,
            workload: null,
            language: 'de',
            fullText,
            packageDir: null,
          ingestedAt: '2026-09-02T05:12:00Z',
            archivedAt: null,
            archiveSource: null,
            enrichmentNote: null,
        },
        score: {value: 80, hardPass: true, reasons: [], model: 'test', rulesetVersion: '3'},
      flags: {incomplete: false, remoteUnknown: true, possibleDuplicate: false},
        sources: [{portal: 'portal-a', agency: null, url: `https://example.invalid/${id}`}],
      content,
    };
}

describe('OfferDetail', () => {
    let http: HttpTestingController;

    beforeEach(async () => {
        await TestBed.configureTestingModule({
            providers: [
                provideRouter([]),
                provideHttpClient(),
                provideHttpClientTesting(),
                {provide: SCORE_THRESHOLDS, useValue: signal({shortlistAt: 70, reviewAt: 45})},
            ],
        }).compileComponents();
        http = TestBed.inject(HttpTestingController);
    });

    function render(payload: ShortlistEntry): ComponentFixture<OfferDetail> {
        const fixture = TestBed.createComponent(OfferDetail);
        fixture.componentRef.setInput('id', String(payload.offer.id));
        fixture.detectChanges();
        http.expectOne(`/api/v1/offers/${payload.offer.id}`).flush(payload);
        // Both are answered with an empty list rather than left open: the application panel
        // shares this page, and an unanswered request leaves its computed reading `undefined`.
        http.match((request) => request.url.startsWith('/api/v1/applications')).forEach((request) => request.flush([]));
        fixture.detectChanges();
        return fixture;
    }

    function ad(fixture: ComponentFixture<OfferDetail>): HTMLElement {
        return fixture.nativeElement.querySelector('.ad');
    }

    function toggle(fixture: ComponentFixture<OfferDetail>): HTMLButtonElement | null {
        return fixture.nativeElement.querySelector('.ad-toggle');
    }

  function close(fixture: ComponentFixture<OfferDetail>): HTMLAnchorElement | null {
    return fixture.nativeElement.querySelector('a.close');
  }

  function reveals(fixture: ComponentFixture<OfferDetail>): HTMLButtonElement[] {
    return Array.from(fixture.nativeElement.querySelectorAll('.ad-reveal'));
  }

  it('reads the panel in four blocks, and says when the offer was read in', () => {
    // The whole order, not a pair of neighbours: nothing in the markup separates the
    // blocks — the grid is one list — so this sequence is the only thing carrying the
    // grouping, and a row appended at the end would land in the wrong one with every
    // other assertion still green.
    const fixture = render(entry(9, null));
    const rows: HTMLElement[] = Array.from(fixture.nativeElement.querySelectorAll('.fields .field'));
    const labels = rows.map((row) => row.querySelector('dt')!.textContent!.trim());

    expect(labels).toEqual([
      // where it came from
      'Source',
      'Portal',
      'Agency',
      'External id',
      // what the work is
      'Location',
      'Remote share',
      'Workload',
      'Rate',
      // when it runs — four, so the next block starts on a fresh grid row
      'Start',
      'Duration',
      'Application deadline',
      'Published',
      // what this tool wrote about the row
      'Ingested',
      'Language',
    ]);

    // The two days are only useful read against each other, and both are written the way
    // the active language writes a day rather than as the server's `YYYY-MM-DD`.
    const ingested = rows[labels.indexOf('Ingested')];
    expect(ingested.querySelector('dd')!.textContent!.trim()).toBe('Sep 2, 2026');
  });

  it('offers a way out only where the route says there is one', () => {
    // `closeTo` comes from the route's data, so the screen that owns the reading column
    // decides. The board's exists only while something is being read; the shortlist opens
    // its first entry by itself, so a close there would be undone on the next tick.
    const fixture = render(entry(9, null));
    expect(close(fixture)).toBeNull();

    fixture.componentRef.setInput('closeTo', '/pipeline');
    fixture.detectChanges();

    const link = close(fixture)!;
    expect(link).not.toBeNull();
    expect(link.getAttribute('href')).toBe('/pipeline');
    expect(link.getAttribute('aria-label')).toBeTruthy();

    // Beside the header and not inside its action row: that row wraps, and a close
    // control that wraps with it ends up below the title it closes. The header pays for
    // the space instead, so a long title never runs under the button.
    const header: HTMLElement = fixture.nativeElement.querySelector('lg-page-header');
    expect(header.contains(link)).toBe(false);
    expect(header.classList).toContain('has-close');
  });

    it('folds a long advert and offers to unfold it', () => {
        const fixture = render(entry(1, LONG_AD));

        expect(ad(fixture).classList).toContain('folded');
        expect(toggle(fixture)).not.toBeNull();

        toggle(fixture)!.click();
        fixture.detectChanges();

        expect(ad(fixture).classList).not.toContain('folded');
        // The whole ad was in the DOM the entire time — folding is a clamp, not a truncation,
        // so the browser's own find still reaches the end of it.
        expect(ad(fixture).textContent).toContain('Spring Boot');
    });

    it('leaves a short advert alone and shows no toggle at all', () => {
        // A control that does nothing is worse than no control: it says there is more to read.
        const fixture = render(entry(2, null));

        expect(ad(fixture).classList).not.toContain('folded');
        expect(toggle(fixture)).toBeNull();
    });

  it('hides what was decided not to be the advert, and puts it back where it stood', () => {
    const fixture = render(
      entry(3, 'ignored when blocks are present', [
        block(0, 'Apply now Save to watchlist', 'CHROME', 'Portal buttons.'),
        block(1, '* Print\n* Report', 'CHROME'),
        block(2, 'Wir suchen einen Angular Entwickler.', 'CONTENT'),
        block(3, 'Amtsgericht München, HRB 187777', 'AGENCY', "The recruiter's register entry."),
      ]),
    );

    // Two consecutive hidden blocks are one line to click, not two: three paragraphs of
    // furniture in a row must not become three separate decisions.
    expect(reveals(fixture)).toHaveLength(2);
    expect(ad(fixture).textContent).toContain('Wir suchen einen Angular Entwickler.');
    expect(ad(fixture).textContent).not.toContain('Save to watchlist');

    reveals(fixture)[0].click();
    fixture.detectChanges();

    // Back in place, with the reason beside it, so over-hiding is visible rather than
    // something to take on trust.
    expect(ad(fixture).textContent).toContain('Save to watchlist');
    expect(ad(fixture).textContent).toContain('Portal buttons.');
    // And only that one: revealing a stretch says nothing about any other.
    expect(ad(fixture).textContent).not.toContain('Amtsgericht');
  });

  it('shows a block whose kind it has never heard of', () => {
    // The server owns the taxonomy. A kind this build does not know is still hidden and
    // still revealable — it is labelled by its own name rather than a translated one.
    const fixture = render(
      entry(4, null, [
        block(0, 'Something new the server started labelling', 'SOMETHING_NEW'),
        block(1, 'Wir suchen einen Angular Entwickler.', 'CONTENT'),
      ]),
    );

    expect(reveals(fixture)).toHaveLength(1);
    expect(reveals(fixture)[0].textContent).toContain('SOMETHING_NEW');
  });

  it('measures the fold on what is on the screen, not on the whole advert', () => {
    // Measured on the raw text, an ad that is mostly portal furniture offers "show the
    // whole ad" for content that is no longer there.
    const fixture = render(
      entry(5, null, [
        block(0, LONG_AD, 'CHROME'),
        block(1, 'Kurz.', 'CONTENT'),
      ]),
    );

    expect(ad(fixture).classList).not.toContain('folded');
    expect(toggle(fixture)).toBeNull();
  });

  it('does not carry a revealed stretch to the next offer', () => {
    const blocks = [
      block(0, 'Apply now Save to watchlist', 'CHROME'),
      block(1, 'Wir suchen einen Angular Entwickler.', 'CONTENT'),
    ];
    const fixture = render(entry(6, null, blocks));

    reveals(fixture)[0].click();
    fixture.detectChanges();
    expect(ad(fixture).textContent).toContain('Save to watchlist');

    // The same component, a different offer: a decision about one advert must not open
    // the next one's furniture for a decision nobody made about it.
    fixture.componentRef.setInput('id', '7');
    fixture.detectChanges();
    http.expectOne('/api/v1/offers/7').flush(entry(7, null, blocks));
    http.match((request) => request.url.startsWith('/api/v1/applications')).forEach((request) => request.flush([]));
    fixture.detectChanges();

    expect(ad(fixture).textContent).not.toContain('Save to watchlist');
  });

  function fetchAgain(fixture: ComponentFixture<OfferDetail>): HTMLButtonElement | null {
    return fixture.nativeElement.querySelector('.fetch-again');
  }

  it('offers to fetch the ad again only where the run would fetch it', () => {
    // ISC-243. The four conditions the server checks, flipped one at a time: a button that
    // appeared on any of the four would offer a request that can only come back refused.
    const missing = entry(21, null);
    expect(fetchAgain(render(missing))).not.toBeNull();

    const variants: ShortlistEntry[] = [
      {...entry(22, 'Das ganze Inserat.')},
      {...entry(23, null), offer: {...entry(23, null).offer, url: null}},
      {...entry(24, null), offer: {...entry(24, null).offer, archivedAt: '2026-09-20T05:00:00Z'}},
      {...entry(25, null), score: {...entry(25, null).score, hardPass: false}},
    ];
    for (const variant of variants) {
      expect(fetchAgain(render(variant)), `offer ${variant.offer.id}`).toBeNull();
    }
  });

  it('keeps the offer on screen and says why when a fetch is turned away', () => {
    // ISC-247, the page's half. The note the run recorded stands beside the button, and a
    // request the server refused adds its own sentence there — the detail is not replaced by
    // an error, because the offer on it is still the right one and still worth reading.
    const refused = {...entry(26, null), offer: {...entry(26, null).offer, enrichmentNote: 'status 403'}};
    const fixture = render(refused);
    expect(fixture.nativeElement.textContent).toContain('Last attempt: status 403');

    fetchAgain(fixture)!.click();
    http.expectOne({method: 'POST', url: '/api/v1/offers/26/fetch'})
        .flush('the fetch rate limit of 20 ads a minute is spent; try again in a minute', {
          status: 429,
          statusText: 'Too Many Requests',
        });
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.fetch-refusal').textContent).toContain('rate limit');
    expect(fixture.nativeElement.textContent).toContain('Senior Java Entwickler');
    expect(ad(fixture)).not.toBeNull();
    expect(fetchAgain(fixture)!.disabled).toBe(false);
  });

  it('replaces the caption with the advert once the fetch brings it back', () => {
    // ISC-246, the page's half: the entry the server stored replaces the one on screen.
    const fixture = render(entry(27, null));
    expect(fixture.nativeElement.textContent).toContain('The original ad was not fetched');

    fetchAgain(fixture)!.click();
    fixture.detectChanges();
    expect(fetchAgain(fixture)!.disabled).toBe(true);
    http.expectOne({method: 'POST', url: '/api/v1/offers/27/fetch'}).flush(entry(27, 'Das ganze Inserat, abgerufen.'));
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).not.toContain('The original ad was not fetched');
    expect(ad(fixture).textContent).toContain('Das ganze Inserat, abgerufen.');
    expect(fetchAgain(fixture)).toBeNull();
  });
});
