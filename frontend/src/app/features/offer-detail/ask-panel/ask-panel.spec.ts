import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {ComponentFixture, TestBed} from '@angular/core/testing';
import {AdvertAnswer} from '@core/model/advert-answer';
import {AskPanel} from './ask-panel';

/**
 * The three states, and that they are three.
 *
 * A silent advert, a refused question and an answered one must not read alike: drawn the same
 * way, a reader takes a spent budget for a quiet advert and fills the gap themselves.
 */
describe('AskPanel', () => {
    let http: HttpTestingController;

    beforeEach(async () => {
        await TestBed.configureTestingModule({
            providers: [provideHttpClient(), provideHttpClientTesting()],
        }).compileComponents();
        http = TestBed.inject(HttpTestingController);
    });

    function render(): ComponentFixture<AskPanel> {
        const fixture = TestBed.createComponent(AskPanel);
        fixture.componentRef.setInput('offerId', 42);
        fixture.detectChanges();
        return fixture;
    }

    function click(fixture: ComponentFixture<AskPanel>, index = 0): void {
        const buttons: HTMLButtonElement[] = Array.from(
            fixture.nativeElement.querySelectorAll('.question-ask'),
        );
        buttons[index]!.click();
        fixture.detectChanges();
    }

    const answered: AdvertAnswer = {
        question: 'rate',
        stated: true,
        answer: '95 EUR pro Stunde.',
        quote: 'Die Vergütung liegt bei 95 EUR pro Stunde.',
        model: 'test-model',
    };

    it('offers one button per question and asks for exactly the one clicked', () => {
        const fixture = render();
        expect(fixture.nativeElement.querySelectorAll('.question-ask')).toHaveLength(5);

        click(fixture);

        const request = http.expectOne((r) => r.url === '/api/offers/42/ask');
        expect(request.request.params.get('question')).toBe('rate');
        expect(request.request.method).toBe('POST');
        request.flush(answered);
    });

    it('shows the sentence the answer rests on, not only the answer', () => {
        // The server already dropped every claim whose quote it could not find in the advert.
        // Rendering the quote is what lets a reader confirm it by looking up.
        const fixture = render();
        click(fixture);
        http.expectOne((r) => r.url === '/api/offers/42/ask').flush(answered);
        fixture.detectChanges();

        expect(fixture.nativeElement.textContent).toContain('95 EUR pro Stunde.');
        expect(fixture.nativeElement.querySelector('.quote')?.textContent).toContain('Die Vergütung liegt');
    });

    it('draws a silent advert as an answer and not as a failure', () => {
        const fixture = render();
        click(fixture);
        http.expectOne((r) => r.url === '/api/offers/42/ask')
            .flush({question: 'rate', stated: false, answer: null, quote: null, model: 'test-model'});
        fixture.detectChanges();

        expect(fixture.nativeElement.querySelector('.quote')).toBeNull();
        expect(fixture.nativeElement.querySelector('.refusal')).toBeNull();
    });

    it('passes the server sentence through when nobody could ask at all', () => {
        // A 409 is not "the advert is silent". It names which of the three reasons applied, and
        // the screen has no business restating it in its own words.
        const fixture = render();
        click(fixture);
        http.expectOne((r) => r.url === '/api/offers/42/ask').flush(
            "today's llm.budget is spent",
            {status: 409, statusText: 'Conflict'},
        );
        fixture.detectChanges();

        expect(fixture.nativeElement.querySelector('.refusal')?.textContent).toContain('budget');
    });

    it('asks a question once and never twice', () => {
        // One question is one model call against the budget the judge shares.
        const fixture = render();
        click(fixture);
        http.expectOne((r) => r.url === '/api/offers/42/ask').flush(answered);
        fixture.detectChanges();

        click(fixture);
        http.expectNone((r) => r.url === '/api/offers/42/ask');
    });

    it('asks one at a time, so a held key cannot spend five calls', () => {
        const fixture = render();
        click(fixture, 0);
        click(fixture, 1);

        http.expectOne((r) => r.url === '/api/offers/42/ask');
    });
});
