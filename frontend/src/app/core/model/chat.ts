/**
 * The chat's wire contract, mirroring the records in `de.codeministry.leadgen.chat`.
 *
 * A turn is a stream of server-sent events whose names are the keys of {@link ChatEventMap}.
 * The order is fixed on the server: `turn` once and first, then `step` and `text` interleaved,
 * then either `sources` followed by `done`, or `error` alone. A stream that ends any other way
 * was cut, and the store treats it as an error.
 */

export type ChatStepState = 'RUNNING' | 'DONE';
export type ChatTurnState = 'STREAMING' | 'DONE' | 'INCOMPLETE' | 'STOPPED';
export type ChatSourceKind = 'OFFER' | 'APPLICATION';
export type ChatErrorReason = 'MODEL' | 'BUDGET' | 'ROUNDS';

/** The longest question the server takes: its `NewTurn` is `@Size(max = 4000)`. */
export const QUESTION_MAX_LENGTH = 4000;

/** Whether the server would take this question at all: not blank, not over the limit. */
export function questionFits(question: string): boolean {
    return question.trim() !== '' && question.length <= QUESTION_MAX_LENGTH;
}

/** How long a conversation's title runs before it is cut: `ConversationRepository.TITLE_LENGTH`. */
export const TITLE_LENGTH = 80;

/**
 * A conversation's title from its first question, the way the server writes it in `startTurn`
 * (`ConversationRepository.title`): whitespace collapsed, and a long one cut at the last space
 * before the limit — or at the limit when that space falls in the first half — with an ellipsis.
 * The drawer names a conversation it just created with it, because the server's title only
 * exists once the turn started and the drawer does not read the conversation again.
 */
export function conversationTitle(question: string): string {
    // Java's `\s` is ASCII whitespace only; `strip()` runs first, as there.
    const line = question.trim().replace(/[ \t\n\v\f\r]+/g, ' ');
    if (line.length <= TITLE_LENGTH) return line;
    const cut = line.lastIndexOf(' ', TITLE_LENGTH);
    return `${line.slice(0, cut > TITLE_LENGTH / 2 ? cut : TITLE_LENGTH)}…`;
}

/** `turn` — the id the stop and regenerate calls address. */
export interface ChatTurnStarted {
    readonly turnId: number;
}

/** `step` — a tool call, sent once when it starts and once when it ends, under one ordinal. */
export interface ChatStep {
    readonly ordinal: number;
    readonly tool: string;
    readonly label: string;
    readonly state: ChatStepState;
    readonly count: number | null;
    readonly durationMs: number | null;
}

/**
 * `text` — the next piece of the answer, as Markdown.
 *
 * Citations arrive resolved: `[n](cite:offer/ID)` or `[n](cite:application/ID)` for one the
 * turn's tools returned, `⟨unverified:ID⟩` for one they did not. The browser renders what it
 * is given and decides nothing about grounding.
 */
export interface ChatText {
    readonly delta: string;
}

/** One row an answer rests on. */
export interface ChatSource {
    readonly n: number;
    readonly kind: ChatSourceKind;
    readonly id: number;
    readonly title: string;
    readonly source: string;
    /** ISO date. */
    readonly date: string | null;
    readonly archived: boolean;
}

/** `sources` — exactly once, after the last text and before `done`. */
export interface ChatSources {
    readonly sources: readonly ChatSource[];
}

/** `error` — ends a turn that could not finish; its partial answer is kept. */
export interface ChatError {
    readonly reason: ChatErrorReason;
    readonly message: string;
}

/** `done` — ends a turn that finished or was stopped. */
export interface ChatDone {
    readonly state: Extract<ChatTurnState, 'DONE' | 'STOPPED'>;
}

export interface ChatEventMap {
    readonly turn: ChatTurnStarted;
    readonly step: ChatStep;
    readonly text: ChatText;
    readonly sources: ChatSources;
    readonly error: ChatError;
    readonly done: ChatDone;
}

/** One event as the stream delivers it: its name and its parsed data, kept together. */
export type ChatEvent = {
    [K in keyof ChatEventMap]: { readonly event: K; readonly data: ChatEventMap[K] };
}[keyof ChatEventMap];

/** One line of the conversation list, newest first. */
export interface ConversationSummary {
    readonly id: number;
    readonly title: string;
    readonly updatedAt: string;
}

/** One stored turn, as a reload shows it. */
export interface TurnView {
    readonly id: number;
    readonly question: string;
    readonly answer: string;
    readonly state: ChatTurnState;
    readonly steps: readonly ChatStep[];
    readonly sources: readonly ChatSource[];
    readonly replacesTurnId: number | null;
    readonly model: string | null;
    readonly createdAt: string;
}

/** A whole conversation, as the drawer opens it. */
export interface ConversationView {
    readonly id: number;
    readonly title: string;
    readonly pinnedOfferId: number | null;
    readonly turns: readonly TurnView[];
    readonly updatedAt: string;
}

/** `GET /api/v1/chat/capability` — false means the header draws no button. */
export interface ChatCapability {
    readonly present: boolean;
}
