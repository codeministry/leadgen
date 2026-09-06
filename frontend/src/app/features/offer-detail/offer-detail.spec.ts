import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {ComponentFixture, TestBed} from '@angular/core/testing';
import {signal} from '@angular/core';
import {provideRouter} from '@angular/router';
import {ShortlistEntry} from '@core/model/shortlist-entry';
import {SCORE_THRESHOLDS} from '@shared/shared.ports';
import {OfferDetail} from './offer-detail';

/** Long enough to be folded; the exact length is what the component decides on. */
const LONG_AD = 'Wir suchen einen Senior Java-Entwickler mit Spring Boot. '.repeat(40);

function entry(id: number, fullText: string | null): ShortlistEntry {
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
});
