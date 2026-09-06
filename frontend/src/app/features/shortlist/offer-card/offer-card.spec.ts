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
    };
}

describe('OfferCard', () => {
    async function render(selected: boolean): Promise<ComponentFixture<OfferCard>> {
        await TestBed.configureTestingModule({
            providers: [
                provideRouter([]),
                {provide: SCORE_THRESHOLDS, useValue: signal({shortlistAt: 70, reviewAt: 50})},
            ],
        }).compileComponents();
        const fixture = TestBed.createComponent(OfferCard);
        fixture.componentRef.setInput('entry', entry(2));
        fixture.componentRef.setInput('selected', selected);
        fixture.detectChanges();
        return fixture;
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
        expect(fixture.nativeElement.querySelector('.card.is-selected')).toBeNull();
    });

    it('says which card the detail column is showing', async () => {
        const fixture = await render(true);

        expect(fixture.nativeElement.querySelector('.card.is-selected')).not.toBeNull();
        expect(fixture.nativeElement.querySelector('.title a').getAttribute('aria-current')).toBe(
            'true',
        );
    });
});
