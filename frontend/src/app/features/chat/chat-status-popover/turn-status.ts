import {ChatErrorReason, ChatStep, ChatTurnState} from '@core/model/chat';

/** The popover's first line: what happened to the turn, in one of five words (ISC-474). */
export type TurnStatusKind = 'done' | 'working' | 'writing' | 'incomplete' | 'stopped';

/** What the status popover says about one turn, read the same off a live turn and a stored one. */
export interface TurnStatus {
    readonly kind: TurnStatusKind;
    /** Why it ended incomplete; null for every other kind, and for an incomplete turn that kept none. */
    readonly reason: ChatErrorReason | null;
    /** The tool calls it made, one per ordinal. */
    readonly steps: number;
    /** From the question to the end, where both are known. */
    readonly durationMs: number | null;
    readonly model: string | null;
}

/** The fields both shapes carry, a stored turn (`TurnView`) and the one streaming now (`LiveTurn`). */
export interface TurnLike {
    readonly state: ChatTurnState;
    readonly steps: readonly ChatStep[];
    readonly model?: string | null;
    readonly createdAt?: string;
    readonly startedAt?: string;
    readonly finishedAt?: string | null;
    readonly endReason?: ChatErrorReason | null;
    readonly error?: {readonly reason: ChatErrorReason} | null;
}

/**
 * One turn's status. A running step wins over `STREAMING`, as in the mark's frame (ISC-465); the
 * reason is the stored one, else the live `error` event's.
 */
export function turnStatus(turn: TurnLike): TurnStatus {
    const kind: TurnStatusKind =
        turn.state === 'STREAMING'
            ? turn.steps.some((step) => step.state === 'RUNNING')
                ? 'working'
                : 'writing'
            : turn.state === 'DONE'
              ? 'done'
              : turn.state === 'STOPPED'
                ? 'stopped'
                : 'incomplete';
    const start = turn.createdAt ?? turn.startedAt ?? null;
    const end = turn.finishedAt ?? null;
    const duration = start !== null && end !== null ? Date.parse(end) - Date.parse(start) : NaN;
    return {
        kind,
        reason: kind === 'incomplete' ? (turn.endReason ?? turn.error?.reason ?? null) : null,
        steps: new Set(turn.steps.map((step) => step.ordinal)).size,
        durationMs: Number.isFinite(duration) && duration >= 0 ? duration : null,
        model: turn.model ?? null,
    };
}
