import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {TestBed} from '@angular/core/testing';
import {injectDispatch} from '@ngrx/signals/events';
import {CoverLetter} from '@core/model/cover-letter';
import {coverLetterEvents} from './cover-letter.events';
import {CoverLetterStore} from './cover-letter.store';

const URL = '/api/v1/offers/7/cover-letter';

function letter(overrides: Partial<CoverLetter> = {}): CoverLetter {
    return {text: 'Guten Tag,\n\n…', author: 'model', at: '2026-09-24T08:00:00Z', ...overrides};
}

describe('CoverLetterStore', () => {
    let store: InstanceType<typeof CoverLetterStore>;
    let http: HttpTestingController;
    let dispatch: ReturnType<typeof injectDispatch<typeof coverLetterEvents>>;

    beforeEach(() => {
        TestBed.configureTestingModule({
            providers: [provideHttpClient(), provideHttpClientTesting()],
        });
        store = TestBed.inject(CoverLetterStore);
        http = TestBed.inject(HttpTestingController);
        dispatch = TestBed.runInInjectionContext(() => injectDispatch(coverLetterEvents));
    });

    afterEach(() => http.verify());

    it('reads the letter of one offer', () => {
        dispatch.requested(7);
        expect(store.loading()).toBe(true);

        http.expectOne({method: 'GET', url: URL}).flush(letter());

        expect(store.letter()).toEqual(letter());
        expect(store.loading()).toBe(false);
    });

    it('takes a 404 as "no letter yet", not as a failure', () => {
        dispatch.requested(7);
        http.expectOne(URL).flush('no package', {status: 404, statusText: 'Not Found'});

        expect(store.letter()).toBeNull();
        expect(store.error()).toBeNull();
    });

    it('saves an edit with PUT and keeps the stored answer', () => {
        dispatch.requested(7);
        http.expectOne(URL).flush(letter());

        dispatch.saveRequested({offerId: 7, text: 'Neu.'});
        expect(store.saving()).toBe(true);

        const request = http.expectOne({method: 'PUT', url: URL});
        expect(request.request.body).toEqual({text: 'Neu.'});
        request.flush(letter({text: 'Neu.', author: 'edited'}));

        expect(store.letter()).toEqual(letter({text: 'Neu.', author: 'edited'}));
        expect(store.saving()).toBe(false);
    });

    it('asks for a fresh draft with POST', () => {
        dispatch.requested(7);
        http.expectOne(URL).flush(letter({author: 'edited'}));

        dispatch.draftRequested(7);
        expect(store.drafting()).toBe(true);
        http.expectOne({method: 'POST', url: `${URL}/draft`}).flush(letter({text: 'Frisch.'}));

        expect(store.letter()?.text).toBe('Frisch.');
        expect(store.drafting()).toBe(false);
    });

    it('names a sent application and a spent budget as what they are', () => {
        dispatch.requested(7);
        http.expectOne(URL).flush(letter());

        dispatch.saveRequested({offerId: 7, text: 'x'});
        http.expectOne({method: 'PUT', url: URL}).flush('sent', {status: 409, statusText: 'Conflict'});
        expect(store.error()).toBe('error.letterSent');
        expect(store.letter()).toEqual(letter());

        dispatch.draftRequested(7);
        http.expectOne({method: 'POST', url: `${URL}/draft`}).flush('spent', {status: 429, statusText: 'Too Many Requests'});
        expect(store.error()).toBe('error.letterBudget');
        expect(store.drafting()).toBe(false);
    });

    it('drops an answer that belongs to an offer no longer shown', () => {
        dispatch.requested(7);
        dispatch.requested(8);
        // `switchMap`: the first read is cancelled, and only the second one is answered.
        http.expectOne('/api/v1/offers/8/cover-letter').flush(letter({text: 'acht'}));
        http.match(URL).forEach((request) => expect(request.cancelled).toBe(true));

        expect(store.offerId()).toBe(8);
        expect(store.letter()?.text).toBe('acht');
    });
});
