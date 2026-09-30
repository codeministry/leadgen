import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {ComponentFixture, TestBed} from '@angular/core/testing';
import {provideRouter, Router} from '@angular/router';
import {injectDispatch} from '@ngrx/signals/events';
import {of, Subject} from 'rxjs';
import {ChatApi} from '@core/api/chat.api';
import {ChatEvent, ChatTurnState, ConversationView, TurnView} from '@core/model/chat';
import {chatEvents} from '@core/store/chat.events';
import {ChatPanel} from './chat-panel';

const BASE = '/api/v1/chat/conversations';

function stored(id: number, state: ChatTurnState): TurnView {
    return {
        id,
        question: `Question ${id}?`,
        answer: `Answer ${id}.`,
        state,
        steps: [],
        sources: [],
        replacesTurnId: null,
        model: null,
        createdAt: '2026-09-27T08:00:00Z',
    };
}

function conversation(turns: readonly TurnView[] = []): ConversationView {
    return {id: 9, title: 'Kafka', pinnedOfferId: null, turns, updatedAt: '2026-09-27T08:00:00Z'};
}

const text = (i: number): ChatEvent => ({event: 'text', data: {delta: `word${i} `}});

/**
 * The glyph beside every answer is the living mark bound to its turn (ISC-465): a stubbed stream
 * driven through the real store into the real panel, and each glyph's `data-frame` read back.
 */
describe('ChatPanel glyph (ISC-465)', () => {
    let fixture: ComponentFixture<ChatPanel>;
    let http: HttpTestingController;
    let server: Subject<ChatEvent>;
    let api: ChatApi;
    let dispatch: ReturnType<typeof injectDispatch<typeof chatEvents>>;

    beforeEach(async () => {
        TestBed.configureTestingModule({
            providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
        });
        http = TestBed.inject(HttpTestingController);
        api = TestBed.inject(ChatApi);
        server = new Subject<ChatEvent>();
        vi.spyOn(api, 'ask').mockReturnValue(server);
        vi.spyOn(api, 'stop').mockReturnValue(of(undefined));
        dispatch = TestBed.runInInjectionContext(() => injectDispatch(chatEvents));
        fixture = TestBed.createComponent(ChatPanel);
        document.body.appendChild(fixture.nativeElement);
        await settle();
    });

    afterEach(() => (fixture.nativeElement as HTMLElement).remove());

    async function settle(): Promise<void> {
        http.match((r) => r.url.endsWith('/capability')).forEach((r) => r.flush({present: true}));
        http.match((r) => r.url === BASE).forEach((r) => r.flush([]));
        fixture.detectChanges();
        await fixture.whenStable();
    }

    async function open(body: ConversationView = conversation()): Promise<void> {
        await TestBed.inject(Router).navigateByUrl('/?chat=9');
        http.expectOne(`${BASE}/9`).flush(body);
        await settle();
    }

    async function push(...events: ChatEvent[]): Promise<void> {
        events.forEach((event) => server.next(event));
        await settle();
    }

    /** Every answer glyph's frame, in thread order. */
    function frames(): (string | null)[] {
        const marks = (fixture.nativeElement as HTMLElement).querySelectorAll('.lg-chat-glyph lg-living-mark');
        return [...marks].map((mark) => mark.getAttribute('data-frame'));
    }

    async function ask(): Promise<void> {
        await open();
        dispatch.asked('Which offers asked for Kafka?');
        await settle();
        await push({event: 'turn', data: {turnId: 31}});
    }

    it('is working over the tool steps, speaking over the chunks, and at rest once done', async () => {
        await ask();

        await push({event: 'step', data: {ordinal: 1, tool: 'searchOffers', label: 'Searched', state: 'RUNNING', count: null, durationMs: null}});
        expect(frames()).toEqual(['working']);
        await push({event: 'step', data: {ordinal: 1, tool: 'searchOffers', label: 'Searched', state: 'DONE', count: 14, durationMs: 412}});
        await push({event: 'step', data: {ordinal: 2, tool: 'semanticSearch', label: 'Read', state: 'RUNNING', count: null, durationMs: null}});
        expect(frames()).toEqual(['working']);
        await push({event: 'step', data: {ordinal: 2, tool: 'semanticSearch', label: 'Read', state: 'DONE', count: 27, durationMs: 690}});

        for (let i = 0; i < 20; i++) {
            await push(text(i));
            expect(frames()).toEqual(['speaking']);
        }

        await push({event: 'sources', data: {sources: []}}, {event: 'done', data: {state: 'DONE'}});
        server.complete();
        await settle();
        expect(frames()).toEqual(['rest']);
    });

    it('halts when the stream fails after two chunks', async () => {
        await ask();
        await push(text(0), text(1));
        expect(frames()).toEqual(['speaking']);

        server.error(new Error('cut'));
        await settle();
        expect(frames()).toEqual(['halted']);
    });

    it('halts when the server ends the turn with an error after two chunks', async () => {
        await ask();
        await push(text(0), text(1), {event: 'error', data: {reason: 'MODEL', message: 'The model broke off.'}});
        server.complete();
        await settle();
        expect(frames()).toEqual(['halted']);
    });

    it('halts when the turn is stopped after three chunks', async () => {
        await ask();
        await push(text(0), text(1), text(2));
        expect(frames()).toEqual(['speaking']);

        dispatch.stopRequested();
        await settle();
        expect(api.stop).toHaveBeenCalledWith(9, 31);
        await push({event: 'done', data: {state: 'STOPPED'}});
        server.complete();
        await settle();
        expect(frames()).toEqual(['halted']);
    });

    it('gives each turn loaded from the server the frame of its stored state', async () => {
        await open(conversation([stored(1, 'DONE'), stored(2, 'INCOMPLETE'), stored(3, 'STOPPED')]));

        expect(frames()).toEqual(['rest', 'halted', 'halted']);
    });
});

/**
 * The empty chat asks the server what the data suggests (ISC-456): for the open conversation, or
 * for the pins a new one is asked under. Skeleton rows hold the place while it answers, at most
 * four sentences are shown, and one on screen is never swapped out under the reader's finger.
 */
describe('ChatPanel suggestions (ISC-456)', () => {
    const SUGGESTIONS = '/api/v1/chat/suggestions';
    const FIVE = [
        {trigger: 'NEW_THIS_WEEK', text: 'What came in this week?', count: 12},
        {trigger: 'DEADLINE_SOON', text: 'Which offers close before Friday?', count: 3},
        {trigger: 'NO_ANSWER', text: 'Which applications have had no answer for two weeks?', count: 2},
        {trigger: 'TAG_RISING', text: 'Why is Kotlin rising?', count: 5},
        {trigger: 'SHORTLIST', text: 'What stands on the shortlist?', count: 9},
    ];
    let fixture: ComponentFixture<ChatPanel>;
    let http: HttpTestingController;
    let api: ChatApi;

    beforeEach(async () => {
        TestBed.configureTestingModule({
            providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
        });
        http = TestBed.inject(HttpTestingController);
        api = TestBed.inject(ChatApi);
        vi.spyOn(api, 'ask').mockReturnValue(new Subject<ChatEvent>());
        fixture = TestBed.createComponent(ChatPanel);
        document.body.appendChild(fixture.nativeElement);
        await settle();
    });

    afterEach(() => (fixture.nativeElement as HTMLElement).remove());

    async function settle(): Promise<void> {
        http.match((r) => r.url.endsWith('/capability')).forEach((r) => r.flush({present: true}));
        http.match((r) => r.url === BASE).forEach((r) => r.flush([]));
        fixture.detectChanges();
        await fixture.whenStable();
    }

    const root = () => fixture.nativeElement as HTMLElement;
    const buttons = () => [...root().querySelectorAll<HTMLButtonElement>('button.lg-chat-suggestion')];
    const texts = () => buttons().map((b) => b.textContent?.trim());
    const skeletons = () => root().querySelectorAll('.lg-chat-suggestion-skeleton');

    async function openEmpty(): Promise<void> {
        await TestBed.inject(Router).navigateByUrl('/?chat=9');
        http.expectOne(`${BASE}/9`).flush(conversation());
        await settle();
    }

    it('asks for the open conversation, and holds skeleton rows of the final height while it answers', async () => {
        await openEmpty();

        const request = http.expectOne((r) => r.url === SUGGESTIONS);
        expect(request.request.params.get('conversation')).toBe('9');
        expect(buttons()).toHaveLength(0);
        expect(skeletons().length).toBeGreaterThan(0);
        expect(skeletons().length).toBeLessThanOrEqual(4);
        // The row class carries the height, so the sentences land where the skeletons stood.
        skeletons().forEach((row) => expect(row.classList).toContain('lg-chat-suggestion-row'));
        request.flush(FIVE.slice(0, 2));
        await settle();

        expect(skeletons()).toHaveLength(0);
        expect(texts()).toEqual([FIVE[0].text, FIVE[1].text]);
        buttons().forEach((button) => expect(button.classList).toContain('lg-chat-suggestion-row'));
    });

    it('shows at most four of what the server answers, and none of the old fixed sentences', async () => {
        await openEmpty();
        http.expectOne((r) => r.url === SUGGESTIONS).flush(FIVE);
        await settle();

        expect(texts()).toEqual(FIVE.slice(0, 4).map((s) => s.text));
        expect(root().textContent).not.toContain('chat.suggest.spring');
    });

    it('asks the sentence as it reads when one is clicked', async () => {
        await openEmpty();
        http.expectOne((r) => r.url === SUGGESTIONS).flush(FIVE);
        await settle();

        buttons()[1].click();
        await settle();

        expect(api.ask).toHaveBeenCalledWith(9, FIVE[1].text);
    });

    it('never swaps a sentence on screen: the same conversation again asks nothing and shows the same', async () => {
        await openEmpty();
        http.expectOne((r) => r.url === SUGGESTIONS).flush(FIVE.slice(0, 3));
        await settle();
        const before = texts();

        TestBed.runInInjectionContext(() => injectDispatch(chatEvents)).conversationLoaded(conversation());
        await settle();

        http.expectNone((r) => r.url === SUGGESTIONS);
        expect(texts()).toEqual(before);
    });

    it('asks for the pinned context of a new conversation', async () => {
        await TestBed.inject(Router).navigateByUrl('/?chat=new&chatCtx=o:12');
        await settle();

        const request = http.expectOne((r) => r.url === SUGGESTIONS);
        expect(request.request.params.get('context')).toBe('o:12');
        expect(request.request.params.has('conversation')).toBe(false);
    });

    it('shows no suggestion when the server has none, and no skeleton left behind', async () => {
        await openEmpty();
        http.expectOne((r) => r.url === SUGGESTIONS).flush([]);
        await settle();

        expect(buttons()).toHaveLength(0);
        expect(skeletons()).toHaveLength(0);
    });
});
