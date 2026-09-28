import {Observable} from 'rxjs';

import type {ChatEvent, ChatFollowUp, ChatStatus, ChatSuggestion} from '../model/chat';

/**
 * What `GET /suggestions` answers for an empty chat once the server does (ISC-455): at most four,
 * each with the trigger that raised it. Neutral content, like the recorded turn below.
 */
export const RECORDED_SUGGESTIONS: readonly ChatSuggestion[] = [
    {trigger: 'NEW_THIS_WEEK', text: 'What came in this week?', count: 12},
    {trigger: 'DEADLINE_SOON', text: 'Which offers close before Friday?', count: 3},
    {trigger: 'NO_ANSWER', text: 'Which applications have had no answer for two weeks?', count: 2},
];

/** What `GET …/followups` answers under a finished turn (ISC-457): two or three questions. */
/** What `GET /status` answers on an instance with a chat model (ISC-476). */
export const RECORDED_STATUS: ChatStatus = {model: 'chat-model', callsUsed: 12, callsLimit: 200, toolRounds: 6};

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
    {event: 'done', data: {state: 'DONE'}},
];
