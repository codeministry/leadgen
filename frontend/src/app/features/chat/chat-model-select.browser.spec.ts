import {Component} from '@angular/core';
import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {ComponentFixture, TestBed} from '@angular/core/testing';
import {provideRouter, Router} from '@angular/router';
import {page} from 'vitest/browser';
import {TranslocoTestingModule} from '@jsverse/transloco';
import {provideTranslocoMessageformat} from '@jsverse/transloco-messageformat';
import {of, Subject} from 'rxjs';
import {ChatApi} from '@core/api/chat.api';
import {ChatEvent, ChatModels, ChatStatus, ConversationView, TurnView} from '@core/model/chat';
import {App} from '../../app';
import en from '../../../../public/i18n/en.json';

@Component({template: '<h1>screen</h1>'})
class Screen {}

/** Two connections: the primary billed with two models, the second unbilled with one, its day's calls spent. */
const CATALOG: ChatModels = {
    connections: [
        {
            name: 'PRIMARY',
            kind: 'local',
            billed: true,
            reachable: true,
            reason: null,
            models: [
                {name: 'model-a', defaultModel: true},
                {name: 'model-b', defaultModel: false},
            ],
        },
        {name: 'SECOND', kind: 'local', billed: false, reachable: true, reason: null, models: [{name: 'model-c', defaultModel: false}]},
    ],
};

const SPENT: ChatStatus = {model: 'model-a', callsUsed: 30, callsLimit: 200, toolRounds: 6, secondCalls: 20, secondLimit: 20};

/**
 * The model select beside the composer (ISC-511): the catalog grouped by connection, the billed
 * marker on billed options, a spent or unreachable connection's options disabled with the reason
 * in words, the pick sent with the next question, and the model named under the answer it wrote.
 *
 * <p>In a real browser, because a native `<select>`'s groups, its disabled options and its
 * accessible description are the browser's own, and the full app's drawer is what opens it.
 */
describe('the chat model select (ISC-511)', () => {
    let fixture: ComponentFixture<App>;
    let http: HttpTestingController;
    let server: Subject<ChatEvent>;
    let ask: ReturnType<typeof vi.spyOn>;

    async function settle(): Promise<void> {
        for (let i = 0; i < 4; i++) {
            await new Promise((resolve) => setTimeout(resolve, 0));
            fixture.detectChanges();
        }
    }

    async function open(catalog: ChatModels, status: ChatStatus | null): Promise<void> {
        TestBed.configureTestingModule({
            providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([{path: 'dashboard', component: Screen}])],
        });
        http = TestBed.inject(HttpTestingController);
        server = new Subject<ChatEvent>();
        ask = vi.spyOn(TestBed.inject(ChatApi), 'ask').mockReturnValue(server);
        await page.viewport(1280, 800);
        fixture = TestBed.createComponent(App);
        document.body.appendChild(fixture.nativeElement);
        await TestBed.inject(Router).navigateByUrl('/dashboard?chat=9');
        await settle();
        http.match((r) => r.url === '/api/v1/chat/capability').forEach((r) => r.flush({present: true}));
        await settle();
        http.expectOne('/api/v1/chat/conversations/9').flush({id: 9, title: 'Rates', pinnedOfferId: null, turns: [], updatedAt: '2026-10-12T08:00:00Z'});
        await settle();
        http.expectOne('/api/v1/chat/models').flush(catalog);
        await settle();
        if (status !== null) {
            http.expectOne('/api/v1/chat/status').flush(status);
            await settle();
        }
    }

    afterEach(() => {
        server.complete();
        (fixture.nativeElement as HTMLElement).remove();
    });

    const select = () => document.querySelector<HTMLSelectElement>('select.lg-chat-model')!;
    const groups = () => Array.from(select().querySelectorAll('optgroup'));
    const options = (group: HTMLOptGroupElement) => Array.from(group.querySelectorAll('option'));

    async function askQuestion(question: string): Promise<void> {
        const input = document.querySelector<HTMLTextAreaElement>('.lg-chat-input')!;
        input.value = question;
        input.dispatchEvent(new Event('input'));
        input.dispatchEvent(new KeyboardEvent('keydown', {key: 'Enter', bubbles: true, cancelable: true}));
        await settle();
    }

    it('groups the models by connection, marks the billed ones and disables a spent connection with its reason', async () => {
        await open(CATALOG, SPENT);

        expect(select(), 'the select beside the composer').not.toBeNull();
        const label = document.querySelector(`label[for="${select().id}"]`);
        expect(label?.textContent?.trim()).toBe('Model');

        const [primary, second] = groups();
        expect(groups()).toHaveLength(2);
        expect(primary.label).toBe('Primary connection');
        expect(options(primary).map((o) => o.textContent!.trim())).toEqual(['model-a · billed', 'model-b · billed']);
        expect(options(primary).every((o) => !o.disabled)).toBe(true);

        expect(second.label).toBe('Second connection · No calls left today');
        expect(options(second).map((o) => o.textContent!.trim())).toEqual(['model-c']);
        expect(options(second).every((o) => o.disabled), 'the spent connection cannot be picked').toBe(true);

        // The reason in words beside the select, and the select described by it.
        const note = document.getElementById(select().getAttribute('aria-describedby')!)!;
        expect(note.textContent!.trim()).toBe('Second connection: No calls left today');

        // A new conversation's pick is the default on the primary connection.
        expect(select().value).toBe('PRIMARY:model-a');
    });

    it('sends the pick with the next question, and names the model and the marker under the answer', async () => {
        await open(CATALOG, SPENT);

        select().value = 'PRIMARY:model-b';
        select().dispatchEvent(new Event('change'));
        await settle();
        await askQuestion('What do Kafka offers pay?');

        expect(ask).toHaveBeenCalledWith(9, 'What do Kafka offers pay?', {connection: 'PRIMARY', model: 'model-b'});
        expect(select().value, 'the pick holds for the conversation').toBe('PRIMARY:model-b');

        server.next({event: 'turn', data: {turnId: 31, model: 'model-b', connection: 'PRIMARY'}});
        server.next({event: 'text', data: {delta: 'Between 90 and 110 euros an hour.'}});
        server.next({event: 'sources', data: {sources: []}});
        server.next({event: 'done', data: {state: 'DONE'}});
        await settle();

        const line = document.querySelector('.lg-chat-answer-model')!;
        expect(line, 'the model under the answer').not.toBeNull();
        expect(line.querySelector('.lg-chat-answer-model-name')!.textContent!.trim()).toBe('model-b');
        expect(line.querySelector('.lg-chat-billed')!.textContent!.trim()).toBe('billed');
    });

    it('sends no pick for the default, and shows no marker for an unbilled answer', async () => {
        await open({connections: [{...CATALOG.connections[0], billed: false}, CATALOG.connections[1]]}, SPENT);

        await askQuestion('What came in today?');
        expect(ask.mock.calls[0], 'the default is asked without a pick').toEqual([9, 'What came in today?']);

        server.next({event: 'turn', data: {turnId: 32, model: 'model-a', connection: 'PRIMARY'}});
        server.next({event: 'text', data: {delta: 'Three offers.'}});
        server.next({event: 'done', data: {state: 'DONE'}});
        await settle();

        const line = document.querySelector('.lg-chat-answer-model')!;
        expect(line.querySelector('.lg-chat-answer-model-name')!.textContent!.trim()).toBe('model-a');
        expect(line.querySelector('.lg-chat-billed')).toBeNull();
    });

    it('disables an unreachable connection with the reason it was given', async () => {
        await open(
            {
                connections: [
                    CATALOG.connections[0],
                    {name: 'SECOND', kind: 'hosted', billed: true, reachable: false, reason: 'connection refused', models: []},
                ],
            },
            {...SPENT, secondCalls: 0},
        );

        const [, second] = groups();
        expect(second.label).toBe('Second connection · Not reachable: connection refused');
        expect(options(second)).toHaveLength(1);
        expect(options(second)[0].disabled).toBe(true);
        expect(options(second)[0].textContent!.trim()).toBe('No models');
        const note = document.getElementById(select().getAttribute('aria-describedby')!)!;
        expect(note.textContent!.trim()).toBe('Second connection: Not reachable: connection refused');
    });

    it('draws no select when the catalog offers one model in all', async () => {
        await open({connections: [{...CATALOG.connections[0], models: [{name: 'model-a', defaultModel: true}]}]}, null);

        expect(document.querySelector('select.lg-chat-model')).toBeNull();
        expect(http.match('/api/v1/chat/status'), 'no second connection, no status asked').toHaveLength(0);
    });
});

/** The primary unbilled with the default and one more, the second billed with calls left today. */
const BILLED_SECOND: ChatModels = {
    connections: [
        {
            name: 'PRIMARY',
            kind: 'local',
            billed: false,
            reachable: true,
            reason: null,
            models: [
                {name: 'model-a', defaultModel: true},
                {name: 'model-b', defaultModel: false},
            ],
        },
        {name: 'SECOND', kind: 'hosted', billed: true, reachable: true, reason: null, models: [{name: 'model-c', defaultModel: false}]},
    ],
};

const CALLS_LEFT: ChatStatus = {model: 'model-a', callsUsed: 3, callsLimit: 200, toolRounds: 6, secondCalls: 1, secondLimit: 20};

/** A stored turn as the server sends it back on a reopen; `connection` null is a turn stored before the select. */
function storedTurn(id: number, model: string | null, connection: 'PRIMARY' | 'SECOND' | null): TurnView {
    return {
        id,
        question: `Question ${id}`,
        answer: `Answer ${id}`,
        state: 'DONE',
        steps: [],
        sources: [],
        replacesTurnId: null,
        model,
        connection,
        createdAt: '2026-10-12T08:00:00Z',
        finishedAt: '2026-10-12T08:00:05Z',
    };
}

function conversation(id: number, turns: readonly TurnView[]): ConversationView {
    return {id, title: `Conversation ${id}`, pinnedOfferId: null, turns, updatedAt: '2026-10-12T08:00:00Z'};
}

/** What the stubbed API answers: the catalog, the status, and each stored conversation by id. */
interface StubServer {
    readonly catalog: ChatModels;
    readonly status: ChatStatus | null;
    readonly conversations: Readonly<Record<number, ConversationView>>;
}

/**
 * The whole app booted at one URL against a stubbed API: one booted instance is one page load.
 * A reload, and a second tab, are a fresh instance — a new root injector, so a new store — over
 * the same window and its storage, which is everything a pick could have been carried over in.
 */
class Tab {
    readonly fixture: ComponentFixture<App>;
    readonly http: HttpTestingController;
    readonly ask: ReturnType<typeof vi.spyOn>;
    readonly create: ReturnType<typeof vi.spyOn>;
    readonly servers: Subject<ChatEvent>[] = [];

    private constructor(private readonly server: StubServer) {
        // A page load starts from nothing: a new root injector, so the catalogs the setup file provides are provided again.
        TestBed.resetTestingModule();
        TestBed.configureTestingModule({
            imports: [
                TranslocoTestingModule.forRoot({langs: {en}, translocoConfig: {availableLangs: ['en', 'de'], defaultLang: 'en'}, preloadLangs: true}),
            ],
            providers: [provideTranslocoMessageformat(), provideHttpClient(), provideHttpClientTesting(), provideRouter([{path: 'dashboard', component: Screen}])],
        });
        this.http = TestBed.inject(HttpTestingController);
        const api = TestBed.inject(ChatApi);
        this.ask = vi.spyOn(api, 'ask').mockImplementation(() => {
            const stream = new Subject<ChatEvent>();
            this.servers.push(stream);
            return stream;
        });
        this.create = vi.spyOn(api, 'create').mockImplementation(() => of(conversation(77, [])));
        this.fixture = TestBed.createComponent(App);
        document.body.appendChild(this.fixture.nativeElement);
    }

    static async open(url: string, server: StubServer): Promise<Tab> {
        await page.viewport(1280, 800);
        const tab = new Tab(server);
        await tab.go(url);
        return tab;
    }

    async settle(): Promise<void> {
        for (let i = 0; i < 4; i++) {
            await new Promise((resolve) => setTimeout(resolve, 0));
            this.fixture.detectChanges();
            this.answer();
        }
    }

    /** Whatever the chat asked the stubbed server, answered; nothing else the app asks is. */
    private answer(): void {
        this.http.match('/api/v1/chat/capability').forEach((r) => r.flush({present: true}));
        this.http.match('/api/v1/chat/models').forEach((r) => r.flush(this.server.catalog));
        this.http.match('/api/v1/chat/status').forEach((r) => r.flush(this.server.status));
        this.http
            .match((r) => /^\/api\/v1\/chat\/conversations\/\d+$/.test(r.url))
            .forEach((r) => r.flush(this.server.conversations[Number(r.request.url.split('/').at(-1))]));
    }

    async go(url: string): Promise<void> {
        await TestBed.inject(Router).navigateByUrl(url);
        await this.settle();
    }

    select(): HTMLSelectElement {
        return document.querySelector<HTMLSelectElement>('select.lg-chat-model')!;
    }

    async pick(value: string): Promise<void> {
        this.select().value = value;
        this.select().dispatchEvent(new Event('change'));
        await this.settle();
    }

    async askQuestion(question: string): Promise<void> {
        const input = document.querySelector<HTMLTextAreaElement>('.lg-chat-input')!;
        input.value = question;
        input.dispatchEvent(new Event('input'));
        input.dispatchEvent(new KeyboardEvent('keydown', {key: 'Enter', bubbles: true, cancelable: true}));
        await this.settle();
    }

    /** The last turn's stream, answered by the model the server names and ended. */
    async answerWith(turnId: number, model: string, connection: 'PRIMARY' | 'SECOND'): Promise<void> {
        const stream = this.servers.at(-1)!;
        stream.next({event: 'turn', data: {turnId, model, connection}});
        stream.next({event: 'text', data: {delta: 'An answer.'}});
        stream.next({event: 'done', data: {state: 'DONE'}});
        stream.complete();
        await this.settle();
    }

    close(): void {
        this.servers.forEach((s) => s.complete());
        (this.fixture.nativeElement as HTMLElement).remove();
    }
}

/** Every key and value in both storages, so a pick written under any name is found. */
function storageText(): string {
    const dump = (storage: Storage) =>
        Array.from({length: storage.length}, (_, i) => storage.key(i)!).map((key) => `${key}=${storage.getItem(key)}`);
    return [...dump(localStorage), ...dump(sessionStorage)].join('\n');
}

/**
 * No remembered pick (ISC-512): a new conversation opens on the default whatever was picked in
 * another one, in this page, after a reload or in another tab; a stored conversation reopens on
 * the model of its last turn, read off the turn the server sends back with it.
 */
describe('the pick a conversation opens on (ISC-512)', () => {
    let tab: Tab | null = null;

    beforeEach(() => {
        localStorage.clear();
        sessionStorage.clear();
    });

    afterEach(() => tab?.close());

    /** Conversation 9 holds the billed answer asked in it; 10 is another one, asked on the default. */
    const SERVER = {
        catalog: BILLED_SECOND,
        status: CALLS_LEFT,
        conversations: {
            9: conversation(9, [storedTurn(41, 'model-a', 'PRIMARY'), storedTurn(42, 'model-c', 'SECOND')]),
            10: conversation(10, [storedTurn(43, 'model-a', 'PRIMARY')]),
        },
    };

    it('opens a new conversation on the default after a billed pick in another, in the same page', async () => {
        tab = await Tab.open('/dashboard?chat=9', SERVER);
        await tab.pick('PRIMARY:model-a');
        await tab.pick('SECOND:model-c');
        await tab.askQuestion('Which offers want Kafka?');
        expect(tab.ask).toHaveBeenLastCalledWith(9, 'Which offers want Kafka?', {connection: 'SECOND', model: 'model-c'});
        await tab.answerWith(42, 'model-c', 'SECOND');

        await tab.go('/dashboard?chat=new');
        expect(tab.select().value, 'a new conversation on the default').toBe('PRIMARY:model-a');
        await tab.askQuestion('What came in today?');
        expect(tab.create).toHaveBeenCalledTimes(1);
        expect(tab.ask.mock.calls.at(-1), 'the new conversation is asked on the default, no pick sent').toEqual([77, 'What came in today?']);

        await tab.go('/dashboard?chat=10');
        expect(tab.select().value, 'another conversation on the model of its own last turn').toBe('PRIMARY:model-a');

        await tab.go('/dashboard?chat=9');
        expect(tab.select().value, 'the billed conversation back on its last turn').toBe('SECOND:model-c');
        expect(storageText()).not.toMatch(/model-c|SECOND/);
    });

    it('opens new conversations on the default after a reload and in a second tab, and reopens one on its last turn', async () => {
        // The first page: the billed model picked in conversation 9 and asked there.
        tab = await Tab.open('/dashboard?chat=9', {...SERVER, conversations: {...SERVER.conversations, 9: conversation(9, [storedTurn(41, 'model-a', 'PRIMARY')])}});
        await tab.pick('SECOND:model-c');
        await tab.askQuestion('Which offers want Kafka?');
        await tab.answerWith(42, 'model-c', 'SECOND');
        tab.close();
        expect(storageText(), 'nothing left in storage that names the pick').not.toMatch(/model-c|SECOND/);

        // A reload onto a new conversation, then another stored one.
        tab = await Tab.open('/dashboard?chat=new', SERVER);
        expect(tab.select().value, 'a reload opens a new conversation on the default').toBe('PRIMARY:model-a');
        await tab.go('/dashboard?chat=10');
        expect(tab.select().value, 'and another conversation on its own last turn').toBe('PRIMARY:model-a');
        tab.close();

        // A second tab, beside the first: the same storage, a store of its own.
        tab = await Tab.open('/dashboard?chat=new', SERVER);
        expect(tab.select().value, 'a second tab opens a new conversation on the default').toBe('PRIMARY:model-a');
        await tab.askQuestion('What came in today?');
        expect(tab.ask.mock.calls.at(-1), 'and asks it without a pick').toEqual([77, 'What came in today?']);
        await tab.answerWith(48, 'model-a', 'PRIMARY');

        // The billed conversation reopened: on the model of its last turn, from the turn alone.
        await tab.go('/dashboard?chat=9');
        expect(tab.select().value, 'conversation 9 reopens on its last turn').toBe('SECOND:model-c');
        await tab.askQuestion('And in Berlin?');
        expect(tab.ask).toHaveBeenLastCalledWith(9, 'And in Berlin?', {connection: 'SECOND', model: 'model-c'});
        expect(storageText()).not.toMatch(/model-c|SECOND/);
    });

    it('reopens on the default when the last turn predates the select or its model is gone from the catalog', async () => {
        tab = await Tab.open('/dashboard?chat=11', {
            ...SERVER,
            conversations: {
                11: conversation(11, [storedTurn(44, 'model-c', 'SECOND'), storedTurn(45, 'model-old', null)]),
                12: conversation(12, [storedTurn(46, 'model-gone', 'SECOND')]),
                13: conversation(13, []),
            },
        });
        expect(tab.select().value, 'a turn stored before the select ran on the default').toBe('PRIMARY:model-a');
        await tab.go('/dashboard?chat=12');
        expect(tab.select().value, 'a model the catalog no longer lists').toBe('PRIMARY:model-a');
        await tab.go('/dashboard?chat=13');
        expect(tab.select().value, 'a conversation without a turn').toBe('PRIMARY:model-a');
        await tab.askQuestion('Anything new?');
        expect(tab.ask.mock.calls.at(-1)).toEqual([13, 'Anything new?']);
    });
});

/** Anti (ISC-513): one connection is one group, and an unbilled primary draws no marker anywhere. */
describe('the select with the primary connection alone (ISC-513)', () => {
    let tab: Tab | null = null;

    afterEach(() => tab?.close());

    it('lists one group of the primary models, with no billed marker on an option or under the answer', async () => {
        tab = await Tab.open('/dashboard?chat=10', {
            catalog: {connections: [BILLED_SECOND.connections[0]]},
            status: null,
            conversations: {10: conversation(10, [])},
        });

        const groups = Array.from(tab.select().querySelectorAll('optgroup'));
        expect(groups).toHaveLength(1);
        expect(groups[0].label).toBe('Primary connection');
        expect(Array.from(groups[0].querySelectorAll('option')).map((o) => o.textContent!.trim())).toEqual(['model-a', 'model-b']);
        expect(tab.http.match('/api/v1/chat/status'), 'no second connection, no status asked').toHaveLength(0);

        await tab.pick('PRIMARY:model-b');
        await tab.askQuestion('What came in today?');
        await tab.answerWith(47, 'model-b', 'PRIMARY');

        expect(document.querySelector('.lg-chat-answer-model-name')!.textContent!.trim()).toBe('model-b');
        expect(document.querySelector('.lg-chat-billed'), 'no billed marker anywhere').toBeNull();
        expect(document.body.textContent).not.toContain('billed');
    });
});
