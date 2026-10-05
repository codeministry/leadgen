import {HttpErrorResponse, provideHttpClient} from '@angular/common/http';
import {provideHttpClientTesting} from '@angular/common/http/testing';
import {ComponentFixture, TestBed} from '@angular/core/testing';
import {provideRouter, Router} from '@angular/router';
import {Dispatcher, Events} from '@ngrx/signals/events';
import {Observable, of, Subject, Subscription, throwError} from 'rxjs';
import {ChatApi} from '@core/api/chat.api';
import {replayTurn} from '@core/api/chat-stub';
import {ChatEvent, ConversationView} from '@core/model/chat';
import {chatEvents} from '@core/store/chat.events';
import {ChatStore} from '@core/store/chat.store';
import {UnsavedWork} from '@core/unsaved/unsaved-work';
import {unsentQuestion} from '@core/unsaved/unsent-question';
import {ChatComposer} from './chat-composer';

const CREATED: ConversationView = {id: 9, title: 'Kafka', pinnedOfferId: null, turns: [], updatedAt: '2026-09-27T08:00:00Z'};

/** Twenty text chunks between the turn's opening and its sources, as a long answer streams. */
const TWENTY_CHUNKS: readonly ChatEvent[] = [
    {event: 'turn', data: {turnId: 31}},
    ...Array.from({length: 20}, (_, i): ChatEvent => ({event: 'text', data: {delta: `word${i} `}})),
    {
        event: 'sources',
        data: {
            sources: [
                {n: 1, kind: 'OFFER', id: 1, title: 'One', source: 'List', date: null, archived: false},
                {n: 2, kind: 'OFFER', id: 2, title: 'Two', source: 'List', date: null, archived: false},
                {n: 3, kind: 'OFFER', id: 3, title: 'Three', source: 'List', date: null, archived: false},
            ],
        },
    },
    {event: 'done', data: {state: 'DONE'}},
];

describe('ChatComposer (ISC-436)', () => {
    let fixture: ComponentFixture<ChatComposer>;
    let store: InstanceType<typeof ChatStore>;
    const asked: string[] = [];

    beforeEach(async () => {
        asked.length = 0;
        TestBed.configureTestingModule({
            providers: [
                provideRouter([]),
                provideHttpClient(),
                provideHttpClientTesting(),
                {
                    provide: ChatApi,
                    useValue: {
                        create: () => of(CREATED),
                        ask: () => replayTurn(TWENTY_CHUNKS, 0),
                        capability: () => of({present: true}),
                    },
                },
            ],
        });
        store = TestBed.inject(ChatStore);
        TestBed.inject(Events).on(chatEvents.asked).subscribe(({payload}) => asked.push(payload));
        // A question is taken under `?chat=new` or an open conversation only (fix 2F-6).
        await TestBed.inject(Router).navigateByUrl('/?chat=new');
        fixture = TestBed.createComponent(ChatComposer);
        document.body.appendChild(fixture.nativeElement);
        fixture.detectChanges();
    });

    afterEach(() => {
        (fixture.nativeElement as HTMLElement).remove();
        window.sessionStorage.clear();
    });

    const input = () => fixture.nativeElement.querySelector('textarea') as HTMLTextAreaElement | null;
    const region = () => fixture.nativeElement.querySelector('[aria-live="polite"]') as HTMLElement | null;

    function type(text: string): void {
        input()!.value = text;
        input()!.dispatchEvent(new Event('input'));
        fixture.detectChanges();
    }

    function press(key: string, shiftKey = false): KeyboardEvent {
        const event = new KeyboardEvent('keydown', {key, shiftKey, bubbles: true, cancelable: true});
        input()!.dispatchEvent(event);
        fixture.detectChanges();
        return event;
    }

    it('sends on Enter and breaks the line on Shift+Enter', () => {
        expect(input()).not.toBeNull();
        expect(input()!.getAttribute('enterkeyhint')).toBe('send');
        type('Which offers asked for Kafka?');

        const shifted = press('Enter', true);
        expect(shifted.defaultPrevented).toBe(false);
        expect(asked).toEqual([]);

        const plain = press('Enter');
        expect(plain.defaultPrevented).toBe(true);
        expect(asked).toEqual(['Which offers asked for Kafka?']);
        expect(input()!.value).toBe('');
    });

    // Fix 3F-4: the draft was cleared although the store threw the question away.
    it('keeps the draft when the store cannot take a question because a deleted turn still streams', async () => {
        const stream = new Subject<ChatEvent>();
        const api = TestBed.inject(ChatApi);
        vi.spyOn(api, 'ask').mockReturnValue(stream);
        // `deleted` sends the drawer to the list, which reads it.
        Object.assign(api, {list: () => of([])});
        type('First?');
        press('Enter');
        stream.next({event: 'turn', data: {turnId: 31}});
        await vi.waitFor(() => expect(store.view()).toBe('conversation'));

        TestBed.inject(Dispatcher).dispatch(chatEvents.deleted(CREATED.id));
        fixture.detectChanges();
        type('Second?');
        press('Enter');

        expect(asked).not.toContain('Second?');
        expect(input()!.value).toBe('Second?');
    });

    // Fix 3F-5: the server's NewTurn is `@Size(max = 4000)`.
    it('caps the question at the server limit and counts the characters near it', () => {
        expect(input()!.getAttribute('maxlength')).toBe('4000');
        const count = () => fixture.nativeElement.querySelector('.lg-chat-count') as HTMLElement | null;
        type('x'.repeat(100));
        expect(count()).toBeNull();
        type('x'.repeat(3700));
        expect(count()?.textContent?.replace(/\s+/g, '')).toBe('3700/4000');
    });

    it('sends nothing while an input method is composing', () => {
        type('Kafka');
        input()!.dispatchEvent(new KeyboardEvent('keydown', {key: 'Enter', bubbles: true, cancelable: true, isComposing: true}));
        expect(asked).toEqual([]);
    });

    describe('a question the server never stored comes back (spec 024, code review 7)', () => {
        function failingAsk(): Subject<ChatEvent> {
            const stream = new Subject<ChatEvent>();
            vi.spyOn(TestBed.inject(ChatApi), 'ask').mockReturnValue(stream);
            return stream;
        }

        async function settled(): Promise<void> {
            await vi.waitFor(() => expect(store.streaming()).toBe(false));
            fixture.detectChanges();
        }

        for (const [why, status] of [
            ['refused for want of a session', 401],
            ['timed out on its renewal', 0],
            ['refused for having no model', 503],
        ] as const) {
            it(`${why}: into the empty field, kept beyond the page, so it holds no redirect`, async () => {
                const stream = failingAsk();
                type('Which offers asked for Kafka?');
                press('Enter');
                expect(input()!.value).toBe('');

                stream.error(new HttpErrorResponse({status}));
                await settled();

                expect(input()!.value).toBe('Which offers asked for Kafka?');
                expect(TestBed.inject(UnsavedWork).any()).toBe(false);
            });
        }

        it('a question the server named stays sent, whatever happens to its answer', async () => {
            const stream = failingAsk();
            type('Which offers asked for Kafka?');
            press('Enter');
            stream.next({event: 'turn', data: {turnId: 31}});
            stream.error(new HttpErrorResponse({status: 0}));
            await settled();

            expect(input()!.value).toBe('');
            expect(TestBed.inject(UnsavedWork).any()).toBe(false);
            expect(unsentQuestion()).toBeNull();
        });

        it('ahead of one typed meanwhile, kept as typed, so neither is lost (code review 8, finding 6; review 11, finding 2)', async () => {
            const stream = failingAsk();
            type('Which offers asked for Kafka?');
            press('Enter');
            type('And for ');
            stream.error(new HttpErrorResponse({status: 401}));
            await settled();

            expect(input()!.value).toBe('Which offers asked for Kafka?\n\nAnd for ');
        });

        it('into the conversation open now, which may not be the one it was asked in (code review 10, finding 1)', async () => {
            // Under an ended session the thread it was asked in may never load again; shown here it
            // is still the person's to move, held for that thread it would be lost.
            const stream = failingAsk();
            type('Which offers asked for Kafka?');
            press('Enter');
            await vi.waitFor(() => expect(store.view()).toBe('conversation'));
            await TestBed.inject(Router).navigateByUrl('/?chat=new');
            fixture.detectChanges();

            stream.error(new HttpErrorResponse({status: 401}));
            await settled();

            expect(input()!.value).toBe('Which offers asked for Kafka?');
        });

        it('holds the redirect when the tab refused to keep it, and once it is edited (final review, finding 1)', async () => {
            const stream = failingAsk();
            const write = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
                throw new DOMException('quota', 'QuotaExceededError');
            });
            type('Which offers asked for Kafka?');
            press('Enter');
            stream.error(new HttpErrorResponse({status: 401}));
            await settled();
            write.mockRestore();

            expect(input()!.value).toBe('Which offers asked for Kafka?');
            expect(TestBed.inject(UnsavedWork).any()).toBe(true);
        });

        it('not while the composer stands where no question is taken', async () => {
            const stream = failingAsk();
            type('Which offers asked for Kafka?');
            press('Enter');
            await vi.waitFor(() => expect(store.view()).toBe('conversation'));
            await TestBed.inject(Router).navigateByUrl('/?chat=abc');
            fixture.detectChanges();

            stream.error(new HttpErrorResponse({status: 401}));
            await settled();

            expect(input()!.value).toBe('');
            expect(unsentQuestion()).toBe('Which offers asked for Kafka?');
        });

        it('after the sign-in an ended session took the page through: a new composer brings it back (code review 11)', async () => {
            const stream = failingAsk();
            type('Which offers asked for Kafka?');
            press('Enter');
            stream.error(new HttpErrorResponse({status: 401}));
            await settled();
            // The page leaves before anybody copies it.
            fixture.destroy();

            fixture = TestBed.createComponent(ChatComposer);
            document.body.appendChild(fixture.nativeElement);
            fixture.detectChanges();

            expect(input()!.value).toBe('Which offers asked for Kafka?');
        });

        it('emptying the field forgets it, and the next send keeps the next one', async () => {
            const stream = failingAsk();
            type('Which offers asked for Kafka?');
            press('Enter');
            stream.error(new HttpErrorResponse({status: 503}));
            await settled();
            expect(input()!.value).toBe('Which offers asked for Kafka?');

            type('');
            expect(unsentQuestion()).toBeNull();
        });

        it('not a follow-up or a suggestion, which is a click away from being asked again (code review 11, finding 5)', async () => {
            const stream = failingAsk();
            type('my own draft');
            TestBed.inject(Dispatcher).dispatch(chatEvents.asked('A suggested question?'));
            stream.error(new HttpErrorResponse({status: 503}));
            await settled();

            expect(input()!.value).toBe('my own draft');
            expect(unsentQuestion()).toBeNull();
        });

        it('one whose conversation could not be created', async () => {
            vi.spyOn(TestBed.inject(ChatApi), 'create').mockReturnValue(throwError(() => new HttpErrorResponse({status: 401})));
            type('Which offers asked for Kafka?');
            press('Enter');
            await settled();

            expect(input()!.value).toBe('Which offers asked for Kafka?');
        });
    });

    it('announces a finished answer once, politely, and nothing per chunk', async () => {
        expect(region()).not.toBeNull();
        const heard: {text: string; streaming: boolean}[] = [];
        const observer = new MutationObserver(() => heard.push({text: region()!.textContent!.trim(), streaming: store.streaming()}));
        observer.observe(region()!, {childList: true, characterData: true, subtree: true});

        type('Which offers asked for Kafka?');
        press('Enter');
        for (let i = 0; i < 60 && store.live()?.state !== 'DONE'; i++) {
            await new Promise((resolve) => setTimeout(resolve, 0));
            fixture.detectChanges();
        }
        fixture.detectChanges();
        await Promise.resolve();
        observer.disconnect();

        const sentence = 'Answer ready, 3 sources';
        expect(store.live()?.answer.split(' ').filter(Boolean).length).toBe(20);
        const spoken = heard.filter((h) => h.text !== '');
        expect(spoken.map((h) => h.text)).toEqual([sentence]);
        expect(spoken.every((h) => !h.streaming)).toBe(true);
    });
});

/**
 * A stand-in for the server's side of one turn: a chunk on every tick until stop is called,
 * then — as `ChatTurnService` does — the model call cancelled, `done {STOPPED}` and the end of
 * the stream. Nothing the timer still held is sent.
 */
function stoppableTurn(delayMs: number) {
    let stopped: (() => void) | null = null;
    const stream = new Observable<ChatEvent>((subscriber) => {
        let sent = 0;
        subscriber.next({event: 'turn', data: {turnId: 31}});
        const timer = setInterval(() => subscriber.next({event: 'text', data: {delta: `word${sent++} `}}), delayMs);
        stopped = () => {
            clearInterval(timer);
            subscriber.next({event: 'done', data: {state: 'STOPPED'}});
            subscriber.complete();
        };
        return () => clearInterval(timer);
    });
    return {stream, stop: () => stopped?.()};
}

describe('ChatComposer stop (ISC-439)', () => {
    let fixture: ComponentFixture<ChatComposer>;
    let store: InstanceType<typeof ChatStore>;
    const asked: string[] = [];
    const stops: [number, number][] = [];
    let server: ReturnType<typeof stoppableTurn>;
    let listening: Subscription;

    beforeEach(async () => {
        asked.length = 0;
        stops.length = 0;
        server = stoppableTurn(10);
        TestBed.configureTestingModule({
            providers: [
                provideRouter([]),
                provideHttpClient(),
                provideHttpClientTesting(),
                {
                    provide: ChatApi,
                    useValue: {
                        create: () => of(CREATED),
                        ask: () => server.stream,
                        stop: (conversationId: number, turnId: number) => {
                            stops.push([conversationId, turnId]);
                            server.stop();
                            return of(undefined);
                        },
                        capability: () => of({present: true}),
                        get: (id: number) => of({...CREATED, id, title: `Conversation ${id}`}),
                    },
                },
            ],
        });
        store = TestBed.inject(ChatStore);
        listening = TestBed.inject(Events).on(chatEvents.asked).subscribe(({payload}) => asked.push(payload));
        await TestBed.inject(Router).navigateByUrl('/?chat=new');
        fixture = TestBed.createComponent(ChatComposer);
        document.body.appendChild(fixture.nativeElement);
        fixture.detectChanges();
    });

    // The event bus outlives a TestBed reset, so a listener left behind hears the next test too.
    afterEach(() => {
        listening.unsubscribe();
        (fixture.nativeElement as HTMLElement).remove();
    });

    const input = () => fixture.nativeElement.querySelector('textarea') as HTMLTextAreaElement;
    const action = () => fixture.nativeElement.querySelector('.lg-chat-send') as HTMLButtonElement;
    const chunks = () => (store.live()?.answer ?? '').split(' ').filter(Boolean).length;

    function type(text: string): void {
        input().value = text;
        input().dispatchEvent(new Event('input'));
        fixture.detectChanges();
    }

    async function until(done: () => boolean): Promise<void> {
        for (let i = 0; i < 400 && !done(); i++) {
            await new Promise((resolve) => setTimeout(resolve, 1));
        }
        fixture.detectChanges();
    }

    it('turns send into stop while a turn streams, and a stop after the third chunk keeps three', async () => {
        type('Which offers asked for Kafka?');
        action().click();
        await until(() => chunks() >= 3);
        expect(store.streaming()).toBe(true);
        expect(chunks()).toBe(3);

        // The same button, not a second one: its name and glyph change, its place does not.
        expect(fixture.nativeElement.querySelectorAll('.lg-chat-field-foot button').length).toBe(1);
        expect(action().getAttribute('aria-label')).toBe('Stop');
        expect(action().disabled).toBe(false);

        action().click();
        const held = store.live()!.answer;
        await new Promise((resolve) => setTimeout(resolve, 80));
        fixture.detectChanges();

        expect(stops).toEqual([[9, 31]]);
        expect(store.live()?.state).toBe('STOPPED');
        // No text event after the stop: the turn holds what it held when stop was pressed.
        expect(store.live()?.answer).toBe(held);
        expect(held.split(' ').filter(Boolean).length).toBe(3);
    });

    it('is send again after STOPPED, and takes the next question', async () => {
        type('Which offers asked for Kafka?');
        action().click();
        await until(() => chunks() >= 3);
        action().click();
        await until(() => store.live()?.state === 'STOPPED');

        expect(action().getAttribute('aria-label')).toBe('Send');
        type('And for Flink?');
        expect(action().disabled).toBe(false);
        action().click();
        expect(asked).toEqual(['Which offers asked for Kafka?', 'And for Flink?']);
    });

    // Fix 2F-7: `streaming()` is global, so another conversation's composer showed Stop and stopped this turn.
    it('shows send, never stop, in a conversation other than the one streaming, and its button stops nothing', async () => {
        type('Which offers asked for Kafka?');
        action().click();
        await until(() => chunks() >= 2);
        await TestBed.inject(Router).navigateByUrl('/?chat=12');
        await until(() => store.conversation()?.id === 12);
        expect(store.streaming()).toBe(true);

        expect(action().getAttribute('aria-label')).toBe('Send');
        expect(action().disabled).toBe(true);
        type('Something else');
        action().click();
        await new Promise((resolve) => setTimeout(resolve, 30));

        expect(stops).toEqual([]);
        expect(store.streaming()).toBe(true);
        server.stop();
    });

    // Fix 2F-6: a question there would create a conversation the URL never follows.
    it('is disabled where no question is taken: a missing conversation and one still loading', async () => {
        const router = TestBed.inject(Router);
        await router.navigateByUrl('/?chat=abc');
        fixture.detectChanges();
        type('Anything?');
        expect(input().disabled).toBe(true);
        expect(action().disabled).toBe(true);
        action().click();
        expect(asked).toEqual([]);

        vi.spyOn(TestBed.inject(ChatApi), 'get').mockReturnValue(new Observable<ConversationView>());
        await router.navigateByUrl('/?chat=13');
        fixture.detectChanges();
        expect(store.loading()).toBe(true);
        expect(input().disabled).toBe(true);
        expect(action().disabled).toBe(true);
    });
});
