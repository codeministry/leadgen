import {ChatStep, ChatStepState} from '@core/model/chat';
import {turnFrame} from './turn-frame';

function step(ordinal: number, state: ChatStepState): ChatStep {
    return {ordinal, tool: 'searchOffers', label: `Step ${ordinal}`, state, count: null, durationMs: null};
}

describe('turnFrame (ISC-465)', () => {
    it('is working while a step runs, even as the turn streams', () => {
        expect(turnFrame('STREAMING', [step(1, 'DONE'), step(2, 'RUNNING')])).toBe('working');
    });

    it('is speaking while the turn streams with no step running', () => {
        expect(turnFrame('STREAMING', [])).toBe('speaking');
        expect(turnFrame('STREAMING', [step(1, 'DONE'), step(2, 'DONE')])).toBe('speaking');
    });

    it('rests once the turn is done', () => {
        expect(turnFrame('DONE', [step(1, 'DONE')])).toBe('rest');
    });

    it('halts a turn that ended incomplete', () => {
        expect(turnFrame('INCOMPLETE', [step(1, 'DONE')])).toBe('halted');
    });

    it('halts a turn that was stopped', () => {
        expect(turnFrame('STOPPED', [])).toBe('halted');
    });

    // A turn cut or stopped mid-call is stored with that step still `RUNNING`; nothing runs any more.
    it('lets an ended turn win over a step it left running', () => {
        expect(turnFrame('INCOMPLETE', [step(1, 'RUNNING')])).toBe('halted');
        expect(turnFrame('STOPPED', [step(1, 'RUNNING')])).toBe('halted');
        expect(turnFrame('DONE', [step(1, 'RUNNING')])).toBe('rest');
    });
});
