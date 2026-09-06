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
            duration: null,
            workload: null,
            language: 'de',
            fullText,
            packageDir: null,
            archivedAt: null,
            archiveSource: null,
        },
        score: {value: 80, hardPass: true, reasons: [], model: 'test', rulesetVersion: '3'},
        flags: {incomplete: false, remoteUnknown: true},
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
        http.expectOne(`/api/offers/${payload.offer.id}`).flush(payload);
        // Both are answered with an empty list rather than left open: the application panel
        // shares this page, and an unanswered request leaves its computed reading `undefined`.
        http.match((request) => request.url.startsWith('/api/applications')).forEach((request) => request.flush([]));
        fixture.detectChanges();
        return fixture;
    }

    function ad(fixture: ComponentFixture<OfferDetail>): HTMLElement {
        return fixture.nativeElement.querySelector('.ad');
    }

    function toggle(fixture: ComponentFixture<OfferDetail>): HTMLButtonElement | null {
        return fixture.nativeElement.querySelector('.ad-toggle');
    }

  function reveals(fixture: ComponentFixture<OfferDetail>): HTMLButtonElement[] {
    return Array.from(fixture.nativeElement.querySelectorAll('.ad-reveal'));
  }

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
    http.expectOne('/api/offers/7').flush(entry(7, null, blocks));
    http.match((request) => request.url.startsWith('/api/applications')).forEach((request) => request.flush([]));
    fixture.detectChanges();

    expect(ad(fixture).textContent).not.toContain('Save to watchlist');
  });
});
