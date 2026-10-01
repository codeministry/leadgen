import {HttpErrorResponse, provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {TestBed} from '@angular/core/testing';
import {firstValueFrom, toArray} from 'rxjs';
import {AuthService} from '@core/auth/auth.service';
import {ChatEvent, ConversationView, formatChatCtx, parseChatCtx} from '@core/model/chat';
import {ChatApi} from './chat.api';

const BASE = '/api/v1/chat';

/** A response whose body arrives in exactly these chunks, as a real network would cut it. */
function streamed(chunks: readonly string[], status = 200): Response {
    const encoder = new TextEncoder();
    const body = new ReadableStream<Uint8Array>({
        start(controller) {
            for (const chunk of chunks) controller.enqueue(encoder.encode(chunk));
            controller.close();
        },
    });
    return {ok: status >= 200 && status < 300, status, statusText: 'x', body} as unknown as Response;
}

describe('ChatApi', () => {
    let api: ChatApi;
    let http: HttpTestingController;
    let token: string | null;
    let expired: number;

    beforeEach(() => {
        token = null;
        expired = 0;
        TestBed.configureTestingModule({
            providers: [
                provideHttpClient(),
                provideHttpClientTesting(),
                {provide: AuthService, useValue: {token: () => token, sessionExpired: () => (expired += 1)}},
            ],
        });
        api = TestBed.inject(ChatApi);
        http = TestBed.inject(HttpTestingController);
    });

    afterEach(() => {
        http.verify();
        vi.unstubAllGlobals();
    });

    it('speaks the REST half through HttpClient', () => {
        api.capability().subscribe();
        http.expectOne({method: 'GET', url: `${BASE}/capability`}).flush({present: true});

        api.list().subscribe();
        http.expectOne({method: 'GET', url: `${BASE}/conversations`}).flush([]);

        api.get(4).subscribe();
        http.expectOne({method: 'GET', url: `${BASE}/conversations/4`}).flush({});

        api.create([{kind: 'OFFER', offerId: 2291}]).subscribe();
        const create = http.expectOne({method: 'POST', url: `${BASE}/conversations`});
        expect(create.request.body).toEqual({context: [{kind: 'OFFER', offerId: 2291}]});
        create.flush({});

        api.create([]).subscribe();
        const bare = http.expectOne({method: 'POST', url: `${BASE}/conversations`});
        expect(bare.request.body).toEqual({});
        bare.flush({});

        api.delete(4).subscribe();
        http.expectOne({method: 'DELETE', url: `${BASE}/conversations/4`}).flush(null);

        api.stop(4, 9).subscribe();
        http.expectOne({method: 'POST', url: `${BASE}/conversations/4/turns/9/stop`}).flush(null);
    });

    it('speaks the contracts of spec 020: rename, search, context, suggestions, follow-ups (ISC-449)', () => {
        const renamed: ConversationView[] = [];
        api.rename(4, 'Kafka offers').subscribe((c) => renamed.push(c));
        const rename = http.expectOne({method: 'PATCH', url: `${BASE}/conversations/4`});
        expect(rename.request.body).toEqual({title: 'Kafka offers'});
        rename.flush({id: 4, title: 'Kafka offers', pinnedOfferId: null, turns: [], updatedAt: '2026-09-27T08:00:00Z'});
        expect(renamed.map((c) => c.title)).toEqual(['Kafka offers']);

        api.list('  kafka remote ').subscribe();
        const search = http.expectOne((r) => r.method === 'GET' && r.url === `${BASE}/conversations`);
        expect(search.request.params.get('q')).toBe('kafka remote');
        search.flush([{id: 4, title: 'Kafka', updatedAt: '2026-09-27T08:00:00Z', lastActivityAt: '2026-09-27T09:00:00Z'}]);

        api.list('   ').subscribe();
        const blank = http.expectOne((r) => r.method === 'GET' && r.url === `${BASE}/conversations`);
        expect(blank.request.params.has('q')).toBe(false);
        blank.flush([]);

        api.setContext(4, [{kind: 'OFFER', offerId: 2291}]).subscribe();
        const context = http.expectOne({method: 'PUT', url: `${BASE}/conversations/4/context`});
        expect(context.request.body).toEqual({context: [{kind: 'OFFER', offerId: 2291}]});
        context.flush({});

        api.suggestions({conversationId: 4}).subscribe();
        const stored = http.expectOne((r) => r.method === 'GET' && r.url === `${BASE}/suggestions`);
        expect(stored.request.params.get('conversation')).toBe('4');
        expect(stored.request.params.has('context')).toBe(false);
        stored.flush([]);

        api.suggestions({context: [{kind: 'OFFER', offerId: 2291}, {kind: 'OFFER', offerId: 7}]}).subscribe();
        const unstored = http.expectOne((r) => r.method === 'GET' && r.url === `${BASE}/suggestions`);
        expect(unstored.request.params.get('context')).toBe('o:2291,o:7');
        expect(unstored.request.params.has('conversation')).toBe(false);
        unstored.flush([{trigger: 'NEW_THIS_WEEK', text: 'What came in this week?', count: 12}]);

        api.suggestions({context: []}).subscribe();
        const bare = http.expectOne((r) => r.method === 'GET' && r.url === `${BASE}/suggestions`);
        expect(bare.request.params.keys()).toEqual([]);
        bare.flush([]);

        api.followups(4, 9).subscribe();
        http.expectOne({method: 'GET', url: `${BASE}/conversations/4/turns/9/followups`}).flush([{text: 'And remote only?'}]);
    });

    it('turns `?chatCtx` into pins and back, skipping what it cannot read (ISC-446, ISC-451)', () => {
        const pins = [
            {kind: 'OFFER', offerId: 2291},
            {kind: 'SHORTLIST_VIEW', query: 'q=kafka&band=above'},
            {kind: 'ANALYTICS_WINDOW', from: '2026-09-01', to: '2026-09-27'},
            {kind: 'OFFER', offerId: 7},
        ] as const;
        expect(parseChatCtx('o:2291')).toEqual([{kind: 'OFFER', offerId: 2291}]);
        expect(parseChatCtx('o:2291,v:q%3Dkafka%26band%3Dabove,w:2026-09-01..2026-09-27,o:7')).toEqual(pins);
        expect(parseChatCtx(formatChatCtx(pins))).toEqual(pins);
        expect(parseChatCtx('o:0,o:abc,w:2026-09-01,v:%E0%A4%A,')).toEqual([]);
        expect(parseChatCtx(null)).toEqual([]);
        expect(formatChatCtx([{kind: 'OFFER', offerId: 2291}])).toBe('o:2291');
        expect(formatChatCtx([])).toBeNull();
    });

    it('reads a turn from a POST stream, one event cut across two chunks', async () => {
        const fetch = vi.fn().mockResolvedValue(
            streamed([
                'event: turn\ndata: {"turnId":7}\n\n',
                // The text event is split mid-JSON, and the split falls inside a multi-byte character.
                'event: text\ndata: {"delta":"Fourteen offers ⟨unver',
                'ified:4471⟩ came in"}\n\nevent: sources\ndata: {"sources":[]}\n\n',
                'event: done\ndata: {"state":"DONE"}\n\n',
            ]),
        );
        vi.stubGlobal('fetch', fetch);

        const events = await firstValueFrom(api.ask(4, 'What came in?').pipe(toArray()));

        expect(events).toEqual<ChatEvent[]>([
            {event: 'turn', data: {turnId: 7}},
            {event: 'text', data: {delta: 'Fourteen offers ⟨unverified:4471⟩ came in'}},
            {event: 'sources', data: {sources: []}},
            {event: 'done', data: {state: 'DONE'}},
        ]);
        const [url, init] = fetch.mock.calls[0] as [string, RequestInit];
        expect(url).toBe(`${BASE}/conversations/4/turns`);
        expect(init.method).toBe('POST');
        expect(JSON.parse(init.body as string)).toEqual({question: 'What came in?'});
        expect(new Headers(init.headers).get('Accept')).toBe('text/event-stream');
        expect(new Headers(init.headers).get('Authorization')).toBeNull();
    });

    it('splits a chunk that holds a byte of a character only', async () => {
        const encoder = new TextEncoder();
        const bytes = encoder.encode('event: text\ndata: {"delta":"⟨"}\n\n');
        const cut = bytes.indexOf(0xe2) + 1;
        const body = new ReadableStream<Uint8Array>({
            start(controller) {
                controller.enqueue(bytes.slice(0, cut));
                controller.enqueue(bytes.slice(cut));
                controller.close();
            },
        });
        vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ok: true, status: 200, body}));

        const events = await firstValueFrom(api.ask(4, 'q').pipe(toArray()));

        expect(events).toEqual([{event: 'text', data: {delta: '⟨'}}]);
    });

    it('sends the bearer the interceptor would have sent, since fetch passes no interceptor', async () => {
        token = 'abc';
        const fetch = vi.fn().mockResolvedValue(streamed(['event: done\ndata: {"state":"DONE"}\n\n']));
        vi.stubGlobal('fetch', fetch);

        await firstValueFrom(api.regenerate(4, 9).pipe(toArray()));

        const [url, init] = fetch.mock.calls[0] as [string, RequestInit];
        expect(url).toBe(`${BASE}/conversations/4/turns/9/regenerate`);
        expect(new Headers(init.headers).get('Authorization')).toBe('Bearer abc');
    });

    it('ignores an event name outside the contract', async () => {
        vi.stubGlobal(
            'fetch',
            vi.fn().mockResolvedValue(streamed(['event: ping\ndata: {}\n\n', ': keep-alive\n\n', 'event: done\ndata: {"state":"STOPPED"}\n\n'])),
        );

        const events = await firstValueFrom(api.ask(4, 'q').pipe(toArray()));

        expect(events).toEqual([{event: 'done', data: {state: 'STOPPED'}}]);
    });

    it('fails with the status when the server refuses the stream', async () => {
        vi.stubGlobal('fetch', vi.fn().mockResolvedValue(streamed([], 404)));

        const failure = await firstValueFrom(api.ask(4, 'q')).catch((error: unknown) => error);

        expect(failure).toBeInstanceOf(HttpErrorResponse);
        expect((failure as HttpErrorResponse).status).toBe(404);
    });

    it('reports an ended session on a 401 to a stream that carried the bearer, as the interceptor would (ISC-503)', async () => {
        token = 'abc';
        vi.stubGlobal('fetch', vi.fn().mockResolvedValue(streamed([], 401)));

        const failure = await firstValueFrom(api.ask(4, 'q')).catch((error: unknown) => error);

        expect((failure as HttpErrorResponse).status).toBe(401);
        expect(expired).toBe(1);
    });

    it('reports nothing for a 401 without a bearer, or for any other refusal', async () => {
        vi.stubGlobal('fetch', vi.fn().mockResolvedValue(streamed([], 401)));
        await firstValueFrom(api.ask(4, 'q')).catch(() => undefined);
        token = 'abc';
        vi.stubGlobal('fetch', vi.fn().mockResolvedValue(streamed([], 403)));
        await firstValueFrom(api.ask(4, 'q')).catch(() => undefined);

        expect(expired).toBe(0);
    });

    it('carries the refusal the server wrote, as JSON `detail`, JSON `message` or plain text', async () => {
        const refused = (status: number, body: string, type: string) => ({
            ok: false,
            status,
            statusText: 'x',
            body: null,
            headers: new Headers({'Content-Type': type}),
            text: () => Promise.resolve(body),
        });
        const fetch = vi
            .fn()
            .mockResolvedValueOnce(refused(409, '{"status":409,"detail":"no chat model is configured"}', 'application/problem+json'))
            .mockResolvedValueOnce(refused(503, '{"status":503,"message":"too many turns are running"}', 'application/json'))
            .mockResolvedValueOnce(refused(503, 'too many turns are running', 'text/plain'))
            .mockResolvedValueOnce(refused(502, '', 'text/plain'));
        vi.stubGlobal('fetch', fetch);

        const errors: HttpErrorResponse[] = [];
        for (let i = 0; i < 4; i++) errors.push((await firstValueFrom(api.ask(4, 'q')).catch((e: unknown) => e)) as HttpErrorResponse);

        expect(errors.map((e) => [e.status, e.error])).toEqual([
            [409, 'no chat model is configured'],
            [503, 'too many turns are running'],
            [503, 'too many turns are running'],
            [502, null],
        ]);
    });

    it('aborts the request when the subscriber leaves', async () => {
        // A body that never ends, so only an abort can finish the request.
        const body = new ReadableStream<Uint8Array>({start: () => undefined});
        let signal: AbortSignal | null | undefined;
        vi.stubGlobal(
            'fetch',
            vi.fn((_url: string, init: RequestInit) => {
                signal = init.signal;
                return Promise.resolve({ok: true, status: 200, body});
            }),
        );

        const subscription = api.ask(4, 'q').subscribe();
        await Promise.resolve();
        expect(signal?.aborted).toBe(false);

        subscription.unsubscribe();

        expect(signal?.aborted).toBe(true);
    });
});
