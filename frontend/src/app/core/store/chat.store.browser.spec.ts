import {Component} from '@angular/core';
import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {TestBed} from '@angular/core/testing';
import {provideRouter, Router, withComponentInputBinding} from '@angular/router';
import {RouterTestingHarness} from '@angular/router/testing';
import {ConversationView} from '@core/model/chat';
import {ChatStore} from './chat.store';

@Component({template: '<p>offers</p>'})
class OffersScreen {}

@Component({template: '<p>pipeline</p>'})
class PipelineScreen {}

const BASE = '/api/v1/chat/conversations';

const STORED: ConversationView = {
    id: 4,
    title: 'Remote Spring offers',
    pinnedOfferId: null,
    updatedAt: '2026-09-27T08:00:00Z',
    turns: [
        {
            id: 11,
            question: 'What came in last month?',
            answer: 'Fourteen remote Spring offers [1](cite:offer/2291).',
            state: 'DONE',
            steps: [],
            sources: [],
            replacesTurnId: null,
            model: null,
            createdAt: '2026-09-27T08:00:00Z',
        },
    ],
};

/**
 * One page load: a fresh injector, as a reload gives the application, with the router on the
 * URL the address bar holds. Nothing survives from the previous boot but the URL itself, so
 * whatever the store shows afterwards it read out of `?chat`.
 */
async function boot(url: string) {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
        providers: [
            provideHttpClient(),
            provideHttpClientTesting(),
            provideRouter(
                [
                    {path: 'offers', component: OffersScreen},
                    {path: 'pipeline', component: PipelineScreen},
                ],
                withComponentInputBinding(),
            ),
        ],
    });
    const harness = await RouterTestingHarness.create();
    const store = TestBed.inject(ChatStore);
    await harness.navigateByUrl(url);
    return {harness, store, http: TestBed.inject(HttpTestingController), router: TestBed.inject(Router)};
}

describe('ChatStore against a real router (ISC-431)', () => {
    afterEach(() => TestBed.inject(HttpTestingController).verify());

    it('restores the same conversation and turn after a reload on either screen', async () => {
        const seen: [number | null, number | undefined][] = [];

        for (const url of ['/offers?chat=4', '/pipeline?chat=4']) {
            const {store, http} = await boot(url);
            http.expectOne({method: 'GET', url: `${BASE}/4`}).flush(STORED);

            expect(store.view()).toBe('conversation');
            seen.push([store.openId(), store.turns()[0]?.id]);
        }

        expect(seen).toEqual([
            [4, 11],
            [4, 11],
        ]);
    });

    it('keeps the conversation open across a change of screen that carries `?chat`', async () => {
        const {harness, store, http, router} = await boot('/offers?chat=4');
        http.expectOne(`${BASE}/4`).flush(STORED);

        await router.navigate(['/pipeline'], {queryParamsHandling: 'preserve'});
        harness.detectChanges();

        expect(router.url).toBe('/pipeline?chat=4');
        expect(harness.routeNativeElement?.textContent).toContain('pipeline');
        expect(store.openId()).toBe(4);
        expect(store.turns().map((t) => t.id)).toEqual([11]);
        // Held, not read again: `verify` in afterEach fails on a second GET.
    });

    it('resolves `?chat=new` to a fresh conversation and `?chat=list` to the list', async () => {
        const fresh = await boot('/pipeline?chat=new');
        expect(fresh.store.view()).toBe('new');
        expect(fresh.store.conversation()).toBeNull();

        const listed = await boot('/offers?chat=list');
        listed.http.expectOne({method: 'GET', url: BASE}).flush([{id: 4, title: 'Remote Spring offers', updatedAt: '2026-09-27T08:00:00Z'}]);
        expect(listed.store.view()).toBe('list');
        expect(listed.store.conversations().map((c) => c.id)).toEqual([4]);
    });

    it('lands an unknown id in the missing state, never in an error', async () => {
        const {store, http} = await boot('/offers?chat=404');
        http.expectOne(`${BASE}/404`).flush('no such conversation', {status: 404, statusText: 'Not Found'});

        expect(store.view()).toBe('missing');
        expect(store.error()).toBeNull();
        expect(store.conversation()).toBeNull();
    });
});
