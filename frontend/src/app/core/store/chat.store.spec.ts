import {Component} from '@angular/core';
import {HttpErrorResponse, provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {TestBed} from '@angular/core/testing';
import {provideRouter, Router} from '@angular/router';
import {injectDispatch} from '@ngrx/signals/events';
import {Subject, throwError} from 'rxjs';
import {ChatApi} from '@core/api/chat.api';
import {RECORDED_TURN, replayTurn} from '@core/api/chat-stub';
import {ChatEvent, ConversationView} from '@core/model/chat';
import {UnsavedWork} from '@core/unsaved/unsaved-work';
import {chatEvents} from './chat.events';
import {ChatStore} from './chat.store';

@Component({template: ''})
class Blank {}

const BASE = '/api/v1/chat/conversations';

function conversation(id: number, overrides: Partial<ConversationView> = {}): ConversationView {
    return {id, title: `Conversation ${id}`, pinnedOfferId: null, turns: [], updatedAt: '2026-09-27T08:00:00Z', ...overrides};
}

describe('ChatStore', () => {
    let store: InstanceType<typeof ChatStore>;
    let http: HttpTestingController;
    let router: Router;
    let api: ChatApi;
    let dispatch: ReturnType<typeof injectDispatch<typeof chatEvents>>;

    beforeEach(() => {
        TestBed.configureTestingModule({
            providers: [
                provideHttpClient(),
                provideHttpClientTesting(),
                provideRouter([
                    {path: 'offers', component: Blank},
                    {path: 'pipeline', component: Blank},
                ]),
            ],
        });
        store = TestBed.inject(ChatStore);
        http = TestBed.inject(HttpTestingController);
        router = TestBed.inject(Router);
        api = TestBed.inject(ChatApi);
        dispatch = TestBed.runInInjectionContext(() => injectDispatch(chatEvents));
    });

    afterEach(() => http.verify());

    async function open(id: number, body: ConversationView = conversation(id)): Promise<void> {
        await router.navigateByUrl(`/offers?chat=${id}`);
        http.expectOne({method: 'GET', url: `${BASE}/${id}`}).flush(body);
    }

    it('deletes more than the server takes in one request in batches of 500, and drops every row that went', async () => {
        // The server caps a bulk delete at 500 ids; "Select all" over a longer list used to get a 400
        // and delete nothing at all.
        await router.navigateByUrl('/offers');
        const ids = Array.from({length: 1200}, (_, i) => i + 1);
        dispatch.bulkDeleteRequested(ids);
        for (const size of [500, 500, 200]) {
            const request = http.expectOne({method: 'POST', url: `${BASE}/bulk-delete`});
            const sent = (request.request.body as {ids: number[]}).ids;
            expect(sent).toHaveLength(size);
            request.flush({deleted: sent});
        }
        expect(store.error()).toBeNull();
    });

    it('keeps the answer to the latest status request when an older one comes back last', () => {
        const first = new Subject<{model: string; callsUsed: number; callsLimit: number; toolRounds: number}>();
        const second = new Subject<{model: string; callsUsed: number; callsLimit: number; toolRounds: number}>();
        vi.spyOn(api, 'status').mockReturnValueOnce(first).mockReturnValueOnce(second);
        dispatch.statusRequested();
        dispatch.statusRequested();
        second.next({model: 'new', callsUsed: 2, callsLimit: 200, toolRounds: 6});
        first.next({model: 'old', callsUsed: 1, callsLimit: 200, toolRounds: 6});
        expect(store.status()?.model).toBe('new');
        expect(store.statusFailed()).toBe(false);
    });

    it('is closed while the URL holds no chat', async () => {
        await router.navigateByUrl('/offers');

        expect(store.view()).toBe('closed');
        expect(store.openId()).toBeNull();
    });

    it('opens the conversation `?chat=<id>` names', async () => {
        await open(4);

        expect(store.view()).toBe('conversation');
        expect(store.openId()).toBe(4);
        expect(store.conversation()?.title).toBe('Conversation 4');
    });

    it('lands an id the server does not know in the missing state, not in an error', async () => {
        await router.navigateByUrl('/offers?chat=99');
        http.expectOne(`${BASE}/99`).flush('gone', {status: 404, statusText: 'Not Found'});

        expect(store.view()).toBe('missing');
        expect(store.error()).toBeNull();
    });

    it('takes a value that is no id for a missing conversation too, without asking the server', async () => {
        await router.navigateByUrl('/offers?chat=abc');

        expect(store.view()).toBe('missing');
    });

    it('resolves `?chat=list` to the list, newest first as the server sends it', async () => {
        await router.navigateByUrl('/offers?chat=list');
        http.expectOne({method: 'GET', url: BASE}).flush([
            {id: 3, title: 'c', updatedAt: '2026-09-27T09:00:00Z'},
            {id: 1, title: 'a', updatedAt: '2026-09-26T09:00:00Z'},
        ]);

        expect(store.view()).toBe('list');
        expect(store.conversations().map((c) => c.id)).toEqual([3, 1]);
    });

    it('resolves `?chat=new` to an empty conversation without creating one yet', async () => {
        await router.navigateByUrl('/offers?chat=new');

        expect(store.view()).toBe('new');
        expect(store.conversation()).toBeNull();
    });

    it('keeps the last opened id once `?chat` goes away, and reopens it', async () => {
        await open(4);
        // Closed by the chat itself: a link without `?chat` no longer takes it away (ISC-444).
        dispatch.closeRequested();
        await vi.waitFor(() => expect(router.url).toBe('/offers'));
        await router.navigateByUrl('/pipeline');

        expect(store.view()).toBe('closed');
        expect(store.lastId()).toBe(4);

        dispatch.reopenRequested();
        await vi.waitFor(() => expect(router.url).toBe('/pipeline?chat=4'));
        // Reopened from memory: the conversation is still held, so nothing is fetched again.
        expect(store.view()).toBe('conversation');
    });

    it('writes every intent to the URL and nothing else, on the screen it was made on', async () => {
        await router.navigateByUrl('/pipeline?q=x');

        dispatch.openRequested(7);
        await vi.waitFor(() => expect(router.url).toBe('/pipeline?q=x&chat=7'));
        http.expectOne(`${BASE}/7`).flush(conversation(7));

        dispatch.listRequested();
        await vi.waitFor(() => expect(router.url).toBe('/pipeline?q=x&chat=list'));
        http.expectOne(BASE).flush([]);

        dispatch.closeRequested();
        await vi.waitFor(() => expect(router.url).toBe('/pipeline?q=x'));
    });

    it('accumulates a streamed turn: text by delta, steps by ordinal, the sources, the final state', async () => {
        await open(4);
        const ask = vi.spyOn(api, 'ask').mockReturnValue(replayTurn(RECORDED_TURN));

        dispatch.asked('What came in?');
        expect(store.streaming()).toBe(true);
        await vi.waitFor(() => expect(store.live()?.state).toBe('DONE'));

        expect(ask).toHaveBeenCalledWith(4, 'What came in?');
        const live = store.live()!;
        expect(live.turnId).toBe(1);
        expect(live.question).toBe('What came in?');
        expect(live.answer).toBe(
            'Fourteen remote Spring offers came in last month. The strongest is a Kotlin backend role [1](cite:offer/2291), ' +
                'then a payments platform [2](cite:offer/2304). One id from the newsletter, ⟨unverified:4471⟩, came back from no search.',
        );
        expect(live.steps.map((s) => [s.ordinal, s.state, s.count])).toEqual([
            [1, 'DONE', 14],
            [2, 'DONE', 27],
        ]);
        expect(live.sources.map((s) => (s.kind === 'STATISTICS' ? null : s.id))).toEqual([2291, 2304]);
        expect(store.streaming()).toBe(false);
        expect(store.liveTurn()).toBe(live);

        // The next question folds the finished turn into the conversation's stored ones.
        vi.spyOn(api, 'ask').mockReturnValue(replayTurn([{event: 'turn', data: {turnId: 2}}, {event: 'done', data: {state: 'DONE'}}]));
        dispatch.asked('And the week before?');
        await vi.waitFor(() => expect(store.live()?.state).toBe('DONE'));
        expect(store.turns().map((t) => [t.id, t.state, t.question])).toEqual([[1, 'DONE', 'What came in?']]);
        expect(store.liveTurn()?.turnId).toBe(2);
    });

    it('creates the conversation on the first question under `?chat=new`, pinned, and swaps the URL to its id', async () => {
        await router.navigateByUrl('/offers');
        dispatch.newRequested({pinnedOfferId: 2291});
        await vi.waitFor(() => expect(router.url).toBe('/offers?chat=new&chatCtx=o:2291'));
        vi.spyOn(api, 'ask').mockReturnValue(replayTurn(RECORDED_TURN));

        dispatch.asked('Is this one worth it?');
        const create = http.expectOne({method: 'POST', url: BASE});
        expect(create.request.body).toEqual({context: [{kind: 'OFFER', offerId: 2291}]});
        create.flush(conversation(12, {pinnedOfferId: 2291}));

        await vi.waitFor(() => expect(router.url).toBe('/offers?chat=12&chatCtx=o:2291'));
        await vi.waitFor(() => expect(store.live()?.state).toBe('DONE'));
        // The id it moved to is the conversation already held: no second read.
        expect(store.openId()).toBe(12);
        expect(store.conversation()?.pinnedOfferId).toBe(2291);
    });

    it('keeps a partial answer marked incomplete when the model fails mid-stream', async () => {
        await open(4);
        vi.spyOn(api, 'ask').mockReturnValue(
            replayTurn([
                {event: 'turn', data: {turnId: 5}},
                {event: 'text', data: {delta: 'Half an '}},
                {event: 'text', data: {delta: 'answer'}},
                {event: 'error', data: {reason: 'MODEL', message: 'model went away'}},
            ]),
        );

        dispatch.asked('q');
        await vi.waitFor(() => expect(store.live()?.state).toBe('INCOMPLETE'));

        expect(store.live()?.answer).toBe('Half an answer');
        expect(store.live()?.error).toEqual({reason: 'MODEL', message: 'model went away'});
    });

    it('treats a stream that closes without `done` or `error` as cut', async () => {
        await open(4);
        vi.spyOn(api, 'ask').mockReturnValue(replayTurn([{event: 'turn', data: {turnId: 5}}, {event: 'text', data: {delta: 'Half'}}]));

        dispatch.asked('q');
        await vi.waitFor(() => expect(store.live()?.state).toBe('INCOMPLETE'));

        expect(store.live()?.answer).toBe('Half');
        expect(store.live()?.failure).toBe('error.chatStream');
    });

    it('ignores a second question while a turn streams', async () => {
        await open(4);
        const events = new Subject<ChatEvent>();
        const ask = vi.spyOn(api, 'ask').mockReturnValue(events);

        dispatch.asked('first');
        dispatch.asked('second');

        expect(ask).toHaveBeenCalledTimes(1);
        expect(store.live()?.question).toBe('first');
        events.complete();
    });

    it('takes a question sent after `done` but before the stream closed, and streams it exactly once', async () => {
        await open(4);
        const first = new Subject<ChatEvent>();
        const second = new Subject<ChatEvent>();
        const ask = vi.spyOn(api, 'ask').mockReturnValueOnce(first).mockReturnValueOnce(second);

        dispatch.asked('first');
        first.next({event: 'turn', data: {turnId: 5}});
        first.next({event: 'done', data: {state: 'DONE'}});
        dispatch.asked('second');
        first.complete();

        expect(ask.mock.calls).toEqual([
            [4, 'first'],
            [4, 'second'],
        ]);
        second.next({event: 'turn', data: {turnId: 6}});
        second.next({event: 'text', data: {delta: 'Streams'}});
        expect(store.live()?.question).toBe('second');
        expect(store.live()?.answer).toBe('Streams');
        expect(store.live()?.state).toBe('STREAMING');
        second.next({event: 'done', data: {state: 'DONE'}});
        second.complete();
        expect(store.live()?.state).toBe('DONE');
        expect(store.turns().map((t) => t.id)).toEqual([5]);
    });

    it('names a spent budget and spent rounds by their own catalog text', async () => {
        await open(4);
        const reasons = [
            ['BUDGET', 'error.chatBudget'],
            ['ROUNDS', 'error.chatRounds'],
        ] as const;
        for (const [reason, key] of reasons) {
            vi.spyOn(api, 'ask').mockReturnValue(
                replayTurn([{event: 'turn', data: {turnId: 5}}, {event: 'error', data: {reason, message: 'server sentence'}}]),
            );
            dispatch.asked('q');
            await vi.waitFor(() => expect(store.live()?.state).toBe('INCOMPLETE'));
            expect(store.live()?.failure).toBe(key);
            expect(store.live()?.failureParams).toBeNull();
        }
    });

    // Fix 5F-8: the server's sentence says which model failure it was — a timeout, a conversation
    // deleted meanwhile, a turn that could not be stored — and a generic "cut" hid all of them.
    it("names a model failure by the server's sentence, and as a cut answer when it sent none", async () => {
        await open(4);
        vi.spyOn(api, 'ask').mockReturnValue(
            replayTurn([{event: 'turn', data: {turnId: 5}}, {event: 'error', data: {reason: 'MODEL', message: 'The model did not answer in time.'}}]),
        );
        dispatch.asked('q');
        await vi.waitFor(() => expect(store.live()?.state).toBe('INCOMPLETE'));
        expect(store.live()?.failure).toBe('error.chatModel');
        expect(store.live()?.failureParams).toEqual({message: 'The model did not answer in time.'});

        vi.spyOn(api, 'ask').mockReturnValue(
            replayTurn([{event: 'turn', data: {turnId: 6}}, {event: 'error', data: {reason: 'MODEL', message: '  '}}]),
        );
        dispatch.asked('q');
        await vi.waitFor(() => expect(store.live()?.turnId).toBe(6));
        await vi.waitFor(() => expect(store.live()?.state).toBe('INCOMPLETE'));
        expect(store.live()?.failure).toBe('error.chatStream');
        expect(store.live()?.failureParams).toBeNull();
    });

    it("shows the server's sentence when it refuses the turn outright", async () => {
        await open(4);
        vi.spyOn(api, 'ask').mockReturnValue(
            throwError(() => new HttpErrorResponse({status: 409, error: 'no chat model is configured'})),
        );

        dispatch.asked('q');

        await vi.waitFor(() => expect(store.live()?.state).toBe('INCOMPLETE'));
        expect(store.live()?.failure).toBe('no chat model is configured');
    });

    it('counts a turn as unsaved work while it streams, and a question the server never stored until it is (spec 024, code review 5, finding 4)', async () => {
        // The composer is emptied on send, so a question waiting on a renewal exists only in the
        // live turn: a redirect would take it along. Once the server named the turn it is stored.
        await open(4);
        const stream = new Subject<ChatEvent>();
        vi.spyOn(api, 'ask').mockReturnValue(stream);
        const unsaved = TestBed.inject(UnsavedWork);

        dispatch.asked('What came in?');
        expect(store.streaming()).toBe(true);
        expect(unsaved.any()).toBe(true);

        stream.next({event: 'turn', data: {turnId: 7}});
        expect(unsaved.any()).toBe(true);

        stream.next({event: 'done', data: {state: 'DONE'}});
        stream.complete();
        expect(unsaved.any()).toBe(false);
    });

    it('a question refused before the server stored it stays unsaved work after the stream ends (code review 5, finding 4)', async () => {
        await open(4);
        const stream = new Subject<ChatEvent>();
        vi.spyOn(api, 'ask').mockReturnValue(stream);
        const unsaved = TestBed.inject(UnsavedWork);

        dispatch.asked('What came in?');
        stream.error(new HttpErrorResponse({status: 401, statusText: 'Session ended'}));

        await vi.waitFor(() => expect(store.streaming()).toBe(false));
        expect(store.live()?.question).toBe('What came in?');
        expect(unsaved.any()).toBe(true);
    });

    it('asks the server to stop the live turn and lets the stream say how it ended', async () => {
        await open(4);
        const events = new Subject<ChatEvent>();
        vi.spyOn(api, 'ask').mockReturnValue(events);
        dispatch.asked('q');
        events.next({event: 'turn', data: {turnId: 5}});
        events.next({event: 'text', data: {delta: 'Part'}});

        dispatch.stopRequested();
        http.expectOne({method: 'POST', url: `${BASE}/4/turns/5/stop`}).flush(null);
        events.next({event: 'done', data: {state: 'STOPPED'}});
        events.complete();

        expect(store.live()?.state).toBe('STOPPED');
        expect(store.live()?.answer).toBe('Part');
    });

    // Fix 5F-3: Stop shows as soon as the turn streams, before the server has named it.
    it('remembers a stop pressed before the turn is named, and sends it once when the `turn` event arrives', async () => {
        await open(4);
        const events = new Subject<ChatEvent>();
        vi.spyOn(api, 'ask').mockReturnValue(events);
        dispatch.asked('q');

        dispatch.stopRequested();
        http.expectNone((r) => r.url.endsWith('/stop'));

        events.next({event: 'turn', data: {turnId: 5}});
        http.expectOne({method: 'POST', url: `${BASE}/4/turns/5/stop`}).flush(null);
        events.next({event: 'text', data: {delta: 'Part'}});
        events.next({event: 'done', data: {state: 'STOPPED'}});
        events.complete();

        http.expectNone((r) => r.url.endsWith('/stop'));
        expect(store.live()?.state).toBe('STOPPED');
    });

    it('sends a stop pressed while the first question under `?chat=new` creates its conversation, to the created one', async () => {
        await router.navigateByUrl('/offers');
        dispatch.newRequested({pinnedOfferId: null});
        await vi.waitFor(() => expect(router.url).toBe('/offers?chat=new'));
        const events = new Subject<ChatEvent>();
        vi.spyOn(api, 'ask').mockReturnValue(events);

        dispatch.asked('q');
        dispatch.stopRequested();
        http.expectOne({method: 'POST', url: BASE}).flush(conversation(12));
        http.expectNone((r) => r.url.endsWith('/stop'));

        events.next({event: 'turn', data: {turnId: 7}});
        http.expectOne({method: 'POST', url: `${BASE}/12/turns/7/stop`}).flush(null);
        events.next({event: 'done', data: {state: 'STOPPED'}});
        events.complete();
        await vi.waitFor(() => expect(store.live()?.state).toBe('STOPPED'));
    });

    it('forgets a pending stop with its turn: the next turn is not stopped', async () => {
        await open(4);
        const first = new Subject<ChatEvent>();
        vi.spyOn(api, 'ask').mockReturnValue(first);
        dispatch.asked('q');
        dispatch.stopRequested();
        // The stream breaks before the server named the turn: nothing to stop, and nothing left pending.
        first.error(new Error('cut'));
        await vi.waitFor(() => expect(store.streaming()).toBe(false));

        const second = new Subject<ChatEvent>();
        vi.spyOn(api, 'ask').mockReturnValue(second);
        dispatch.asked('again');
        second.next({event: 'turn', data: {turnId: 9}});
        http.expectNone((r) => r.url.endsWith('/stop'));
        second.next({event: 'done', data: {state: 'DONE'}});
        second.complete();
    });

    // Fix 2F-7: a stop names the open conversation's turn, never one streaming behind another.
    it('does not stop a turn that streams in a conversation other than the open one', async () => {
        await open(4);
        const events = new Subject<ChatEvent>();
        vi.spyOn(api, 'ask').mockReturnValue(events);
        dispatch.asked('q');
        events.next({event: 'turn', data: {turnId: 5}});
        await open(6);

        dispatch.stopRequested();
        http.expectNone((r) => r.url.endsWith('/stop'));
        expect(store.streaming()).toBe(true);
        events.complete();
    });

    // Fix 4F-1: the create in flight belongs to `?chat=new`; a conversation moved to meanwhile keeps its thread.
    it('keeps the conversation moved to while a first question creates its own, and lists the created one', async () => {
        await router.navigateByUrl('/offers?chat=new');
        const events = new Subject<ChatEvent>();
        const ask = vi.spyOn(api, 'ask').mockReturnValue(events);

        dispatch.asked('First question');
        const create = http.expectOne({method: 'POST', url: BASE});
        const stored = {id: 80, question: 'B asked', answer: 'B answer', state: 'DONE' as const, steps: [], sources: [], replacesTurnId: null, model: null, createdAt: '2026-09-27T08:00:00Z'};
        await open(8, conversation(8, {turns: [stored]}));
        create.flush(conversation(12, {title: ''}));
        await new Promise((resolve) => setTimeout(resolve, 0));

        expect(router.url).toBe('/offers?chat=8');
        expect(store.openId()).toBe(8);
        expect(store.conversation()?.id).toBe(8);
        expect(store.turns().map((t) => t.id)).toEqual([80]);
        expect(store.liveTurn()).toBeNull();
        expect(ask).toHaveBeenCalledWith(12, 'First question');
        expect(store.live()?.conversationId).toBe(12);
        expect(store.conversations().map((c) => c.id)).toContain(12);
        events.complete();
    });

    // Fix 4F-2: a first question whose conversation was never created follows nobody into another conversation.
    it('shows a first question whose create failed under `?chat=new` only, and drops it on opening a real conversation', async () => {
        await router.navigateByUrl('/offers?chat=new');

        dispatch.asked('Orphan question');
        http.expectOne({method: 'POST', url: BASE}).flush('down', {status: 500, statusText: 'Server Error'});
        expect(store.liveTurn()?.question).toBe('Orphan question');
        expect(store.liveTurn()?.state).toBe('INCOMPLETE');

        await open(8);

        expect(store.liveTurn()).toBeNull();
        expect(store.live()).toBeNull();
        expect(store.takesQuestion()).toBe(true);
    });

    // Fix 4F-3: the server names a conversation after its first question in `startTurn`; the drawer names it the same way at once.
    it('names a created conversation after its first question, shortened the way the server does', async () => {
        await router.navigateByUrl('/offers?chat=new');
        vi.spyOn(api, 'ask').mockReturnValue(replayTurn(RECORDED_TURN));

        dispatch.asked('  Which   offers\nasked for Kafka?  ');
        http.expectOne({method: 'POST', url: BASE}).flush(conversation(12, {title: ''}));
        await vi.waitFor(() => expect(store.live()?.state).toBe('DONE'));

        expect(store.conversation()?.title).toBe('Which offers asked for Kafka?');
        expect(store.conversations().find((c) => c.id === 12)?.title).toBe('Which offers asked for Kafka?');

        await router.navigateByUrl('/offers?chat=new');
        dispatch.asked('word '.repeat(30));
        http.expectOne({method: 'POST', url: BASE}).flush(conversation(13, {title: ''}));
        await vi.waitFor(() => expect(store.openId()).toBe(13));

        expect(store.conversation()?.title).toBe(`${Array(16).fill('word').join(' ')}…`);
    });

    // Fix 2F-6: under a missing or a still-loading conversation a question would create a new
    // conversation the URL never follows.
    it('takes no question in the missing view', async () => {
        await router.navigateByUrl('/offers?chat=abc');
        const create = vi.spyOn(api, 'create');

        dispatch.asked('q');

        expect(create).not.toHaveBeenCalled();
        expect(store.live()).toBeNull();
    });

    it('takes no question while the conversation loads, and takes one once it has', async () => {
        await router.navigateByUrl('/offers?chat=4');
        const create = vi.spyOn(api, 'create');
        const ask = vi.spyOn(api, 'ask').mockReturnValue(new Subject<ChatEvent>());

        dispatch.asked('q');
        expect(create).not.toHaveBeenCalled();
        expect(ask).not.toHaveBeenCalled();
        expect(store.live()).toBeNull();

        http.expectOne(`${BASE}/4`).flush(conversation(4));
        dispatch.asked('q');
        expect(ask).toHaveBeenCalledWith(4, 'q');
    });

    it('regenerates a stored turn as a new one that replaces it', async () => {
        await open(4, conversation(4, {
            turns: [{id: 3, question: 'Again?', answer: 'old', state: 'DONE', steps: [], sources: [], replacesTurnId: null, model: null, createdAt: '2026-09-27T08:00:00Z'}],
        }));
        const regenerate = vi.spyOn(api, 'regenerate').mockReturnValue(replayTurn([{event: 'turn', data: {turnId: 6}}, {event: 'done', data: {state: 'DONE'}}]));

        dispatch.regenerateRequested(3);
        await vi.waitFor(() => expect(store.live()?.state).toBe('DONE'));

        expect(regenerate).toHaveBeenCalledWith(4, 3);
        expect(store.live()?.question).toBe('Again?');
        expect(store.live()?.replacesTurnId).toBe(3);
    });

    it('deletes one conversation and lands on a new one when it was the open one (ISC-448)', async () => {
        await open(4);
        dispatch.listLoaded([
            {id: 4, title: 'd', updatedAt: '2026-09-27T09:00:00Z', lastActivityAt: '2026-09-27T09:00:00Z'},
            {id: 2, title: 'b', updatedAt: '2026-09-20T09:00:00Z', lastActivityAt: '2026-09-20T09:00:00Z'},
        ]);

        dispatch.deleteRequested(4);
        http.expectOne({method: 'DELETE', url: `${BASE}/4`}).flush(null);

        await vi.waitFor(() => expect(router.url).toBe('/offers?chat=new'));
        expect(store.conversations().map((c) => c.id)).toEqual([2]);
        expect(store.lastId()).toBeNull();
    });

    // Fix 3F-4: deleting the conversation whose turn streamed cleared the turn but left the stream open,
    // and the next question was refused without a word.
    it('stops the turn of a conversation deleted while it streams, and takes the next question once the stream ends', async () => {
        await open(4);
        const events = new Subject<ChatEvent>();
        vi.spyOn(api, 'ask').mockReturnValue(events);
        dispatch.asked('q');
        events.next({event: 'turn', data: {turnId: 5}});
        events.next({event: 'text', data: {delta: 'Part'}});

        dispatch.deleteRequested(4);
        http.expectOne({method: 'POST', url: `${BASE}/4/turns/5/stop`}).flush(null);
        http.expectOne({method: 'DELETE', url: `${BASE}/4`}).flush(null);
        await vi.waitFor(() => expect(router.url).toBe('/offers?chat=new'));
        expect(store.live()).toBeNull();

        events.next({event: 'done', data: {state: 'STOPPED'}});
        events.complete();
        expect(store.streamOpen()).toBe(false);

        await router.navigateByUrl('/offers?chat=new');
        vi.spyOn(api, 'ask').mockReturnValue(replayTurn([{event: 'turn', data: {turnId: 7}}, {event: 'done', data: {state: 'DONE'}}]));
        dispatch.asked('next');
        expect(store.live()?.question).toBe('next');
        http.expectOne({method: 'POST', url: BASE}).flush(conversation(6));
        await vi.waitFor(() => expect(store.live()?.state).toBe('DONE'));
    });

    // Fix 3F-5: the server takes 4000 characters; a longer question must not cost a conversation.
    it('creates no conversation and starts no turn for a question longer than the server takes', async () => {
        await router.navigateByUrl('/offers?chat=new');

        dispatch.asked('x'.repeat(4001));

        http.expectNone({method: 'POST', url: BASE});
        expect(store.live()).toBeNull();
        expect(store.streamOpen()).toBe(false);
    });

    it('reads the capability the header draws its button on', () => {
        dispatch.capabilityRequested();
        http.expectOne({method: 'GET', url: '/api/v1/chat/capability'}).flush({present: true});

        expect(store.present()).toBe(true);
    });

    it('takes a capability that cannot be read for an absent chat', () => {
        dispatch.capabilityRequested();
        http.expectOne('/api/v1/chat/capability').flush('down', {status: 503, statusText: 'Unavailable'});

        expect(store.present()).toBe(false);
    });
});
