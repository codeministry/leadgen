import {HttpClient, HttpErrorResponse} from '@angular/common/http';
import {inject, Injectable, Injector} from '@angular/core';
import {createParser, EventSourceMessage} from 'eventsource-parser';
import {Observable} from 'rxjs';
import {AuthService} from '@core/auth/auth.service';
import {ChatCapability, ChatEvent, ChatEventMap, ConversationSummary, ConversationView} from '@core/model/chat';

const BASE = '/api/v1/chat';

/** The event names the contract knows; anything else on the wire is skipped, not guessed at. */
const EVENT_NAMES: ReadonlySet<string> = new Set<keyof ChatEventMap>(['turn', 'step', 'text', 'sources', 'error', 'done']);

/**
 * `/api/v1/chat` — the conversations, and the one call that is not a request/response.
 *
 * <p>**A turn is a POST that answers with `text/event-stream`**, which `EventSource` cannot send:
 * it only knows GET. So the stream is read with `fetch` and cut into events by
 * `eventsource-parser`, pinned in `package.json` — the one SSE client of ISC-435. Everything
 * else goes through `HttpClient` like every other API here.
 *
 * <p>**`fetch` passes no interceptor, so the bearer is added here** from the same
 * `AuthService.token()` that `bearerInterceptor` reads. The interceptor's same-origin rule holds
 * without restating it: every URL this class builds starts with `/api/`.
 */
@Injectable({providedIn: 'root'})
export class ChatApi {
    private readonly http = inject(HttpClient);
    // Resolved when a turn streams, not when the API is built: every screen that injects the store
    // would otherwise need the OAuth client just to exist, and a spec that stubs the stream never reaches it.
    private readonly injector = inject(Injector);

    /** Whether a chat model is configured; the header draws no button without one. */
    capability(): Observable<ChatCapability> {
        return this.http.get<ChatCapability>(`${BASE}/capability`);
    }

    /** Newest first; the server orders, the browser does not re-sort. */
    list(): Observable<readonly ConversationSummary[]> {
        return this.http.get<readonly ConversationSummary[]>(`${BASE}/conversations`);
    }

    /** 404 for an id that no longer exists, which the store turns into its missing state. */
    get(id: number): Observable<ConversationView> {
        return this.http.get<ConversationView>(`${BASE}/conversations/${id}`);
    }

    create(pinnedOfferId: number | null): Observable<ConversationView> {
        return this.http.post<ConversationView>(`${BASE}/conversations`, pinnedOfferId === null ? {} : {pinnedOfferId});
    }

    delete(id: number): Observable<void> {
        return this.http.delete<void>(`${BASE}/conversations/${id}`);
    }

    /** Cancels the model call; the open stream then ends with `done {state: STOPPED}`. */
    stop(conversationId: number, turnId: number): Observable<void> {
        return this.http.post<void>(`${BASE}/conversations/${conversationId}/turns/${turnId}/stop`, null);
    }

    /** Asks a question; the observable is the turn's event stream, and unsubscribing aborts it. */
    ask(conversationId: number, question: string): Observable<ChatEvent> {
        return this.stream(`${BASE}/conversations/${conversationId}/turns`, {question});
    }

    /** The same question again, as a new turn that replaces the given one — a stream like `ask`. */
    regenerate(conversationId: number, turnId: number): Observable<ChatEvent> {
        return this.stream(`${BASE}/conversations/${conversationId}/turns/${turnId}/regenerate`, {});
    }

    /**
     * POSTs `body` and emits every contract event of the answer until the server closes it.
     *
     * <p>A refused request fails with an `HttpErrorResponse` carrying the status and the sentence
     * the server wrote ("no chat model is configured", "too many turns are running"), so the store
     * reads it with `serverMessage` exactly as it reads one from `HttpClient`. An abort after the
     * subscriber left is not an error; nobody is listening for one.
     */
    private stream(url: string, body: object): Observable<ChatEvent> {
        return new Observable<ChatEvent>((subscriber) => {
            const abort = new AbortController();
            const parser = createParser({
                onEvent: (message) => {
                    const event = toChatEvent(message);
                    if (event !== null) subscriber.next(event);
                },
            });
            const headers: Record<string, string> = {Accept: 'text/event-stream', 'Content-Type': 'application/json'};
            const token = this.injector.get(AuthService).token();
            if (token) headers['Authorization'] = `Bearer ${token}`;

            const read = async (): Promise<void> => {
                const response = await fetch(url, {method: 'POST', headers, body: JSON.stringify(body), signal: abort.signal});
                if (!response.ok || response.body === null) {
                    const error = response.ok ? null : await refusal(response);
                    throw new HttpErrorResponse({error, status: response.status, statusText: response.statusText, url});
                }
                const reader = response.body.getReader();
                // `stream: true` holds back a character cut between two chunks until its last byte arrives.
                const decoder = new TextDecoder();
                for (;;) {
                    const {done, value} = await reader.read();
                    if (done) break;
                    parser.feed(decoder.decode(value, {stream: true}));
                }
                parser.feed(decoder.decode());
                subscriber.complete();
            };
            read().catch((error: unknown) => {
                if (!abort.signal.aborted) subscriber.error(error);
            });
            return () => abort.abort();
        });
    }
}

/**
 * The sentence a refused turn carries: a ProblemDetail's `detail`, Spring's default `message`, or
 * the body as text. Null when there is none, which leaves `serverMessage` to its catalog fallback.
 */
async function refusal(response: Response): Promise<string | null> {
    let text: string;
    try {
        text = (await response.text()).trim();
    } catch {
        return null; // An unreadable body says nothing; the status still does.
    }
    if (text === '') return null;
    try {
        const body: unknown = JSON.parse(text);
        if (typeof body === 'object' && body !== null) {
            const {detail, message} = body as {detail?: unknown; message?: unknown};
            if (typeof detail === 'string' && detail !== '') return detail;
            if (typeof message === 'string' && message !== '') return message;
            return null;
        }
    } catch {
        // Not JSON: the body is the sentence.
    }
    return text;
}

/** One parsed wire event, or null for a name outside the contract. Bad JSON throws: the stream is broken. */
function toChatEvent(message: EventSourceMessage): ChatEvent | null {
    if (message.event === undefined || !EVENT_NAMES.has(message.event)) return null;
    return {event: message.event, data: JSON.parse(message.data)} as ChatEvent;
}
