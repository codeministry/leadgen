import {HttpErrorResponse, provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {TestBed} from '@angular/core/testing';
import {firstValueFrom, toArray} from 'rxjs';
import {AuthService} from '@core/auth/auth.service';
import {ChatEvent} from '@core/model/chat';
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

    beforeEach(() => {
        token = null;
        TestBed.configureTestingModule({
            providers: [
                provideHttpClient(),
                provideHttpClientTesting(),
                {provide: AuthService, useValue: {token: () => token}},
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

        api.create(2291).subscribe();
        const create = http.expectOne({method: 'POST', url: `${BASE}/conversations`});
        expect(create.request.body).toEqual({pinnedOfferId: 2291});
        create.flush({});

        api.create(null).subscribe();
        const bare = http.expectOne({method: 'POST', url: `${BASE}/conversations`});
        expect(bare.request.body).toEqual({});
        bare.flush({});

        api.delete(4).subscribe();
        http.expectOne({method: 'DELETE', url: `${BASE}/conversations/4`}).flush(null);

        api.stop(4, 9).subscribe();
        http.expectOne({method: 'POST', url: `${BASE}/conversations/4/turns/9/stop`}).flush(null);
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
