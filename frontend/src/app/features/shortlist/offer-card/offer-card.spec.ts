import {ComponentFixture, TestBed} from '@angular/core/testing';
import {provideRouter} from '@angular/router';
import {signal} from '@angular/core';
import {ShortlistEntry} from '@core/model/shortlist-entry';
import {SCORE_THRESHOLDS} from '@shared/shared.ports';
import {OfferCard} from './offer-card';

function entry(id: number): ShortlistEntry {
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
            fullText: null,
            packageDir: null,
            archivedAt: null,
            archiveSource: null,
        },
        score: {value: 88, hardPass: true, reasons: [], model: null, rulesetVersion: '1'},
        flags: {incomplete: false, remoteUnknown: true},
        sources: [{portal: 'portal-a', agency: null, url: `https://example.invalid/${id}`}],
      content: [],
    };
}

describe('OfferCard', () => {
  async function render(
    selected: boolean,
    inputs: Record<string, unknown> = {},
  ): Promise<ComponentFixture<OfferCard>> {
        await TestBed.configureTestingModule({
            providers: [
                provideRouter([]),
                {provide: SCORE_THRESHOLDS, useValue: signal({shortlistAt: 70, reviewAt: 50})},
            ],
        }).compileComponents();
        const fixture = TestBed.createComponent(OfferCard);
        fixture.componentRef.setInput('entry', entry(2));
        fixture.componentRef.setInput('selected', selected);
    for (const [name, value] of Object.entries(inputs)) {
      fixture.componentRef.setInput(name, value);
    }
        fixture.detectChanges();
        return fixture;
    }

  function checkbox(fixture: ComponentFixture<OfferCard>): HTMLInputElement {
    return fixture.nativeElement.querySelector('input[type="checkbox"]') as HTMLInputElement;
  }

    it('links into the split view and carries the filters with it', async () => {
        // Without `queryParamsHandling`, opening an offer drops the query string and the list
        // reloads unfiltered — which looks like a store bug and is a missing attribute.
        const fixture = await render(false);

        const link = fixture.nativeElement.querySelector('.title a') as HTMLAnchorElement;
        expect(link.getAttribute('href')).toBe('/shortlist/2');
        // The attribute is the whole mechanism; that the resulting href really carries `?q=…`
        // needs a router with a query string behind it and is measured in the browser instead.
        expect(link.getAttribute('queryParamsHandling')).toBe('preserve');
      expect(fixture.nativeElement.querySelector('.offer.is-selected')).toBeNull();
    });

    it('says which card the detail column is showing', async () => {
        const fixture = await render(true);

      expect(fixture.nativeElement.querySelector('.offer.is-selected')).not.toBeNull();
        expect(fixture.nativeElement.querySelector('.title a').getAttribute('aria-current')).toBe(
            'true',
        );
    });

  it('offers no selection where there is no bulk restore', async () => {
    // The archive side passes `pickable: false`. The card is told; it never learns what an
    // archive is.
    const fixture = await render(false);

    expect(checkbox(fixture)).toBeNull();
  });

  it('keeps the checkbox out of the stretched link', async () => {
    // The behavioural half — that a click on the box does not navigate — needs a real
    // browser, because it is decided by `z-index` against `.title a::after`. What jsdom can
    // answer is the structure that rule depends on: the input is not inside the anchor.
    const fixture = await render(false, {pickable: true});

    const box = checkbox(fixture);
    expect(box).not.toBeNull();
    expect(fixture.nativeElement.querySelector('.title a').contains(box)).toBe(false);
    expect(box.closest('.pick')).not.toBeNull();
  });

  it('is a column of the card, beside the score and the title', async () => {
    // The structural half of the placement decision, which the column width was widened
    // for: score, title and checkbox read as one row only while the box is a sibling of
    // `.main` rather than something inside it. Moved into the body it drops below the
    // score ring, and `--lg-list-w` is then two rem wider for nothing.
    const fixture = await render(false, {pickable: true});

    const card = fixture.nativeElement.querySelector('.offer') as HTMLElement;
    const pick = card.querySelector('.pick')!;
    expect(pick.parentElement).toBe(card);
    expect(card.querySelector('.main')!.contains(pick)).toBe(false);
  });

  it('does not let the browser write its own checked state', async () => {
    // Without the `preventDefault`, a Shift-click on an already-ticked card leaves the box
    // showing the opposite of the truth: the browser unticks it, the range keeps it ticked,
    // and `[checked]` sees no change to reconcile.
    const fixture = await render(false, {pickable: true, picked: true});

    const event = new MouseEvent('click', {bubbles: true, cancelable: true});
    checkbox(fixture).dispatchEvent(event);

    expect(event.defaultPrevented).toBe(true);
  });

  it('says a Shift-click was a Shift-click', async () => {
    const fixture = await render(false, {pickable: true});
    const emitted: { id: number; range: boolean }[] = [];
    fixture.componentInstance.pickToggled.subscribe((event) => emitted.push(event));

    checkbox(fixture).dispatchEvent(new MouseEvent('click', {bubbles: true, cancelable: true}));
    checkbox(fixture).dispatchEvent(
      new MouseEvent('click', {bubbles: true, cancelable: true, shiftKey: true}),
    );

    expect(emitted).toEqual([
      {id: 2, range: false},
      {id: 2, range: true},
    ]);
  });

  it('names the offer its checkbox selects', async () => {
    // Fifty identical checkboxes are fifty unlabelled controls.
    const fixture = await render(false, {pickable: true});

    expect(checkbox(fixture).getAttribute('aria-label')).toContain('Senior Java Entwickler');
  });
});
