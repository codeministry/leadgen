import {cachedTurnStatus, TurnLike} from './turn-status';

/**
 * The ring beside every answer takes its status as an input. Built afresh on each change detection,
 * every ring in a long thread re-translated its label and rebuilt its number format on each streamed
 * chunk; an unchanged turn now keeps its status object.
 */
describe('cachedTurnStatus', () => {
    const turn: TurnLike = {state: 'DONE', steps: [], model: 'chat-model', createdAt: '2026-09-28T10:00:00Z', finishedAt: '2026-09-28T10:00:04Z'};

    it('answers the same object for the same turn', () => {
        expect(cachedTurnStatus(turn)).toBe(cachedTurnStatus(turn));
    });

    it('answers anew for a turn that changed, which is a new object in the store', () => {
        const next: TurnLike = {...turn, state: 'STOPPED'};
        expect(cachedTurnStatus(next)).not.toBe(cachedTurnStatus(turn));
        expect(cachedTurnStatus(next).kind).toBe('stopped');
    });
});
