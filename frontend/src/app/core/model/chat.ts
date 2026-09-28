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

/** The longest title a rename stores (ISC-449): `PATCH …/conversations/{id}` cuts there. */
export const TITLE_MAX = 120;

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

/** `turn` — the id the stop and regenerate calls address, and the model answering it. */
export interface ChatTurnStarted {
    readonly turnId: number;
    /** Absent from an older server; the live turn then shows no model until it is reloaded. */
    readonly model?: string | null;
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

/** One headline number of a `statistics` call, and its value in the comparison window when there is one. */
export interface ChatStatisticsRow {
    /** The metric's name, as the server writes it. */
    readonly label: string;
    readonly value: number;
    readonly compareValue: number | null;
    /** `value - compareValue`, computed on the server; null without a comparison window. */
    readonly delta: number | null;
}

/** One day of intake in the window. */
export interface ChatStatisticsDay {
    /** ISO date. */
    readonly day: string;
    readonly count: number;
}

/**
 * A `statistics` tool call's result (ISC-460), one per call of the turn. The card draws its numbers
 * from these rows and this series, never from digits in the answer's text.
 */
export interface ChatStatisticsSource {
    readonly kind: 'STATISTICS';
    /** The tool call's ordinal within the turn. */
    readonly ordinal: number;
    /** The window's first and last day, ISO dates. */
    readonly from: string;
    readonly to: string;
    readonly compareFrom: string | null;
    readonly compareTo: string | null;
    /** At most eight, picked by the server. */
    readonly rows: readonly ChatStatisticsRow[];
    readonly series: readonly ChatStatisticsDay[];
}

/** Anything a turn's `sources` event carries: rows it cites, and the statistics it drew on. */
export type ChatTurnSource = ChatSource | ChatStatisticsSource;

export function isStatistics(source: ChatTurnSource): source is ChatStatisticsSource {
    return source.kind === 'STATISTICS';
}

/** `sources` — exactly once, after the last text and before `done`. */
export interface ChatSources {
    readonly sources: readonly ChatTurnSource[];
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
    /** The last question or answer in it; the list groups by this (ISC-447). */
    readonly lastActivityAt: string;
    /** What the conversation is pinned to, once the server sends it (ISC-451). */
    readonly context?: readonly ChatContextItem[];
}

export type ChatContextKind = 'OFFER' | 'SHORTLIST_VIEW' | 'ANALYTICS_WINDOW';

/** One pin of a conversation's context — `ChatContextItem` on the server; only the fields of its kind are set. */
export interface ChatContextItem {
    readonly kind: ChatContextKind;
    readonly offerId?: number | null;
    /** A shortlist view's query string. */
    readonly query?: string | null;
    /** An analytics window's first and last day, ISO dates. */
    readonly from?: string | null;
    readonly to?: string | null;
}

/** `GET /api/v1/chat/suggestions` — one question the data suggests, and the trigger that raised it. */
export interface ChatSuggestion {
    readonly trigger: string;
    readonly text: string;
    readonly count: number | null;
}

/** `GET …/turns/{turnId}/followups` — one question to ask next. */
export interface ChatFollowUp {
    readonly text: string;
}

/** What `suggestions` is asked for: a stored conversation, or a context not stored yet. */
export type SuggestionsFor = {readonly conversationId: number} | {readonly context: readonly ChatContextItem[]};

const OFFER_PIN = /^o:([1-9]\d*)$/;
const WINDOW_PIN = /^w:(\d{4}-\d{2}-\d{2})\.\.(\d{4}-\d{2}-\d{2})$/;

/**
 * `?chatCtx` as context items (ISC-446, ISC-451): a comma-separated list of `o:<offerId>` for an
 * offer, `v:<encodeURIComponent(query)>` for a shortlist view and `w:<from>..<to>` for an
 * analytics window. The view's query is encoded, so its own `&` and `,` never split the list;
 * anything else is skipped rather than guessed at.
 */
export function parseChatCtx(value: string | null): readonly ChatContextItem[] {
    if (value === null || value === '') return [];
    return value.split(',').flatMap((raw): ChatContextItem[] => {
        const entry = raw.trim();
        const offer = OFFER_PIN.exec(entry);
        if (offer) return [{kind: 'OFFER', offerId: Number(offer[1])}];
        const window = WINDOW_PIN.exec(entry);
        if (window) return [{kind: 'ANALYTICS_WINDOW', from: window[1], to: window[2]}];
        if (!entry.startsWith('v:')) return [];
        try {
            return [{kind: 'SHORTLIST_VIEW', query: decodeURIComponent(entry.slice(2))}];
        } catch {
            // A malformed escape is a hand-edited URL, skipped like any other stray entry.
            return [];
        }
    });
}

/** One item in `?chatCtx` form, or null for one that cannot be written. */
function formatItem(item: ChatContextItem): string | null {
    if (item.kind === 'OFFER') return item.offerId == null ? null : `o:${item.offerId}`;
    if (item.kind === 'SHORTLIST_VIEW') return `v:${encodeURIComponent(item.query ?? '')}`;
    return item.from == null || item.to == null ? null : `w:${item.from}..${item.to}`;
}

/** Context items as `?chatCtx`, or null for none, which takes the parameter out of the URL. */
export function formatChatCtx(items: readonly ChatContextItem[]): string | null {
    const entries = items.map(formatItem).filter((entry) => entry !== null);
    return entries.length === 0 ? null : entries.join(',');
}

/** Whether two items pin the same thing: one chip per offer, view or window. */
export function sameContext(a: ChatContextItem, b: ChatContextItem): boolean {
    return formatItem(a) === formatItem(b);
}

/** A shortlist query without the chat's own parameters: the view a "Use this view" pins. */
export function viewQuery(query: string): string {
    const params = new URLSearchParams(query);
    params.delete('chat');
    params.delete('chatCtx');
    return params.toString();
}

/** How many filters a pinned shortlist query sets: one per parameter name. */
export function viewFilterCount(query: string): number {
    return new Set(new URLSearchParams(query).keys()).size;
}

/** How many offers one conversation holds pinned; the server refuses an eleventh as well (ISC-453). */
export const MAX_PINNED_OFFERS = 10;

/** How many of the items pin an offer. */
export function pinnedOfferCount(items: readonly ChatContextItem[]): number {
    return items.filter((item) => item.kind === 'OFFER').length;
}

/**
 * The pins after `adding` joined `current` — one chip per offer, view or window, so a pin already
 * held changes nothing — or null when the offers would pass {@link MAX_PINNED_OFFERS} (ISC-453).
 */
export function withPins(current: readonly ChatContextItem[], adding: readonly ChatContextItem[]): readonly ChatContextItem[] | null {
    const next = adding.reduce<readonly ChatContextItem[]>(
        (held, item) => (held.some((other) => sameContext(other, item)) ? held : [...held, item]),
        current,
    );
    return pinnedOfferCount(next) > MAX_PINNED_OFFERS ? null : next;
}

/** The first pinned offer, or null. */
export function pinnedOffer(items: readonly ChatContextItem[]): number | null {
    return items.find((item) => item.kind === 'OFFER' && item.offerId != null)?.offerId ?? null;
}

/** One stored turn, as a reload shows it. */
export interface TurnView {
    readonly id: number;
    readonly question: string;
    readonly answer: string;
    readonly state: ChatTurnState;
    readonly steps: readonly ChatStep[];
    readonly sources: readonly ChatTurnSource[];
    readonly replacesTurnId: number | null;
    readonly model: string | null;
    readonly createdAt: string;
    /** Why it ended incomplete, as its live `error` event said; null otherwise and for a turn stored before it was kept (ISC-473). */
    readonly endReason?: ChatErrorReason | null;
    /** When it ended; null while it streams. */
    readonly finishedAt?: string | null;
}

/** A whole conversation, as the drawer opens it. */
export interface ConversationView {
    readonly id: number;
    readonly title: string;
    readonly pinnedOfferId: number | null;
    /** The stored context, once the server sends it (ISC-451); until then `pinnedOfferId` is the pin. */
    readonly context?: readonly ChatContextItem[];
    readonly turns: readonly TurnView[];
    readonly updatedAt: string;
}

/** `GET /api/v1/chat/capability` — false means the header draws no button. */
export interface ChatCapability {
    readonly present: boolean;
}

/** `POST /api/v1/chat/conversations/bulk-delete`'s answer: the named conversations that existed (ISC-477). */
export interface BulkDeleted {
    readonly deleted: readonly number[];
}

/** `GET /api/v1/chat/status` — the chat's own state, shown on the empty chat's ring (ISC-476). */
export interface ChatStatus {
    readonly model: string;
    readonly callsUsed: number;
    readonly callsLimit: number;
    readonly toolRounds: number;
}
