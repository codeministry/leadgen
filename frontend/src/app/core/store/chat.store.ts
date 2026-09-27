import {HttpErrorResponse} from '@angular/common/http';
import {computed, inject} from '@angular/core';
import {NavigationEnd, Router} from '@angular/router';
import {signalStore, withComputed, withState} from '@ngrx/signals';
import {Events, on, withEventHandlers, withReducer} from '@ngrx/signals/events';
import {
    catchError,
    concat,
    concatMap,
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
    ChatError,
    ChatErrorReason,
    ChatEvent,
    ChatSource,
    ChatStep,
    ChatTurnState,
    ConversationSummary,
    conversationTitle,
    ConversationView,
    questionFits,
    TurnView,
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
    readonly sources: readonly ChatSource[];
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
    /** The open conversation as the server last sent it, plus the live turns folded into it. */
    conversation: ConversationView | null;
    loading: boolean;
    /** The offer a conversation started under `?chat=new` is pinned to. */
    pinnedOfferId: number | null;
    live: LiveTurn | null;
    /**
     * Whether a turn's stream is open, from the question the reducer accepts to the stream's last
     * event. The one notion of busy: the reducer gates on it, and the handler's `exhaustMap` is
     * busy for exactly as long, because a turn's stream ends at its `done` or `error`.
     */
    streamOpen: boolean;
    /** A catalog key or the server's sentence, for a list, a read or a delete that failed. */
    error: string | null;
}

const initialState: ChatState = {
    present: null,
    view: 'closed',
    openId: null,
    lastId: null,
    conversations: [],
    listLoading: false,
    conversation: null,
    loading: false,
    pinnedOfferId: null,
    live: null,
    streamOpen: false,
    error: null,
};

const ID = /^[1-9]\d*$/;

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
    withComputed(({live, conversation, view, loading, streamOpen}) => ({
        streaming: computed(() => live()?.state === 'STREAMING'),
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
        on(chatEvents.routed, ({payload}, state) => {
            if (payload === null) return {view: 'closed' as const, openId: null, pinnedOfferId: null};
            if (payload === 'list') return {view: 'list' as const, openId: null, pinnedOfferId: null, listLoading: true, error: null};
            if (payload === 'new') return {view: 'new' as const, openId: null, conversation: null, error: null};
            if (!ID.test(payload)) return {view: 'missing' as const, openId: null, pinnedOfferId: null};
            const id = Number(payload);
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
                pinnedOfferId: null,
                conversation: held ? state.conversation : null,
                loading: !held,
                error: null,
                ...(orphan ? {live: null} : {}),
            };
        }),
        on(chatEvents.newRequested, ({payload}) => ({pinnedOfferId: payload.pinnedOfferId})),
        on(chatEvents.capabilityLoaded, ({payload}) => ({present: payload})),
        on(chatEvents.listLoaded, ({payload}) => ({conversations: payload, listLoading: false})),
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
                [{id: payload.id, title: payload.title, updatedAt: payload.updatedAt}, ...state.conversations.filter((c) => c.id !== payload.id)],
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

        const chatParam = (): string | null => router.routerState.snapshot.root.queryParamMap.get('chat');

        /** Writes `?chat` on whatever screen is showing, leaving the path and every other parameter as they are. */
        const go = (chat: string | number | null, replaceUrl = false): void => {
            void router.navigate([], {
                queryParams: {chat},
                queryParamsHandling: 'merge',
                preserveFragment: true,
                replaceUrl,
            });
        };

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
            // The URL, read once at start and again after every navigation.
            router.events.pipe(
                filter((event) => event instanceof NavigationEnd),
                map(chatParam),
                startWith(chatParam()),
                distinctUntilChanged(),
                map((chat) => chatEvents.routed(chat)),
            ),

            // The intents write the URL and nothing else; `routed` follows from it.
            events.on(chatEvents.openRequested).pipe(tap(({payload}) => go(payload))),
            events.on(chatEvents.newRequested).pipe(tap(() => go('new'))),
            events.on(chatEvents.listRequested).pipe(tap(() => go('list'))),
            events.on(chatEvents.closeRequested).pipe(tap(() => go(null))),
            events.on(chatEvents.reopenRequested).pipe(tap(() => go(store.lastId() ?? 'new'))),
            // `new` becomes the id it created, replacing the history entry so Back does not
            // lead to a second fresh conversation.
            events.on(chatEvents.created).pipe(
                filter(() => store.view() === 'new'),
                tap(({payload}) => go(payload.id, true)),
            ),
            events.on(chatEvents.deleted).pipe(
                filter(({payload}) => store.openId() === payload),
                tap(() => go('list')),
            ),

            // `?chat=list`, or the rail asking beside an open conversation: the same read, and `?chat` stays as it is.
            merge(events.on(chatEvents.routed).pipe(filter(() => store.view() === 'list')), events.on(chatEvents.conversationsRequested)).pipe(
                switchMap(() =>
                    api.list().pipe(
                        map((list) => chatEvents.listLoaded(list)),
                        catchError((error: unknown) => of(chatEvents.listFailed(serverMessage(error as {error?: unknown}, 'error.chatList')))),
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
                    return api.create(store.pinnedOfferId()).pipe(
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
