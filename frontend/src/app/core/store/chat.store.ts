import {HttpErrorResponse} from '@angular/common/http';
import {computed, inject} from '@angular/core';
import {NavigationEnd, Router} from '@angular/router';
import {signalStore, withComputed, withState} from '@ngrx/signals';
import {Events, on, withEventHandlers, withReducer} from '@ngrx/signals/events';
import {
    catchError,
    concat,
    concatMap,
    debounceTime,
    distinctUntilChanged,
    EMPTY,
    exhaustMap,
    filter,
    ignoreElements,
    map,
    merge,
    Observable,
    of,
    startWith,
    switchMap,
    takeWhile,
    tap,
} from 'rxjs';
import {ChatApi} from '@core/api/chat.api';
import {serverMessage} from '@core/api/server-message';
import {
    ChatContextItem,
    ChatError,
    ChatErrorReason,
    ChatEvent,
    ChatTurnSource,
    ChatStep,
    ChatTurnState,
    ConversationSummary,
    conversationTitle,
    ConversationView,
    formatChatCtx,
    parseChatCtx,
    pinnedOffer,
    sameContext,
    questionFits,
    TurnView,
    withPins,
} from '@core/model/chat';
import {withAppDevtools} from '@core/store/devtools';
import {chatEvents} from './chat.events';

/**
 * Where the drawer stands, read from `?chat`: absent is `closed`, `list` and `new` are
 * themselves, an id is `conversation` until the server answers 404 and it becomes `missing`.
 * A value that is no id at all is `missing` too, without asking anybody.
 */
export type ChatView = 'closed' | 'list' | 'new' | 'conversation' | 'missing';

/** The turn being streamed, or the last one streamed, assembled from its events. */
export interface LiveTurn {
    /** Null only between a first question under `?chat=new` and its conversation being created. */
    readonly conversationId: number | null;
    /** The values `failure` interpolates: the server's sentence for a model failure, else null. */
    readonly failureParams: Readonly<Record<string, string>> | null;
    /** Null until the `turn` event names it; stop needs it. */
    readonly turnId: number | null;
    readonly question: string;
    /** Every `text` delta so far, in order. */
    readonly answer: string;
    /** One row per ordinal: a step's `DONE` replaces its `RUNNING`. */
    readonly steps: readonly ChatStep[];
    readonly sources: readonly ChatTurnSource[];
    readonly state: ChatTurnState;
    /** What the server's `error` event said. */
    readonly error: ChatError | null;
    /** What went wrong on this side — a catalog key or the server's sentence: refused, broken off, cut. */
    readonly failure: string | null;
    readonly replacesTurnId: number | null;
    /**
     * Stop was pressed before the `turn` event named the turn (fix 5F-3): the stop is sent as soon
     * as it does. Remembered rather than dropped, because the composer offers Stop from the first
     * moment the turn streams, and aborting the fetch would not end it — the server keeps the model
     * call running for a reader that left and never stores the turn as `STOPPED`.
     */
    readonly stopPending: boolean;
}

interface ChatState {
    /** Whether a chat model is configured; null until asked. */
    present: boolean | null;
    view: ChatView;
    /** The id `?chat` names, while it names one. */
    openId: number | null;
    /** The last id opened, kept after `?chat` goes away so the header can reopen it. */
    lastId: number | null;
    conversations: readonly ConversationSummary[];
    listLoading: boolean;
    /** What the search field holds (ISC-450); not in the URL, so a reload starts unsearched. */
    query: string;
    /** The trimmed words the last list read was sent with. */
    sentQuery: string;
    /** The words the list on screen answers; an empty list with words is "no match", not "no conversations". */
    resultQuery: string;
    /** The open conversation as the server last sent it, plus the live turns folded into it. */
    conversation: ConversationView | null;
    loading: boolean;
    /**
     * `?chatCtx` as items (ISC-446): what the next question is asked under. Read by `routed` beside
     * `?chat`, so a reload, the create and a change of screen all see the same pins; a store-only pin
     * was dropped by the very next route.
     */
    context: readonly ChatContextItem[];
    live: LiveTurn | null;
    /**
     * Whether a turn's stream is open, from the question the reducer accepts to the stream's last
     * event. The one notion of busy: the reducer gates on it, and the handler's `exhaustMap` is
     * busy for exactly as long, because a turn's stream ends at its `done` or `error`.
     */
    streamOpen: boolean;
    /** A catalog key or the server's sentence, for a list, a read or a delete that failed. */
    error: string | null;
    /**
     * Whether the last pin was refused because the offers would pass {@link MAX_PINNED_OFFERS}
     * (ISC-453): the composer says why under the chips. Cleared by the next pin taken, an unpin, and a route.
     */
    contextRefused: boolean;
}

const initialState: ChatState = {
    present: null,
    view: 'closed',
    openId: null,
    lastId: null,
    conversations: [],
    listLoading: false,
    query: '',
    sentQuery: '',
    resultQuery: '',
    conversation: null,
    loading: false,
    context: [],
    live: null,
    streamOpen: false,
    error: null,
    contextRefused: false,
};

const ID = /^[1-9]\d*$/;

/**
 * The pins the next question is asked under (ISC-446, ISC-451): the ones `?chatCtx` names, else —
 * for a stored conversation opened without it — the ones the server stored with it.
 */
function effectiveContext(state: {view: ChatView; context: readonly ChatContextItem[]; conversation: ConversationView | null}): readonly ChatContextItem[] {
    const open = state.conversation;
    if (state.context.length > 0 || state.view !== 'conversation' || open === null) return state.context;
    if (open.context !== undefined) return open.context;
    return open.pinnedOfferId === null ? [] : [{kind: 'OFFER', offerId: open.pinnedOfferId}];
}

/**
 * The pins after one was added or taken out, into the state of an open conversation — stored, or
 * `new` still gathering them — and into the open conversation's own copy until the server answers.
 * Anywhere else nothing changes here: the effect starts a new conversation and `routed` reads it.
 */
function withContext(state: ChatState, next: (current: readonly ChatContextItem[]) => readonly ChatContextItem[]): Partial<ChatState> {
    if (state.view !== 'conversation' && state.view !== 'new') return {};
    const context = next(effectiveContext(state));
    const open = state.view === 'conversation' ? state.conversation : null;
    return {context, ...(open === null ? {} : {conversation: {...open, context}})};
}

/** Where `routed` moves the drawer: the view `?chat` names, with `?chatCtx`'s pins beside it. */
function routedState(chat: string | null, ctx: string | null, state: ChatState): Partial<ChatState> {
    if (chat === null) return {view: 'closed' as const, openId: null, context: []};
    if (chat === 'list') return {view: 'list' as const, openId: null, context: [], listLoading: true, error: null};
    const context = parseChatCtx(ctx);
    if (chat === 'new') return {view: 'new' as const, openId: null, context, conversation: null, error: null};
    if (!ID.test(chat)) return {view: 'missing' as const, openId: null, context: []};
    const id = Number(chat);
    // The conversation already held stays: a reopen, a screen change, or the swap from
    // `new` to the id just created must not read it again or drop the live turn.
    const held = state.conversation?.id === id;
    // A first question whose create failed has no conversation to follow into (fix 4F-2); one
    // whose create is still in flight stays, because `created` hands it its conversation.
    const orphan = state.live?.conversationId === null && !state.streamOpen;
    return {
        view: 'conversation' as const,
        openId: id,
        lastId: id,
        context,
        conversation: held ? state.conversation : null,
        loading: !held,
        error: null,
        ...(orphan ? {live: null} : {}),
    };
}

/** One pin or several, as `contextPinned` carries them. */
function asPins(payload: ChatContextItem | readonly ChatContextItem[]): readonly ChatContextItem[] {
    return Array.isArray(payload) ? payload : [payload as ChatContextItem];
}

/** How long the search field waits for the typing to pause before it asks the server (ISC-450). */
const SEARCH_DEBOUNCE_MS = 250;

/** The `info` on the chat's own closing navigation: the one navigation without `?chat` that is not put back. */
const CLOSED_BY_CHAT = Symbol('chat closed');

/** What an `error` event says, in the catalog's words: a spent budget and spent rounds are limits, not a broken answer. */
const REASON_KEYS: Record<ChatErrorReason, string> = {
    BUDGET: 'error.chatBudget',
    ROUNDS: 'error.chatRounds',
    MODEL: 'error.chatModel',
};

/**
 * The failure line for an `error` event (fix 5F-8). A model failure has many causes — a timeout, a
 * conversation deleted meanwhile, a turn that could not be stored, no model reachable — and only
 * the server's sentence tells them apart, so it is interpolated into the catalog's frame as text.
 * Without a sentence it is a cut answer, like a stream that closed early.
 */
function failureOf(error: ChatError): Pick<LiveTurn, 'failure' | 'failureParams'> {
    if (error.reason !== 'MODEL') return {failure: REASON_KEYS[error.reason], failureParams: null};
    const message = error.message?.trim() ?? '';
    return message === '' ? {failure: 'error.chatStream', failureParams: null} : {failure: REASON_KEYS.MODEL, failureParams: {message}};
}

function startTurn(conversationId: number | null, question: string, replacesTurnId: number | null): LiveTurn {
    return {
        conversationId,
        turnId: null,
        question,
        answer: '',
        steps: [],
        sources: [],
        state: 'STREAMING',
        error: null,
        failure: null,
        failureParams: null,
        replacesTurnId,
        stopPending: false,
    };
}

/** One wire event applied to the live turn. The server fixes the order; this only accumulates. */
function applyEvent(turn: LiveTurn, event: ChatEvent): LiveTurn {
    switch (event.event) {
        case 'turn':
            return {...turn, turnId: event.data.turnId};
        case 'step': {
            const step = event.data;
            const steps = [...turn.steps.filter((s) => s.ordinal !== step.ordinal), step];
            return {...turn, steps: steps.sort((a, b) => a.ordinal - b.ordinal)};
        }
        case 'text':
            return {...turn, answer: turn.answer + event.data.delta};
        case 'sources':
            return {...turn, sources: event.data.sources};
        case 'error':
            return {...turn, state: 'INCOMPLETE', error: event.data, ...failureOf(event.data)};
        case 'done':
            return {...turn, state: event.data.state};
    }
}

/** The finished live turn as a stored one, so the next question does not push it off screen. */
function fold(conversation: ConversationView | null, live: LiveTurn | null): ConversationView | null {
    if (conversation === null || live === null || live.turnId === null || live.conversationId !== conversation.id) {
        return conversation;
    }
    if (conversation.turns.some((t) => t.id === live.turnId)) return conversation;
    const turn: TurnView = {
        id: live.turnId,
        question: live.question,
        answer: live.answer,
        state: live.state,
        steps: live.steps,
        sources: live.sources,
        replacesTurnId: live.replacesTurnId,
        model: null,
        createdAt: new Date().toISOString(),
    };
    return {...conversation, turns: [...conversation.turns, turn]};
}

/**
 * A conversation still untitled, named after the question about to be asked in it, in the drawer and
 * in the list — what the server's `startTurn` writes (`title = CASE WHEN title = '' …`), so the
 * heading, which is the drawer's accessible name, and the list row are never blank (fix 4F-3).
 */
function named<C extends ConversationView | null>(
    conversation: C,
    conversations: readonly ConversationSummary[],
    question: string,
): {conversation: C; conversations: readonly ConversationSummary[]} {
    if (conversation === null || conversation.title !== '' || question.trim() === '') return {conversation, conversations};
    const title = conversationTitle(question);
    return {
        conversation: {...conversation, title},
        conversations: conversations.map((c) => (c.id === conversation.id && c.title === '' ? {...c, title} : c)),
    };
}

function status(error: unknown): number | null {
    return error instanceof HttpErrorResponse ? error.status : null;
}

/**
 * The chat drawer's state, on every screen: the conversation list, the open conversation and
 * its turns, and the turn being streamed.
 *
 * <p>**`?chat` is the source of truth, both ways.** The router's `NavigationEnd` becomes
 * `routed`, which alone decides what is open; the intents only write the parameter. That is the
 * house's router-driven state (`app.config.ts`): a reload or a change of screen that carries
 * `?chat` lands on the same conversation and the same turn, and a link to one is shareable. The
 * parameter is read off the router's root snapshot rather than a routed component's input,
 * because the drawer is global and belongs to no route.
 *
 * <p>**An id the server does not know is a state, not a failure** (ISC-431): it lands in
 * `missing`, which the drawer shows as an empty state, and raises nothing a toast would read.
 *
 * <p>**One turn streams at a time.** A second question while one runs is ignored in the reducer
 * and in the handler alike, the way a draft is in `CoverLetterStore`: a turn costs a model call.
 * Both read the same fact, `streamOpen`: the handler takes a turn's stream only up to its `done`
 * or `error`, so a question sent after `done` but before the connection closed is taken by both
 * instead of being accepted by one and dropped by the other.
 */
export const ChatStore = signalStore(
    {providedIn: 'root'},
    withState(initialState),
    withAppDevtools('chat'),
    withComputed(({live, conversation, view, loading, streamOpen, context}) => ({
        streaming: computed(() => live()?.state === 'STREAMING'),
        /** Every pin the next question is asked under, one chip each (ISC-446, ISC-451). */
        contextItems: computed(() => effectiveContext({view: view(), context: context(), conversation: conversation()})),
        /**
         * The offer the conversation is pinned to: the one `?chatCtx` names, else — for a stored
         * conversation opened without it — the one the server stored with it (ISC-446).
         */
        pinnedOfferId: computed<number | null>(() =>
            pinnedOffer(effectiveContext({view: view(), context: context(), conversation: conversation()})),
        ),
        /**
         * Whether a question is taken here: under `?chat=new`, or in a conversation that has loaded.
         * A missing or a still-loading one has no conversation yet, and a question there would create
         * a new one the URL and the drawer never follow.
         */
        canAsk: computed(() => view() === 'new' || (view() === 'conversation' && !loading())),
        /**
         * Whether a question sent now would be taken: `canAsk`, and no turn's stream still open. The
         * stream can outlive the turn shown — a deleted conversation's turn streams on until its
         * `done` — so this, not `streaming`, is what the composer reads before it lets go of a draft.
         */
        takesQuestion: computed(() => !streamOpen() && (view() === 'new' || (view() === 'conversation' && !loading()))),
        /** The open conversation's stored turns, without the one the live turn is. */
        turns: computed<readonly TurnView[]>(() => {
            const turnId = live()?.turnId;
            return (conversation()?.turns ?? []).filter((t) => t.id !== turnId);
        }),
        /**
         * The live turn when it belongs to the open conversation; the drawer shows it after `turns`.
         * One without a conversation yet — its create in flight, or failed — belongs to `?chat=new`
         * alone, never to whatever conversation is open (fix 4F-2).
         */
        liveTurn: computed<LiveTurn | null>(() => {
            const turn = live();
            if (turn === null) return null;
            return (turn.conversationId === null ? view() === 'new' : turn.conversationId === conversation()?.id) ? turn : null;
        }),
    })),
    withReducer(
        on(chatEvents.routed, ({payload: {chat, ctx}}, state) => ({...routedState(chat, ctx, state), contextRefused: false})),
        on(chatEvents.capabilityLoaded, ({payload}) => ({present: payload})),
        // One chip per offer, view or window: a second press of the same control changes nothing. Past
        // ten offers nothing is pinned at all, wherever the pins would have gone (ISC-453).
        on(chatEvents.contextPinned, ({payload}, state) => {
            const adding = asPins(payload);
            const open = state.view === 'conversation' || state.view === 'new';
            const next = withPins(open ? effectiveContext(state) : [], adding);
            if (next === null) return {contextRefused: true};
            return {...withContext(state, () => next), contextRefused: false};
        }),
        on(chatEvents.contextRemoved, ({payload}, state) => ({
            ...withContext(state, (current) => current.filter((item) => !sameContext(item, payload))),
            contextRefused: false,
        })),
        on(chatEvents.contextStored, ({payload}, state) =>
            state.conversation?.id === payload.id ? {conversation: {...state.conversation, context: payload.context ?? []}} : {},
        ),
        on(chatEvents.contextFailed, ({payload}) => ({error: payload})),
        on(chatEvents.listLoaded, ({payload}, state) => ({conversations: payload, listLoading: false, resultQuery: state.sentQuery})),
        on(chatEvents.searchChanged, ({payload}) => ({query: payload})),
        on(chatEvents.listQueried, ({payload}) => ({sentQuery: payload, listLoading: true})),
        // The title the server stored — trimmed, cut at 120, or derived again from an empty one.
        on(chatEvents.renamed, ({payload}, state) => ({
            conversations: state.conversations.map((c) => (c.id === payload.id ? {...c, title: payload.title} : c)),
            conversation: state.conversation?.id === payload.id ? {...state.conversation, title: payload.title} : state.conversation,
        })),
        on(chatEvents.renameFailed, ({payload}) => ({error: payload})),
        on(chatEvents.listFailed, ({payload}) => ({error: payload, listLoading: false})),
        on(chatEvents.conversationLoaded, ({payload}, state) =>
            payload.id === state.openId ? {conversation: payload, loading: false} : {},
        ),
        on(chatEvents.conversationMissing, ({payload}, state) => ({
            ...(payload === state.openId ? {view: 'missing' as const, conversation: null, loading: false} : {}),
            lastId: state.lastId === payload ? null : state.lastId,
        })),
        on(chatEvents.conversationFailed, ({payload}, state) =>
            payload.id === state.openId ? {error: payload.message, loading: false} : {},
        ),
        on(chatEvents.deleted, ({payload}, state) => ({
            conversations: state.conversations.filter((c) => c.id !== payload),
            conversation: state.conversation?.id === payload ? null : state.conversation,
            lastId: state.lastId === payload ? null : state.lastId,
            live: state.live?.conversationId === payload ? null : state.live,
        })),
        on(chatEvents.deleteFailed, ({payload}) => ({error: payload})),
        // The same predicate as `takesQuestion`, read off the state because a reducer sees no computed;
        // a question the server would refuse for its length costs no conversation and no turn.
        on(chatEvents.asked, ({payload}, state) =>
            state.streamOpen || !questionFits(payload) || !(state.view === 'new' || (state.view === 'conversation' && !state.loading))
                ? {}
                : {
                    ...named(fold(state.conversation, state.live), state.conversations, payload),
                    live: startTurn(state.conversation?.id ?? null, payload, null),
                    streamOpen: true,
                },
        ),
        on(chatEvents.regenerateRequested, ({payload}, state) => {
            if (state.streamOpen || state.conversation === null) return {};
            const conversation = fold(state.conversation, state.live);
            const replaced = conversation?.turns.find((t) => t.id === payload);
            return replaced === undefined
                ? {}
                : {conversation, live: startTurn(state.conversation.id, replaced.question, payload), streamOpen: true};
        }),
        // The conversation a first question created (fix 4F-1). It joins the list either way, named
        // after the question the way the server names it; it becomes the open one only while the
        // drawer still stands on `?chat=new`, because a reader who moved on meanwhile keeps the
        // thread they moved to. The turn streams into the created conversation regardless.
        on(chatEvents.created, ({payload}, state) => {
            const pending = state.live !== null && state.live.conversationId === null ? state.live : null;
            const {conversation, conversations} = named(
                payload,
                [
                    {id: payload.id, title: payload.title, updatedAt: payload.updatedAt, lastActivityAt: payload.updatedAt},
                    ...state.conversations.filter((c) => c.id !== payload.id)],
                pending?.question ?? '',
            );
            return {
                conversations,
                live: pending === null ? state.live : {...pending, conversationId: payload.id},
                ...(state.view === 'new' && pending !== null ? {conversation, openId: payload.id} : {}),
            };
        }),
        // A Stop before the `turn` event has no id to send yet; it is remembered on the turn and sent
        // when the id arrives (fix 5F-3). The same ownership as `liveTurn`: only the drawer's own turn.
        on(chatEvents.stopRequested, (_, state) => {
            const live = state.live;
            if (live?.state !== 'STREAMING' || live.turnId !== null) return {};
            const owned = live.conversationId === null ? state.view === 'new' : live.conversationId === state.conversation?.id;
            return owned ? {live: {...live, stopPending: true}} : {};
        }),
        on(chatEvents.streamed, ({payload}, state) => (state.live === null ? {} : {live: applyEvent(state.live, payload)})),
        on(chatEvents.streamEnded, (_, state) =>
            state.live?.state === 'STREAMING'
                ? {live: {...state.live, state: 'INCOMPLETE' as const, failure: 'error.chatStream'}, streamOpen: false}
                : {streamOpen: false},
        ),
        on(chatEvents.streamFailed, ({payload}, state) =>
            state.live?.state === 'STREAMING'
                ? {live: {...state.live, state: 'INCOMPLETE' as const, failure: payload}, streamOpen: false}
                : {streamOpen: false},
        ),
    ),
    withEventHandlers((store) => {
        const events = inject(Events);
        const api = inject(ChatApi);
        const router = inject(Router);

        /** `?chat` and `?chatCtx` as the URL holds them now. */
        const chatParams = (): {chat: string | null; ctx: string | null} => {
            const params = router.routerState.snapshot.root.queryParamMap;
            return {chat: params.get('chat'), ctx: params.get('chatCtx')};
        };

        /**
         * Writes `?chat` on whatever screen is showing, leaving the path and every other parameter as
         * they are. `ctx` writes `?chatCtx` beside it, null taking it out; left undefined, the pins
         * the URL holds stay — which is what the swap from `new` to the created id needs (ISC-446).
         */
        const go = (chat: string | number | null, ctx?: string | null, replaceUrl = false): void => {
            void router.navigate([], {
                queryParams: {chat, ...(ctx === undefined ? {} : {chatCtx: ctx})},
                queryParamsHandling: 'merge',
                preserveFragment: true,
                replaceUrl,
                // The chat's own close, told apart from a link that merely did not carry `?chat` (ISC-444).
                ...(chat === null ? {info: CLOSED_BY_CHAT} : {}),
            });
        };

        /**
         * Whether the navigation that just ended may take `?chat` away: the chat's own close, or the
         * history going back or forward to an entry that never had it. Any other — a navigation entry,
         * the brand link, an offer card, a page rewriting its own parameters — only failed to carry it.
         */
        const mayClose = (): boolean => {
            const navigation = router.currentNavigation() ?? router.lastSuccessfulNavigation();
            return navigation === null || navigation.extras.info === CLOSED_BY_CHAT || navigation.trigger === 'popstate';
        };

        /**
         * The `?chat` the URL last carried, with its `?chatCtx`: what a navigation the chat did not
         * start gets back, both at once, so a change of screen never drops a pin (ISC-444, ISC-446).
         */
        let held: {chat: string; ctx: string | null} | null = null;

        /**
         * A turn's events up to and including its `done` or `error`, then its end — or its failure,
         * named as the server's sentence or a catalog key. The contract sends nothing after either,
         * and the server has stored the turn before it sends them, so the connection is let go there
         * rather than when it closes: that is what keeps `streamOpen` and `exhaustMap` in step.
         * A spent budget is not a refusal but an `error` event, which `REASON_KEYS` names.
         */
        const turn = (stream: Observable<ChatEvent>) =>
            concat(
                stream.pipe(
                    takeWhile((event) => event.event !== 'done' && event.event !== 'error', true),
                    map((event) => chatEvents.streamed(event)),
                ),
                of(chatEvents.streamEnded()),
            ).pipe(
                catchError((error: unknown) => of(chatEvents.streamFailed(serverMessage(error as {error?: unknown}, 'error.chatStream')))),
            );

        return [
            // The URL, read once at start and again after every navigation. A navigation that arrives
            // without `chat` while the chat is open, and that the chat did not start, is followed by one
            // that puts it back, replacing the entry (ISC-444): one rule for every link in the shell and
            // on every page, where `queryParamsHandling` on each link would miss the next one written.
            // It never reaches `routed`, so the drawer does not close and reopen in between.
            router.events.pipe(
                filter((event) => event instanceof NavigationEnd),
                map(chatParams),
                startWith(chatParams()),
                filter(({chat, ctx}) => {
                    if (chat === null && held !== null && !mayClose()) {
                        go(held.chat, held.ctx, true);
                        return false;
                    }
                    held = chat === null ? null : {chat, ctx};
                    return true;
                }),
                // A closed chat has no pins to tell apart: a stray `chatCtx` alone moves nothing.
                map(({chat, ctx}) => ({chat, ctx: chat === null ? null : ctx})),
                distinctUntilChanged((a, b) => a.chat === b.chat && a.ctx === b.ctx),
                map((params) => chatEvents.routed(params)),
            ),

            // The intents write the URL and nothing else; `routed` follows from it. Every move to
            // another conversation takes the pins out: a stored one brings its own (`pinnedOfferId`).
            events.on(chatEvents.openRequested).pipe(tap(({payload}) => go(payload, null))),
            events.on(chatEvents.newRequested).pipe(
                tap(({payload}) =>
                    go('new', formatChatCtx(payload.pinnedOfferId === null ? [] : [{kind: 'OFFER', offerId: payload.pinnedOfferId}])),
                ),
            ),
            // A pin or an unpin (ISC-451). The reducer has already put the new pins in state for an open
            // conversation; the URL follows, and a stored one is told through `PUT …/context`. With no
            // conversation open, a pin starts a new one holding just it, and the create carries it.
            // `concatMap`: two quick presses are two replacements, and the second must land last.
            merge(events.on(chatEvents.contextPinned), events.on(chatEvents.contextRemoved)).pipe(
                concatMap(({type, payload}) => {
                    // A refused pin changed nothing, so there is nothing to write or to tell the server.
                    if (type === chatEvents.contextPinned.type && store.contextRefused()) return EMPTY;
                    const view = store.view();
                    const id = store.openId();
                    if (view === 'conversation' && id !== null) {
                        const context = store.contextItems();
                        go(id, formatChatCtx(context));
                        return api.setContext(id, context).pipe(
                            map((stored) => chatEvents.contextStored(stored)),
                            catchError((error: unknown) => of(chatEvents.contextFailed(serverMessage(error as {error?: unknown}, 'error.chatContext')))),
                        );
                    }
                    if (view === 'new') go('new', formatChatCtx(store.context()));
                    else if (type === chatEvents.contextPinned.type) go('new', formatChatCtx(withPins([], asPins(payload)) ?? []));
                    return EMPTY;
                }),
            ),
            events.on(chatEvents.listRequested).pipe(tap(() => go('list', null))),
            events.on(chatEvents.closeRequested).pipe(tap(() => go(null, null))),
            events.on(chatEvents.reopenRequested).pipe(tap(() => go(store.lastId() ?? 'new', null))),
            // `new` becomes the id it created, replacing the history entry so Back does not
            // lead to a second fresh conversation; `?chatCtx` stays as it is.
            events.on(chatEvents.created).pipe(
                filter(() => store.view() === 'new'),
                tap(({payload}) => go(payload.id, undefined, true)),
            ),
            // The open one deleted: a fresh conversation takes its place, not the list (ISC-448).
            events.on(chatEvents.deleted).pipe(
                filter(({payload}) => store.openId() === payload),
                tap(() => go('new', null)),
            ),

            // `?chat=list`, the rail asking beside an open conversation, or the search pausing (ISC-450): the
            // same read with the words the field holds, and `?chat` stays as it is. `switchMap`: a newer
            // read cancels an older one, so a slow answer never lands over a newer query's.
            merge(
                events.on(chatEvents.routed).pipe(filter(() => store.view() === 'list')),
                events.on(chatEvents.conversationsRequested),
                events.on(chatEvents.searchChanged).pipe(debounceTime(SEARCH_DEBOUNCE_MS)),
            ).pipe(
                switchMap(() => {
                    const query = store.query().trim();
                    return concat(
                        of(chatEvents.listQueried(query)),
                        api.list(query).pipe(
                            map((list) => chatEvents.listLoaded(list)),
                            catchError((error: unknown) => of(chatEvents.listFailed(serverMessage(error as {error?: unknown}, 'error.chatList')))),
                        ),
                    );
                }),
            ),

            // Renames go out one after another, in the order they were made.
            events.on(chatEvents.renameRequested).pipe(
                concatMap(({payload: {id, title}}) =>
                    api.rename(id, title.trim()).pipe(
                        map((conversation) => chatEvents.renamed(conversation)),
                        catchError((error: unknown) => of(chatEvents.renameFailed(serverMessage(error as {error?: unknown}, 'error.chatRename')))),
                    ),
                ),
            ),
            // `switchMap`: moving to another conversation cancels the read of the last one.
            events.on(chatEvents.routed).pipe(
                filter(() => store.view() === 'conversation' && store.loading()),
                switchMap(() => {
                    const id = store.openId() as number;
                    return api.get(id).pipe(
                        map((conversation) => chatEvents.conversationLoaded(conversation)),
                        catchError((error: unknown) =>
                            of(
                                status(error) === 404
                                    ? chatEvents.conversationMissing(id)
                                    : chatEvents.conversationFailed({
                                        id,
                                        message: serverMessage(error as {error?: unknown}, 'error.chatLoad'),
                                    }),
                            ),
                        ),
                    );
                }),
            ),

            events.on(chatEvents.capabilityRequested).pipe(
                exhaustMap(() =>
                    api.capability().pipe(
                        map((capability) => chatEvents.capabilityLoaded(capability.present)),
                        // Unreadable is absent: a button that opens onto an error is worse than none.
                        catchError(() => of(chatEvents.capabilityLoaded(false))),
                    ),
                ),
            ),

            // A conversation whose turn still streams has that turn stopped first (fix 3F-4): `deleted`
            // takes the turn off screen, but its stream stays open — and `streamOpen` with it — until
            // the server ends it, so the next question would wait on a model call nobody sees.
            events.on(chatEvents.deleteRequested).pipe(
                concatMap(({payload: id}) => {
                    const live = store.live();
                    const stop =
                        live?.state === 'STREAMING' && live.conversationId === id && live.turnId !== null
                            ? api.stop(id, live.turnId).pipe(
                                ignoreElements(),
                                catchError(() => EMPTY),
                            )
                            : EMPTY;
                    return concat(
                        stop,
                        api.delete(id).pipe(
                            map(() => chatEvents.deleted(id)),
                            catchError((error: unknown) => of(chatEvents.deleteFailed(serverMessage(error as {error?: unknown}, 'error.chatDelete')))),
                        ),
                    );
                }),
            ),

            // `exhaustMap` over both: a turn costs a model call, so nothing starts while one streams.
            merge(
                events.on(chatEvents.asked).pipe(
                    // The reducer refused it, so no turn is shown; nothing is created or asked either.
                    filter(({payload}) => store.canAsk() && questionFits(payload)),
                    map(({payload}) => ({question: payload, replaces: null})),
                ),
                events.on(chatEvents.regenerateRequested).pipe(map(({payload}) => ({question: null, replaces: payload}))),
            ).pipe(
                exhaustMap(({question, replaces}) => {
                    const open = store.conversation();
                    if (replaces !== null) {
                        return open === null || store.live()?.replacesTurnId !== replaces ? EMPTY : turn(api.regenerate(open.id, replaces));
                    }
                    if (open !== null) return turn(api.ask(open.id, question as string));
                    // The first question under `?chat=new`: the conversation exists from here on.
                    // It carries the pins `?chatCtx` holds, so the conversation is stored with them (ISC-446).
                    return api.create(store.context()).pipe(
                        switchMap((created) => concat(of(chatEvents.created(created)), turn(api.ask(created.id, question as string)))),
                        catchError((error: unknown) => of(chatEvents.streamFailed(serverMessage(error as {error?: unknown}, 'error.chatCreate')))),
                    );
                }),
            ),

            // The stream stays open: the server cancels the model call and ends it with `done {STOPPED}`.
            // Only the open conversation's turn: one streaming behind another conversation is not this drawer's to stop.
            events.on(chatEvents.stopRequested).pipe(
                exhaustMap(() => {
                    const live = store.liveTurn();
                    if (live?.state !== 'STREAMING' || live.turnId === null || live.conversationId === null) return EMPTY;
                    return api.stop(live.conversationId, live.turnId).pipe(
                        ignoreElements(),
                        catchError(() => EMPTY),
                    );
                }),
            ),
            // The Stop pressed before the turn had an id, sent the moment the `turn` event names it (fix 5F-3).
            // One `turn` event per turn, so one request; a turn that ended before it was named was never pending here.
            events.on(chatEvents.streamed).pipe(
                filter(({payload}) => payload.event === 'turn'),
                concatMap(() => {
                    const live = store.live();
                    if (!live?.stopPending || live.state !== 'STREAMING' || live.turnId === null || live.conversationId === null) return EMPTY;
                    return api.stop(live.conversationId, live.turnId).pipe(
                        ignoreElements(),
                        catchError(() => EMPTY),
                    );
                }),
            ),
        ];
    }),
);
