import {ChatStep, ChatTurnState} from '@core/model/chat';
import {LivingMarkFrame} from '@shared/living-mark/living-mark';

/**
 * The living mark's frame for one turn (ISC-465), for a turn streaming now and one loaded from the
 * server alike, so a reload shows what the stream ended on.
 *
 * <p>A running step wins over `STREAMING` only: a turn cut or stopped mid-call is stored with that
 * step still `RUNNING`, and once the turn has ended nothing is working any more.
 */
export function turnFrame(state: ChatTurnState, steps: readonly ChatStep[]): LivingMarkFrame {
    switch (state) {
        case 'STREAMING':
            return steps.some((step) => step.state === 'RUNNING') ? 'working' : 'speaking';
        case 'DONE':
            return 'rest';
        case 'INCOMPLETE':
        case 'STOPPED':
            return 'halted';
    }
}
