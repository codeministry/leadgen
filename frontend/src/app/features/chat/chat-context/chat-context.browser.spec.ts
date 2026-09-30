import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting, TestRequest} from '@angular/common/http/testing';
import {ComponentFixture, TestBed} from '@angular/core/testing';
import {provideRouter, Router, withComponentInputBinding} from '@angular/router';
import {TranslocoTestingModule} from '@jsverse/transloco';
import {provideTranslocoMessageformat} from '@jsverse/transloco-messageformat';
import en from '../../../../../public/i18n/en.json';
import {AnalyticsView} from '@core/model/analytics';
import {ChatContextItem, ConversationView} from '@core/model/chat';
import {ShortlistEntry} from '@core/model/shortlist-entry';
import {provideScoreThresholds} from '@core/store/score-thresholds.provider';
import {provideChartPalette} from '@core/theme/chart-theme';
import {App} from '../../../app';
import {routes} from '../../../app.routes';

function entry(id: number): ShortlistEntry {
    return {
        offer: {
            id,
            sourceName: 'sample-newsletter',
            externalId: `https://example.invalid/${id}`,
            title: `Kafka backend ${id}`,
            description: 'Event-driven architecture.',
            url: `https://example.invalid/${id}`,
            location: 'Köln',
            portal: 'portal-a',
            agency: null,
            publishedOn: '2026-09-01',
            tags: ['Java', 'Kafka'],
            rateEur: 95,
            remotePercent: 80,
            startsOn: null,
            startText: null,
            durationMonths: null,
            applyBy: null,
            applyByText: null,
            duration: null,
            workload: null,
            language: 'de',
            fullText: 'Wir suchen einen Entwickler.',
            packageDir: null,
            ingestedAt: '2026-09-02T05:12:00Z',
            archivedAt: null,
            archiveSource: null,
            enrichmentNote: null,
        },
        score: {value: 88, hardPass: true, reasons: [], model: null, rulesetVersion: '1'},
        flags: {incomplete: false, remoteUnknown: false, possibleDuplicate: false},
        sources: [{portal: 'portal-a', agency: null, url: `https://example.invalid/${id}`}],
        content: [],
    };
}

const ANALYTICS: AnalyticsView = {
    zone: 'Europe/Berlin',
    generatedAt: '2026-09-02T00:00:00Z',
    from: '2026-08-31',
    to: '2026-09-07',
    funnel: {total: 2, survived: 1, stages: []},
    intake: {byIngestedAt: [], byPublishedOn: [], byReceivedAt: [], withoutPublishedOn: 0, publishedOutOfRange: 0, withoutReceivedAt: 0},
    market: {portals: [], tags: [], locations: [], reach: {outOfReach: 0, abroad: 0, remoteShare: 0}, stageMix: []},
    scores: {bucketSize: 10, buckets: [], unscored: 0, shortlistAt: 70, reviewAt: 50},
    applications: {
        byStatus: [],
        transitions: [],
        response: {sent: 0, answered: 0, backdated: 0, medianDaysToFirstReply: null, p90DaysToFirstReply: null, won: 0, lost: 0, rejected: 0},
    },
    runs: {days: [], passes: [], historySince: null},
    scales: [],
} as unknown as AnalyticsView;

/** Conversation 7 as the server stores it: its context replaced by every `PUT …/context`. */
let stored: readonly ChatContextItem[] = [];
/** Every `PUT …/context` body, in order. */
let puts: (readonly ChatContextItem[])[] = [];

function conversation(): ConversationView {
    return {id: 7, title: 'Kafka offers', pinnedOfferId: null, context: stored, turns: [], updatedAt: '2026-09-02T08:00:00Z'};
}

/** The answer to every request a screen or the shell makes; `undefined` for one nobody expected. */
function answer(request: TestRequest): unknown {
    const url = request.request.url;
    if (url === '/api/v1/offers') {
        // The composer's `@` search asks the same endpoint with `q=` (ISC-453); the shortlist without.
        const q = request.request.params.get('q');
        const entries = q === null ? [entry(1), entry(2), entry(3)] : [entry(11), entry(12)];
        return {entries, nextCursor: null, matched: entries.length, unscored: 0, total: entries.length, portals: [], related: null, relatedTo: null};
    }
    const offer = /^\/api\/v1\/offers\/(\d+)$/.exec(url);
    if (offer) return entry(Number(offer[1]));
    if (url === '/api/v1/analytics') return ANALYTICS;
    if (url === '/api/v1/applications') return [];
    if (url === '/api/v1/chat/capability') return {present: true};
    if (url === '/api/v1/chat/conversations') return [];
    if (url === '/api/v1/chat/suggestions') return [];
    if (url === '/api/v1/chat/conversations/7/context' && request.request.method === 'PUT') {
        stored = (request.request.body as {context: readonly ChatContextItem[]}).context;
        puts.push(stored);
        return conversation();
    }
    if (url === '/api/v1/chat/conversations/7') return conversation();
    return undefined;
}

/**
 * "Use this view" and "Ask about this offer" (ISC-451): the real root, shell, panel and screens,
 * with the server stood in. Each pins its screen's context — the shortlist's query, the
 * analytics window, the offer — as a chip that is part of the URL, survives a reload, is stored
 * on an open conversation, and goes from both when removed.
 */
describe('pinning the screen into the chat (ISC-451)', () => {
    let fixture: ComponentFixture<App>;
    let http: HttpTestingController;
    let router: Router;

    beforeEach(() => {
        stored = [];
        puts = [];
    });

    afterEach(() => (fixture.nativeElement as HTMLElement).remove());

    /** A fresh app on `url`: what a reload is. The setup file's Transloco goes with the reset, so it comes back here. */
    async function boot(url: string): Promise<void> {
        if (fixture) (fixture.nativeElement as HTMLElement).remove();
        TestBed.resetTestingModule();
        TestBed.configureTestingModule({
            imports: [
                TranslocoTestingModule.forRoot({
                    langs: {en},
                    translocoConfig: {availableLangs: ['en', 'de'], defaultLang: 'en'},
                    preloadLangs: true,
                }),
            ],
            providers: [
                provideHttpClient(),
                provideHttpClientTesting(),
                provideRouter(routes, withComponentInputBinding()),
                provideScoreThresholds(),
                provideChartPalette(),
                provideTranslocoMessageformat(),
            ],
        });
        http = TestBed.inject(HttpTestingController);
        router = TestBed.inject(Router);
        fixture = TestBed.createComponent(App);
        document.body.appendChild(fixture.nativeElement);
        await router.navigateByUrl(url);
        await settle();
    }

    async function settle(): Promise<void> {
        for (let i = 0; i < 8; i++) {
            await new Promise((resolve) => setTimeout(resolve, 0));
            http.match(() => true).forEach((request) => {
                const body = answer(request);
                if (body !== undefined) request.flush(body);
            });
            fixture.detectChanges();
        }
    }

    const ctx = (): string | null => router.routerState.snapshot.root.queryParamMap.get('chatCtx');
    const chips = (): string[] =>
        Array.from(document.querySelectorAll('lg-chat-panel .lg-chat-chip-text')).map((chip) => chip.textContent?.trim() ?? '');

    async function press(selector: string): Promise<void> {
        const button = document.querySelector<HTMLButtonElement>(selector);
        expect(button, selector).not.toBeNull();
        button!.click();
        await settle();
    }

    it('pins a filtered shortlist into a new conversation, keeps it over a reload, and carries it into the create', async () => {
        await boot('/shortlist?q=kafka&deadlineOpen=1');
        await press('.lg-chat-use-view');

        expect(router.routerState.snapshot.root.queryParamMap.get('chat')).toBe('new');
        expect(ctx()).toBe(`v:${encodeURIComponent('q=kafka&deadlineOpen=1')}`);
        expect(chips()).toEqual(['Shortlist · 2 filters']);

        await boot(router.url);
        expect(chips()).toEqual(['Shortlist · 2 filters']);
    });

    it('pins the analytics window into the open conversation and stores it there', async () => {
        await boot('/analytics?chat=7');
        await press('.lg-chat-use-view');

        expect(puts).toEqual([[{kind: 'ANALYTICS_WINDOW', from: '2026-08-31', to: '2026-09-07'}]]);
        expect(ctx()).toBe('w:2026-08-31..2026-09-07');
        expect(chips()).toEqual(['Window · 2026-08-31–2026-09-07']);

        await boot(router.url);
        expect(chips()).toEqual(['Window · 2026-08-31–2026-09-07']);
    });

    it('pins an offer into the open conversation beside what it holds, and a removed chip leaves the URL and the conversation', async () => {
        stored = [{kind: 'ANALYTICS_WINDOW', from: '2026-08-31', to: '2026-09-07'}];
        await boot('/shortlist/1?chat=7');
        expect(chips()).toEqual(['Window · 2026-08-31–2026-09-07']);

        await press('.lg-chat-ask-offer');
        expect(router.routerState.snapshot.root.queryParamMap.get('chat')).toBe('7');
        expect(puts.at(-1)).toEqual([
            {kind: 'ANALYTICS_WINDOW', from: '2026-08-31', to: '2026-09-07'},
            {kind: 'OFFER', offerId: 1},
        ]);
        expect(ctx()).toBe('w:2026-08-31..2026-09-07,o:1');
        expect(chips()).toEqual(['Window · 2026-08-31–2026-09-07', 'Offer 1']);

        await press('lg-chat-panel .lg-chat-chip-remove[aria-label="Remove Window · 2026-08-31–2026-09-07"]');
        expect(puts.at(-1)).toEqual([{kind: 'OFFER', offerId: 1}]);
        expect(ctx()).toBe('o:1');
        expect(chips()).toEqual(['Offer 1']);

        await boot(router.url);
        expect(chips()).toEqual(['Offer 1']);
    });
});

/** Ten offers already pinned: the limit a conversation holds (ISC-453). */
const TEN: readonly ChatContextItem[] = Array.from({length: 10}, (_, i) => ({kind: 'OFFER' as const, offerId: 21 + i}));

/**
 * "Ask about N offers" and the composer's `@` search (ISC-453): a selection on the shortlist pins
 * every picked offer at once, `@` pins one found by its title, and neither takes an eleventh.
 */
describe('pinning offers by selection and by @ (ISC-453)', () => {
    let fixture: ComponentFixture<App>;
    let http: HttpTestingController;
    let router: Router;

    beforeEach(() => {
        stored = [];
        puts = [];
    });

    afterEach(() => (fixture.nativeElement as HTMLElement).remove());

    async function boot(url: string): Promise<void> {
        if (fixture) (fixture.nativeElement as HTMLElement).remove();
        TestBed.resetTestingModule();
        TestBed.configureTestingModule({
            imports: [
                TranslocoTestingModule.forRoot({
                    langs: {en},
                    translocoConfig: {availableLangs: ['en', 'de'], defaultLang: 'en'},
                    preloadLangs: true,
                }),
            ],
            providers: [
                provideHttpClient(),
                provideHttpClientTesting(),
                provideRouter(routes, withComponentInputBinding()),
                provideScoreThresholds(),
                provideChartPalette(),
                provideTranslocoMessageformat(),
            ],
        });
        http = TestBed.inject(HttpTestingController);
        router = TestBed.inject(Router);
        fixture = TestBed.createComponent(App);
        document.body.appendChild(fixture.nativeElement);
        await router.navigateByUrl(url);
        await settle();
    }

    async function settle(): Promise<void> {
        for (let i = 0; i < 8; i++) {
            await new Promise((resolve) => setTimeout(resolve, 0));
            http.match(() => true).forEach((request) => {
                const body = answer(request);
                if (body !== undefined) request.flush(body);
            });
            fixture.detectChanges();
        }
    }

    const ctx = (): string | null => router.routerState.snapshot.root.queryParamMap.get('chatCtx');
    const chips = (): string[] =>
        Array.from(document.querySelectorAll('lg-chat-panel .lg-chat-chip-text')).map((chip) => chip.textContent?.trim() ?? '');
    const text = (selector: string): string => document.querySelector(selector)?.textContent?.replace(/\s+/g, ' ').trim() ?? '';

    async function pick(...ids: number[]): Promise<void> {
        for (const id of ids) {
            const box = document.querySelector<HTMLInputElement>(`lg-offer-card input[aria-label*="Kafka backend ${id}"]`);
            expect(box, `checkbox of offer ${id}`).not.toBeNull();
            box!.click();
            await settle();
        }
    }

    async function press(selector: string): Promise<void> {
        const button = document.querySelector<HTMLButtonElement>(selector);
        expect(button, selector).not.toBeNull();
        button!.click();
        await settle();
    }

    /** Types into the composer the way a keyboard does, then waits out the search's pause. */
    async function type(value: string): Promise<void> {
        const field = document.querySelector<HTMLTextAreaElement>('#lg-chat-input')!;
        field.focus();
        field.value = value;
        field.setSelectionRange(value.length, value.length);
        field.dispatchEvent(new Event('input', {bubbles: true}));
        await new Promise((resolve) => setTimeout(resolve, 300));
        await settle();
    }

    async function key(name: string): Promise<void> {
        document.querySelector('#lg-chat-input')!.dispatchEvent(new KeyboardEvent('keydown', {key: name, bubbles: true, cancelable: true}));
        await settle();
    }

    it('asks about three picked offers in one new conversation holding three chips', async () => {
        await boot('/shortlist');
        await pick(1, 2, 3);

        expect(text('.lg-chat-ask-picked')).toBe('Ask about 3 offers');
        await press('.lg-chat-ask-picked');

        expect(router.routerState.snapshot.root.queryParamMap.get('chat')).toBe('new');
        expect(ctx()).toBe('o:1,o:2,o:3');
        expect(chips()).toEqual(['Offer 1', 'Offer 2', 'Offer 3']);
    });

    it('pins the picked offers into the open conversation with one replacement', async () => {
        await boot('/shortlist?chat=7');
        await pick(1, 3);
        await press('.lg-chat-ask-picked');

        expect(puts).toEqual([[{kind: 'OFFER', offerId: 1}, {kind: 'OFFER', offerId: 3}]]);
        expect(chips()).toEqual(['Offer 1', 'Offer 3']);
    });

    it('opens an upward listbox on @, moves with the arrows, and a pick adds the chip and takes the @query out', async () => {
        await boot('/shortlist?chat=7');
        await type('Compare @kafka');

        const combo = document.querySelector('#lg-chat-input')!;
        expect(combo.getAttribute('role')).toBe('combobox');
        expect(combo.getAttribute('aria-expanded')).toBe('true');
        const options = Array.from(document.querySelectorAll('.lg-chat-mention [role="option"]'));
        expect(options.map((option) => option.querySelector('.lg-chat-mention-title')?.textContent?.trim())).toEqual([
            'Kafka backend 11',
            'Kafka backend 12',
        ]);
        const search = http.match(() => true);
        expect(search).toEqual([]);

        await key('ArrowDown');
        expect(combo.getAttribute('aria-activedescendant')).toBe(options[1].id);
        await key('Enter');

        expect(puts).toEqual([[{kind: 'OFFER', offerId: 12}]]);
        expect(chips()).toEqual(['Offer 12']);
        expect((combo as HTMLTextAreaElement).value).toBe('Compare ');
        expect(document.querySelector('.lg-chat-mention')).toBeNull();
    });

    it('closes the listbox on Escape and keeps the typed text', async () => {
        await boot('/shortlist?chat=7');
        await type('@kafka');
        expect(document.querySelector('.lg-chat-mention')).not.toBeNull();

        await key('Escape');
        expect(document.querySelector('.lg-chat-mention')).toBeNull();
        expect(document.querySelector<HTMLTextAreaElement>('#lg-chat-input')!.value).toBe('@kafka');
        expect(puts).toEqual([]);
    });

    it('refuses an eleventh offer from @ and from the selection, each with the limit\'s reason', async () => {
        stored = TEN;
        await boot('/shortlist?chat=7');
        expect(new Set(chips()).size).toBe(10);

        await type('@kafka');
        await key('Enter');
        expect(puts).toEqual([]);
        expect(new Set(chips()).size).toBe(10);
        expect(text('.lg-chat-context-refused')).toBe('A conversation holds at most 10 offers.');

        await pick(1);
        await press('.lg-chat-ask-picked');
        expect(puts).toEqual([]);
        expect(text('.lg-chat-ask-refused')).toBe('A conversation holds at most 10 offers.');
        expect(new Set(chips()).size).toBe(10);
    });
});
