import {Observable} from 'rxjs';

import type {ChatEvent} from '../model/chat';

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
