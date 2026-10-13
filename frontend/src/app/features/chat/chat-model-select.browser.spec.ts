import {Component} from '@angular/core';
import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {ComponentFixture, TestBed} from '@angular/core/testing';
import {provideRouter, Router} from '@angular/router';
import {page} from 'vitest/browser';
import {TranslocoTestingModule} from '@jsverse/transloco';
import {provideTranslocoMessageformat} from '@jsverse/transloco-messageformat';
import {injectDispatch} from '@ngrx/signals/events';
import {of, Subject} from 'rxjs';
import {ChatApi} from '@core/api/chat.api';
import {chatEvents} from '@core/store/chat.events';
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

/** The words a box offers a reader: its text with runs of white space collapsed. */
function words(element: Element | null | undefined): string {
    return (element?.textContent ?? '').replace(/\s+/g, ' ').trim();
}

/**
 * The picker as these cases read it (ISC-522 replaced the native select): the trigger, the popover's
 * groups and rows, and the pick — the row marked selected, whose `data-value` is `CONNECTION:model` or
 * `default`. The rows stand in the DOM while the popover is closed, so the pick is read without opening it.
 */
const picker = {
    trigger: () => document.querySelector<HTMLButtonElement>('button.lg-chat-model-trigger'),
    panel: () => document.querySelector<HTMLElement>('.lg-chat-model-panel'),
    groups: () => Array.from(document.querySelectorAll<HTMLElement>('.lg-chat-model-panel [role="group"]')),
    rows: () => Array.from(document.querySelectorAll<HTMLElement>('.lg-chat-model-panel [role="option"]')),
    options: (group: Element) => Array.from(group.querySelectorAll<HTMLElement>('[role="option"]')),
    row: (value: string) => document.querySelector<HTMLElement>(`.lg-chat-model-panel [role="option"][data-value="${value}"]`),
    picked: () => document.querySelector('.lg-chat-model-panel [role="option"][aria-selected="true"]')?.getAttribute('data-value') ?? null,
    name: (row: Element) => words(row.querySelector('.lg-chat-model-name')),
    disabled: (row: Element) => row.getAttribute('aria-disabled') === 'true',
    /** What the group's head says it is: the icon's name, "Billed" or "Local". */
    tier: (group: Element) => group.querySelector('.lg-chat-model-head svg')?.getAttribute('aria-label') ?? null,
    reason: (group: Element) => words(group.querySelector('.lg-chat-model-reason')),
    /** The words the group is described by: its spend and its reason. */
    description: (group: Element) => words(document.getElementById(group.getAttribute('aria-describedby') ?? '')),
};

/**
 * The model pick beside the composer (ISC-511): the catalog grouped by connection, the billed marker on
 * a billed connection, a spent or unreachable connection's models disabled with the reason in words on
 * its group, the pick sent with the next question, and the model named under the answer it wrote.
 *
 * <p>In a real browser, because the picker is a popover in the browser's own top layer, and the full
 * app's drawer is what opens it.
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

    async function pick(value: string): Promise<void> {
        picker.trigger()!.click();
        await settle();
        picker.row(value)!.click();
        await settle();
    }

    async function askQuestion(question: string): Promise<void> {
        const input = document.querySelector<HTMLTextAreaElement>('.lg-chat-input')!;
        input.value = question;
        input.dispatchEvent(new Event('input'));
        input.dispatchEvent(new KeyboardEvent('keydown', {key: 'Enter', bubbles: true, cancelable: true}));
        await settle();
    }

    it('groups the models by connection, marks the billed ones and disables a spent connection with its reason', async () => {
        await open(CATALOG, SPENT);

        expect(picker.trigger(), 'the picker beside the composer').not.toBeNull();
        expect(picker.trigger()!.getAttribute('aria-label'), 'named as the model, its connection billed').toBe('Model: model-a, billed');

        const [primary, second] = picker.groups();
        expect(picker.groups()).toHaveLength(2);
        expect(picker.tier(primary)).toBe('Billed');
        expect(picker.options(primary).map(picker.name)).toEqual(['model-a', 'model-b']);
        expect(picker.options(primary).every((o) => !picker.disabled(o))).toBe(true);
        expect(picker.reason(primary), 'no reason on a connection that can be asked').toBe('');

        expect(picker.tier(second)).toBe('Local');
        expect(picker.reason(second)).toBe('No calls left today');
        expect(picker.options(second).map(picker.name)).toEqual(['model-c']);
        expect(picker.options(second).every(picker.disabled), 'the spent connection cannot be picked').toBe(true);

        // The reason in words on its group, and the group described by it.
        expect(picker.description(second)).toContain('No calls left today');

        // A new conversation's pick is the default on the primary connection.
        expect(picker.picked()).toBe('PRIMARY:model-a');
    });

    it('sends the pick with the next question, and names the model and the marker under the answer', async () => {
        await open(CATALOG, SPENT);

        await pick('PRIMARY:model-b');
        await askQuestion('What do Kafka offers pay?');

        expect(ask).toHaveBeenCalledWith(9, 'What do Kafka offers pay?', {connection: 'PRIMARY', model: 'model-b'});
        expect(picker.picked(), 'the pick holds for the conversation').toBe('PRIMARY:model-b');

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

        const [, second] = picker.groups();
        expect(picker.reason(second)).toBe('Not reachable: connection refused');
        expect(picker.options(second), 'nothing to pick on it').toHaveLength(0);
        expect(words(second.querySelector('.lg-chat-model-none'))).toBe('No models');
        expect(picker.description(second)).toContain('Not reachable: connection refused');
    });

    it('keeps a spent second connection disabled while the status is asked again after a turn', async () => {
        await open(CATALOG, SPENT);

        await askQuestion('What came in today?');
        server.next({event: 'turn', data: {turnId: 33, model: 'model-a', connection: 'PRIMARY'}});
        server.next({event: 'done', data: {state: 'DONE'}});
        await settle();

        // The turn may have spent the second connection's day, so the status is asked again; until it
        // answers, the last one known still holds.
        const refresh = http.expectOne('/api/v1/chat/status');
        const [, second] = picker.groups();
        expect(picker.reason(second)).toBe('No calls left today');
        expect(picker.options(second).every(picker.disabled), 'still disabled while the status is asked again').toBe(true);

        refresh.flush({...SPENT, secondCalls: 3});
        await settle();
        expect(picker.options(picker.groups()[1]).every((o) => !picker.disabled(o)), 'enabled once the new status says so').toBe(true);
    });

    it('keeps a spent second connection disabled while the status popover asks again', async () => {
        await open(CATALOG, SPENT);

        // Opening the empty chat's status popover asks the status again; the one known so far holds.
        document.querySelector<HTMLButtonElement>('.lg-chat-plate .lg-chat-status-trigger')!.dispatchEvent(new FocusEvent('focus'));
        await settle();
        const reload = http.expectOne('/api/v1/chat/status');
        const [, second] = picker.groups();
        expect(picker.reason(second)).toBe('No calls left today');
        expect(picker.options(second).every(picker.disabled), 'still disabled while the popover asks again').toBe(true);
        expect(document.querySelector('.lg-chat-status-second-calls')?.textContent?.trim(), 'the popover shows the status it has').toBe('20 of 20');

        reload.flush(SPENT);
        await settle();
        expect(picker.options(picker.groups()[1]).every(picker.disabled)).toBe(true);
    });

    it('draws no select when the catalog offers one model in all', async () => {
        await open({connections: [{...CATALOG.connections[0], models: [{name: 'model-a', defaultModel: true}]}]}, null);

        expect(picker.trigger(), 'no picker for one model').toBeNull();
        expect(picker.panel()).toBeNull();
        expect(http.match('/api/v1/chat/status'), 'no second connection and no picker, no status asked').toHaveLength(0);
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

/** What the stubbed API answers: the catalog — null fails it — the status, and each stored conversation by id. */
interface StubServer {
    readonly catalog: ChatModels | null;
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

    /** How often the chat asked for the model catalog. */
    catalogReads = 0;

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
        this.http.match('/api/v1/chat/models').forEach((r) => {
            this.catalogReads++;
            if (this.server.catalog === null) r.flush('down', {status: 503, statusText: 'Service Unavailable'});
            else r.flush(this.server.catalog);
        });
        this.http.match('/api/v1/chat/status').forEach((r) => r.flush(this.server.status));
        this.http
            .match((r) => /^\/api\/v1\/chat\/conversations\/\d+$/.test(r.url))
            .forEach((r) => r.flush(this.server.conversations[Number(r.request.url.split('/').at(-1))]));
    }

    async go(url: string): Promise<void> {
        await TestBed.inject(Router).navigateByUrl(url);
        await this.settle();
    }

    /** The catalog asked again, as the App root does each time the drawer opens. */
    async reloadCatalog(): Promise<void> {
        TestBed.runInInjectionContext(() => injectDispatch(chatEvents)).modelsRequested();
        await this.settle();
    }

    /** The current pick as the picker marks it: `CONNECTION:model`, or `default`. */
    picked(): string | null {
        return picker.picked();
    }

    /** Opens the picker and clicks the row of `value`. */
    async pick(value: string): Promise<void> {
        picker.trigger()!.click();
        await this.settle();
        picker.row(value)!.click();
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
        expect(tab.picked(), 'a new conversation on the default').toBe('PRIMARY:model-a');
        await tab.askQuestion('What came in today?');
        expect(tab.create).toHaveBeenCalledTimes(1);
        expect(tab.ask.mock.calls.at(-1), 'the new conversation is asked on the default, no pick sent').toEqual([77, 'What came in today?']);

        await tab.go('/dashboard?chat=10');
        expect(tab.picked(), 'another conversation on the model of its own last turn').toBe('PRIMARY:model-a');

        await tab.go('/dashboard?chat=9');
        expect(tab.picked(), 'the billed conversation back on its last turn').toBe('SECOND:model-c');
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
        expect(tab.picked(), 'a reload opens a new conversation on the default').toBe('PRIMARY:model-a');
        await tab.go('/dashboard?chat=10');
        expect(tab.picked(), 'and another conversation on its own last turn').toBe('PRIMARY:model-a');
        tab.close();

        // A second tab, beside the first: the same storage, a store of its own.
        tab = await Tab.open('/dashboard?chat=new', SERVER);
        expect(tab.picked(), 'a second tab opens a new conversation on the default').toBe('PRIMARY:model-a');
        await tab.askQuestion('What came in today?');
        expect(tab.ask.mock.calls.at(-1), 'and asks it without a pick').toEqual([77, 'What came in today?']);
        await tab.answerWith(48, 'model-a', 'PRIMARY');

        // The billed conversation reopened: on the model of its last turn, from the turn alone.
        await tab.go('/dashboard?chat=9');
        expect(tab.picked(), 'conversation 9 reopens on its last turn').toBe('SECOND:model-c');
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
        expect(tab.picked(), 'a turn stored before the select ran on the default').toBe('PRIMARY:model-a');
        await tab.go('/dashboard?chat=12');
        expect(tab.picked(), 'a model the catalog no longer lists').toBe('PRIMARY:model-a');
        await tab.go('/dashboard?chat=13');
        expect(tab.picked(), 'a conversation without a turn').toBe('PRIMARY:model-a');
        await tab.askQuestion('Anything new?');
        expect(tab.ask.mock.calls.at(-1)).toEqual([13, 'Anything new?']);
    });

    it('falls back to the default when the catalog reloads without the picked model', async () => {
        const server = {
            catalog: {
                connections: [
                    BILLED_SECOND.connections[0],
                    {
                        ...BILLED_SECOND.connections[1],
                        models: [
                            {name: 'model-b', defaultModel: false},
                            {name: 'model-c', defaultModel: false},
                        ],
                    },
                ],
            } as ChatModels,
            status: CALLS_LEFT,
            conversations: {10: conversation(10, [])},
        };
        tab = await Tab.open('/dashboard?chat=10', server);
        await tab.pick('SECOND:model-b');
        expect(tab.picked()).toBe('SECOND:model-b');

        // The catalog asked again no longer lists the pick.
        server.catalog = BILLED_SECOND;
        await tab.reloadCatalog();
        expect(tab.picked(), 'a pick the catalog no longer lists shows the default').toBe('PRIMARY:model-a');
        await tab.askQuestion('Anything new?');
        expect(tab.ask.mock.calls.at(-1), 'and the next question sends no pick').toEqual([10, 'Anything new?']);

        // A connection that became unreachable takes its pick with it the same way.
        await tab.answerWith(50, 'model-a', 'PRIMARY');
        await tab.pick('SECOND:model-c');
        expect(tab.picked()).toBe('SECOND:model-c');
        server.catalog = {connections: [BILLED_SECOND.connections[0], {...BILLED_SECOND.connections[1], reachable: false, reason: 'timed out'}]};
        await tab.reloadCatalog();
        expect(tab.picked(), 'a pick on an unreachable connection shows the default').toBe('PRIMARY:model-a');
        await tab.askQuestion('And in Berlin?');
        expect(tab.ask.mock.calls.at(-1)).toEqual([10, 'And in Berlin?']);
    });

    it('falls back to the default when the status says the picked connection is spent, and brings the pick back the next day', async () => {
        const server = {catalog: BILLED_SECOND, status: CALLS_LEFT as ChatStatus, conversations: {10: conversation(10, [])}};
        tab = await Tab.open('/dashboard?chat=10', server);
        await tab.pick('SECOND:model-c');
        expect(tab.picked()).toBe('SECOND:model-c');

        // The turn on the second connection spends its day; the status asked after it says so.
        await tab.askQuestion('Which offers want Kafka?');
        expect(tab.ask.mock.calls.at(-1)).toEqual([10, 'Which offers want Kafka?', {connection: 'SECOND', model: 'model-c'}]);
        server.status = {...CALLS_LEFT, secondCalls: 20};
        await tab.answerWith(51, 'model-c', 'SECOND');

        expect(tab.picked(), 'a pick on a spent connection shows the default').toBe('PRIMARY:model-a');
        await tab.askQuestion('And in Berlin?');
        expect(tab.ask.mock.calls.at(-1), 'and the next question sends no pick').toEqual([10, 'And in Berlin?']);
        await tab.answerWith(52, 'model-a', 'PRIMARY');

        // The day turns: the pick itself was kept, so it comes back.
        server.status = CALLS_LEFT;
        await tab.reloadCatalog();
        expect(tab.picked(), 'the kept pick is back once calls are left').toBe('SECOND:model-c');
    });

    it('keeps the explicit pick when the catalog asked again fails, and leaves judging it to the server', async () => {
        const server = {catalog: BILLED_SECOND as ChatModels | null, status: CALLS_LEFT, conversations: {10: conversation(10, [])}};
        tab = await Tab.open('/dashboard?chat=10', server);
        await tab.pick('SECOND:model-c');
        expect(tab.picked()).toBe('SECOND:model-c');

        // The catalog asked again fails: nothing is left to judge the pick by, so it is not dropped.
        server.catalog = null;
        await tab.reloadCatalog();
        await tab.askQuestion('Anything new?');
        expect(tab.ask.mock.calls.at(-1), 'the pick is still sent').toEqual([10, 'Anything new?', {connection: 'SECOND', model: 'model-c'}]);
    });
});

/**
 * The configured chat model is not among the listed ones (ISC-511, ISC-512): "the default" is then an
 * option of its own, first, which sends no pick, and every listed model — the first included — is an
 * explicit pick that is sent.
 */
describe('the select when no listed model is the default', () => {
    let tab: Tab | null = null;

    beforeEach(() => {
        localStorage.clear();
        sessionStorage.clear();
    });

    afterEach(() => tab?.close());

    const UNFLAGGED: ChatModels = {
        connections: [
            {
                ...BILLED_SECOND.connections[0],
                models: [
                    {name: 'model-a', defaultModel: false},
                    {name: 'model-b', defaultModel: false},
                ],
            },
            BILLED_SECOND.connections[1],
        ],
        defaultModelName: 'model-x',
    };
    const SERVER = {catalog: UNFLAGGED, status: CALLS_LEFT, conversations: {10: conversation(10, [])}};

    it('shows the default first and selected, and asks a new question without a pick', async () => {
        tab = await Tab.open('/dashboard?chat=10', SERVER);

        const first = picker.rows()[0];
        expect(first.closest('[role="group"]'), 'the default stands outside the connection groups').toBeNull();
        expect(picker.name(first)).toBe('Default · model-x');
        expect(first.getAttribute('aria-selected'), 'a conversation without a turn on the default').toBe('true');
        expect(tab.picked()).toBe('default');

        await tab.askQuestion('What came in today?');
        expect(tab.ask.mock.calls.at(-1), 'the default is asked without a pick').toEqual([10, 'What came in today?']);
    });

    it("sends the primary connection's first listed model as the explicit pick it is", async () => {
        tab = await Tab.open('/dashboard?chat=10', SERVER);

        await tab.pick('PRIMARY:model-a');
        expect(tab.picked()).toBe('PRIMARY:model-a');
        await tab.askQuestion('What came in today?');
        expect(tab.ask).toHaveBeenLastCalledWith(10, 'What came in today?', {connection: 'PRIMARY', model: 'model-a'});

        // And the default picked back sends none again.
        await tab.answerWith(49, 'model-a', 'PRIMARY');
        await tab.pick(picker.rows()[0].getAttribute('data-value')!);
        await tab.askQuestion('And in Berlin?');
        expect(tab.ask.mock.calls.at(-1), 'the default picked back asks without a pick').toEqual([10, 'And in Berlin?']);
    });

    it('disables the default with the reason when the primary connection it runs on is unreachable', async () => {
        const [primary, second] = UNFLAGGED.connections;
        tab = await Tab.open('/dashboard?chat=10', {
            ...SERVER,
            catalog: {...UNFLAGGED, connections: [{...primary, reachable: false, reason: 'connection refused', models: []}, second]},
        });

        const first = picker.rows()[0];
        expect(first.closest('[role="group"]'), 'the default stands outside the connection groups').toBeNull();
        expect(picker.name(first)).toBe('Default · model-x');
        expect(picker.disabled(first), 'the default runs on the unreachable primary connection').toBe(true);
        const [primaryGroup] = picker.groups();
        expect(picker.reason(primaryGroup), 'the reason in words, on the group it runs on').toBe('Not reachable: connection refused');
        expect(picker.description(primaryGroup)).toContain('Not reachable: connection refused');
        expect(picker.disabled(picker.row('SECOND:model-c')!), 'the reachable connection can still be picked').toBe(false);
        // Never moved onto another connection by itself — that one may be billed; the person picks it.
        expect(tab.picked()).toBe('default');
    });

    it('opens a new conversation on the default, named without a model when the server names none', async () => {
        tab = await Tab.open('/dashboard?chat=new', {...SERVER, catalog: {...UNFLAGGED, defaultModelName: null}});

        const first = picker.rows()[0];
        expect(picker.name(first)).toBe('Default');
        expect(first.getAttribute('aria-selected')).toBe('true');
        await tab.askQuestion('What came in today?');
        expect(tab.ask.mock.calls.at(-1)).toEqual([77, 'What came in today?']);
    });
});

/** A turn stored before the select has no connection; it was answered on the primary one (ISC-511). */
describe('the billed marker under a turn stored before the select', () => {
    let tab: Tab | null = null;

    afterEach(() => tab?.close());

    it('reads a stored turn without a connection as answered on the billed primary connection', async () => {
        tab = await Tab.open('/dashboard?chat=11', {
            catalog: CATALOG,
            status: {...SPENT, secondCalls: 0},
            conversations: {11: conversation(11, [storedTurn(45, 'model-old', null)])},
        });

        const line = document.querySelector('.lg-chat-answer-model')!;
        expect(line.querySelector('.lg-chat-answer-model-name')!.textContent!.trim()).toBe('model-old');
        expect(line.querySelector('.lg-chat-billed'), 'the primary connection is billed').not.toBeNull();
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

        const groups = picker.groups();
        expect(groups).toHaveLength(1);
        expect(picker.tier(groups[0])).toBe('Local');
        expect(picker.options(groups[0]).map(picker.name)).toEqual(['model-a', 'model-b']);
        expect(tab.http.match('/api/v1/chat/status'), 'no second connection, no status asked').toHaveLength(0);

        await tab.pick('PRIMARY:model-b');
        await tab.askQuestion('What came in today?');
        await tab.answerWith(47, 'model-b', 'PRIMARY');

        expect(document.querySelector('.lg-chat-answer-model-name')!.textContent!.trim()).toBe('model-b');
        expect(document.querySelector('.lg-chat-billed'), 'no billed marker anywhere').toBeNull();
        expect(picker.panel()!.querySelector('svg[aria-label="Billed"]'), 'no billed icon in the picker').toBeNull();
        expect(picker.trigger()!.getAttribute('aria-label')).not.toContain('billed');
        expect(document.body.textContent).not.toContain('billed');
    });
});

/** The catalog is asked each time the drawer opens, not only the first time (ISC-511). */
describe('the catalog on reopening the drawer', () => {
    let tab: Tab | null = null;

    afterEach(() => tab?.close());

    it('asks for the catalog again when the drawer is closed and opened again', async () => {
        tab = await Tab.open('/dashboard?chat=10', {
            catalog: CATALOG,
            status: CALLS_LEFT,
            conversations: {10: conversation(10, [])},
        });
        expect(tab.catalogReads, 'the first open').toBe(1);

        // Closed the way the drawer's ✕ closes it: a navigation without `chat` alone is put back (ISC-444).
        TestBed.runInInjectionContext(() => injectDispatch(chatEvents)).closeRequested();
        await tab.settle();
        expect(picker.trigger(), 'the drawer is closed').toBeNull();
        expect(tab.catalogReads, 'closed, nothing asked').toBe(1);

        await tab.go('/dashboard?chat=10');
        expect(tab.catalogReads, 'reopened').toBe(2);
    });
});
