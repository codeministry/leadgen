import {Observable} from 'rxjs';

import type {ChatEvent, ChatFollowUp, ChatModels, ChatStatus, ChatSuggestion, TurnView} from '../model/chat';

/**
 * What `GET /suggestions` answers for an empty chat once the server does (ISC-455): at most four,
 * each with the trigger that raised it. Neutral content, like the recorded turn below.
 */
export const RECORDED_SUGGESTIONS: readonly ChatSuggestion[] = [
    {trigger: 'NEW_THIS_WEEK', text: 'What came in this week?', count: 12},
    {trigger: 'DEADLINE_SOON', text: 'Which offers close before Friday?', count: 3},
    {trigger: 'NO_ANSWER', text: 'Which applications have had no answer for two weeks?', count: 2},
];

/**
 * What `GET /status` answers on an instance with a chat model (ISC-476) and a second connection
 * (ISC-510), with its spend (ISC-521): the primary free at "0", the second priced: one of its turns
 * this month on a model nobody priced, and two whose usage was not (fully) reported, one of them today.
 * Amounts are decimal strings, as the server sends them.
 */
export const RECORDED_STATUS: ChatStatus = {
    model: 'chat-model',
    callsUsed: 12,
    callsLimit: 200,
    toolRounds: 6,
    secondCalls: 4,
    secondLimit: 50,
    spend: {
        connections: [
            {name: 'PRIMARY', today: '0', month: '0', unpricedToday: 0, unpricedMonth: 0, unreportedToday: 0, unreportedMonth: 0},
            {name: 'SECOND', today: '0.012207', month: '0.348', unpricedToday: 0, unpricedMonth: 1, unreportedToday: 1, unreportedMonth: 2},
        ],
        today: '0.012207',
        month: '0.348',
    },
};

/**
 * What `GET /models` answers on an instance with an unbilled and a billed connection (ISC-505,
 * ISC-517): on the first two free models that can call tools, the first of them the default; on the
 * second one model priced per million tokens, as decimal strings, and one the operator has not priced.
 * Neutral names and prices, no provider and no vendor.
 */
export const RECORDED_MODELS: ChatModels = {
    connections: [
        {
            name: 'PRIMARY',
            kind: 'local',
            billed: false,
            reachable: true,
            reason: null,
            models: [
                {name: 'model-a', defaultModel: true, free: true, price: null},
                {name: 'model-b', defaultModel: false, free: true, price: null},
            ],
        },
        {
            name: 'SECOND',
            kind: 'hosted',
            billed: true,
            reachable: true,
            reason: null,
            models: [
                {name: 'model-x', defaultModel: false, free: false, price: {input: '3', output: '15.5'}},
                {name: 'model-y', defaultModel: false, free: false, price: null},
            ],
        },
    ],
    defaultModelName: 'model-a',
    // The default runs on the unbilled primary, so it has no price to name.
    defaultModelPrice: null,
};

/** What `GET …/followups` answers under a finished turn (ISC-457): two or three questions. */
export const RECORDED_FOLLOWUPS: readonly ChatFollowUp[] = [{text: 'Only the remote ones?'}, {text: 'Which of them pay the most?'}];

/**
 * A turn recorded once and replayed, for specs and for building the drawer before the server
 * streams. It speaks exactly the wire contract in `core/model/chat.ts`, so a component that
 * renders this renders the real thing.
 *
 * Each event waits `delayMs` after the previous one; `0` delivers everything in one task,
 * which is what a spec that is not about timing wants.
 */
export function replayTurn(events: readonly ChatEvent[], delayMs = 0): Observable<ChatEvent> {
    return new Observable<ChatEvent>(subscriber => {
        let index = 0;
        let timer: ReturnType<typeof setTimeout> | undefined;
        const next = (): void => {
            if (index >= events.length) {
                subscriber.complete();
                return;
            }
            subscriber.next(events[index++]);
            timer = setTimeout(next, delayMs);
        };
        timer = setTimeout(next, delayMs);
        return () => clearTimeout(timer);
    });
}

/**
 * A plausible finished turn: two tool steps, a streamed answer with two verified citations and
 * one unverified id, and its sources. Neutral content, no portal and no person.
 */
export const RECORDED_TURN: readonly ChatEvent[] = [
    {event: 'turn', data: {turnId: 1}},
    {event: 'step', data: {ordinal: 1, tool: 'searchOffers', label: 'Searched offers · Spring · remote', state: 'RUNNING', count: null, durationMs: null}},
    {event: 'step', data: {ordinal: 1, tool: 'searchOffers', label: 'Searched offers · Spring · remote', state: 'DONE', count: 14, durationMs: 412}},
    {event: 'step', data: {ordinal: 2, tool: 'semanticSearch', label: 'Read the closest adverts', state: 'RUNNING', count: null, durationMs: null}},
    {event: 'step', data: {ordinal: 2, tool: 'semanticSearch', label: 'Read the closest adverts', state: 'DONE', count: 27, durationMs: 690}},
    {event: 'text', data: {delta: 'Fourteen remote Spring offers came in last month. '}},
    {event: 'text', data: {delta: 'The strongest is a Kotlin backend role [1](cite:offer/2291), '}},
    {event: 'text', data: {delta: 'then a payments platform [2](cite:offer/2304). '}},
    {event: 'text', data: {delta: 'One id from the newsletter, ⟨unverified:4471⟩, came back from no search.'}},
    {
        event: 'sources',
        data: {
            sources: [
                {n: 1, kind: 'OFFER', id: 2291, title: 'Senior Backend Engineer (Spring Boot, Kotlin)', source: 'Mailing list', date: '2026-08-04', archived: false},
                {n: 2, kind: 'OFFER', id: 2304, title: 'Java/Spring Developer for a payments platform', source: 'Job board', date: '2026-08-11', archived: true},
            ],
        },
    },
    // On the free primary model: its tokens with a cost of "0", the only form the server sends for a free turn.
    {event: 'done', data: {state: 'DONE', inputTokens: 1840, outputTokens: 212, costUsd: '0'}},
];

/**
 * Two stored turns as a reload shows them (ISC-518): one on the billed connection with its tokens and
 * its cost, one stored before tokens were kept, which carries none of the three fields. Neutral
 * content, as above.
 */
export const RECORDED_TURNS: readonly TurnView[] = [
    {
        id: 1,
        question: 'Which remote Spring offers came in?',
        answer: 'Fourteen, the strongest a Kotlin backend role.',
        state: 'DONE',
        steps: [],
        sources: [],
        replacesTurnId: null,
        model: 'model-x',
        connection: 'SECOND',
        createdAt: '2026-10-13T09:00:00Z',
        endReason: null,
        finishedAt: '2026-10-13T09:00:07Z',
        inputTokens: 1840,
        outputTokens: 212,
        // 1840 × 3 + 212 × 15.5 per million, model-x's price in RECORDED_MODELS; the wire form is
        // the plain decimal without trailing zeros, as the server writes it (ISC-519).
        costUsd: '0.008806',
    },
    {
        id: 2,
        question: 'And last month?',
        answer: 'Eleven.',
        state: 'DONE',
        steps: [],
        sources: [],
        replacesTurnId: null,
        model: 'model-a',
        connection: null,
        createdAt: '2026-09-01T09:00:00Z',
        endReason: null,
        finishedAt: '2026-09-01T09:00:04Z',
    },
];
