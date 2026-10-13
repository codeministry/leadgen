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
    /** The connection the model sits on (ISC-511); absent from an older server. */
    readonly connection?: ChatConnectionName | null;
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
    /** Input tokens the provider reported before the turn failed; absent when none did (ISC-518). */
    readonly inputTokens?: number;
    /** Output tokens, likewise. */
    readonly outputTokens?: number;
    /** What the failed turn cost so far in USD as a decimal string; absent while it has none (ISC-519). */
    readonly costUsd?: string;
    /** True when a billed turn's usage was not fully reported, so its tokens are a lower bound and it has no cost; absent otherwise, also on an unpriced model (spec 026). */
    readonly usageIncomplete?: boolean;
}

/** `done` — ends a turn that finished or was stopped. */
export interface ChatDone {
    readonly state: Extract<ChatTurnState, 'DONE' | 'STOPPED'>;
    /** Input tokens the provider reported over every model call of the turn; absent when none did (ISC-518). */
    readonly inputTokens?: number;
    /** Output tokens, likewise. */
    readonly outputTokens?: number;
    /** The turn's cost in USD as a decimal string; absent while it has none (ISC-519). */
    readonly costUsd?: string;
    /** True when a billed turn's usage was not fully reported, so its tokens are a lower bound and it has no cost; absent otherwise, also on an unpriced model (spec 026). */
    readonly usageIncomplete?: boolean;
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
    /** The connection that answered it; null for a turn stored before the second connection existed (ISC-511). */
    readonly connection?: ChatConnectionName | null;
    readonly createdAt: string;
    /** Why it ended incomplete, as its live `error` event said; null otherwise and for a turn stored before it was kept (ISC-473). */
    readonly endReason?: ChatErrorReason | null;
    /** When it ended; null while it streams. */
    readonly finishedAt?: string | null;
    /** Input tokens summed over the turn's model calls; absent for a turn stored before they were kept — no data, never zero (ISC-518). */
    readonly inputTokens?: number;
    /** Output tokens, likewise. */
    readonly outputTokens?: number;
    /** The turn's cost in USD as a decimal string; absent when unknown or before it was kept (ISC-519). */
    readonly costUsd?: string;
    /** True when a billed turn's usage was not fully reported, so its tokens are a lower bound and it has no cost; absent otherwise, also on an unpriced model (spec 026). */
    readonly usageIncomplete?: boolean;
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

/** Which configured connection a model sits on — never its base URL. */
export type ChatConnectionName = 'PRIMARY' | 'SECOND';

/**
 * The connection a turn was answered on (ISC-511). A turn stored before the select (V37) carries none,
 * and every turn then ran on the primary connection, so none reads as `PRIMARY`.
 */
export function answeredOn(turn: {readonly connection?: ChatConnectionName | null}): ChatConnectionName {
    return turn.connection ?? 'PRIMARY';
}

/**
 * What one model costs, in USD per million tokens — `ModelPrice` on the server. Both halves are decimal
 * strings ("3", "15.5"), never numbers, so no float rounds a fraction of a cent away on the way here.
 */
export interface ChatModelPrice {
    readonly input: string;
    readonly output: string;
}

/** One model a connection offers the chat — `ModelView` on the server. */
export interface ChatModel {
    /** The name as the connection reported it. */
    readonly name: string;
    /** Whether a turn without a pick takes it; only ever true on the primary connection. */
    readonly defaultModel: boolean;
    /**
     * Whether a call on it costs nothing: its connection is not billed (ISC-517). Absent from a server
     * older than the field, which reads as not known to be free.
     */
    readonly free?: boolean;
    /**
     * Its price on a billed connection the operator priced; null when it is free or unpriced, which a
     * billed connection shows as "price unknown" (ISC-517). Absent from a server older than the field.
     */
    readonly price?: ChatModelPrice | null;
}

/**
 * One configured connection and the models it reported that can call tools — `ConnectionView`.
 * One that did not answer is still listed: `reachable` false, its `reason`, and no models.
 */
export interface ChatConnection {
    readonly name: ChatConnectionName;
    /** The provider kind the connection speaks. */
    readonly kind: string;
    /** Whether a call on it is paid for. */
    readonly billed: boolean;
    readonly reachable: boolean;
    /** Why it is not reachable, or why its models cannot be picked today; null when they can. */
    readonly reason: string | null;
    readonly models: readonly ChatModel[];
}

/** `GET /api/v1/chat/models` — what the model select offers, the primary connection first (ISC-505). */
export interface ChatModels {
    readonly connections: readonly ChatConnection[];
    /**
     * The model a turn without a pick takes: the configured chat model on the primary connection, as
     * configured — listed or not. Null without a chat model; absent from a server older than the field.
     */
    readonly defaultModelName?: string | null;
    /**
     * The price of `defaultModelName` on the primary connection; null when it is unpriced or the primary
     * is not billed, absent from a server older than the field (spec 026).
     */
    readonly defaultModelPrice?: ChatModelPrice | null;
}

/**
 * The model a turn is asked on, named by its connection and its name (ISC-508, ISC-511). Sent with a
 * question or a regenerate as both halves or neither: neither is the default on the primary connection.
 */
export interface ChatModelPick {
    readonly connection: ChatConnectionName;
    readonly model: string;
}

/** `GET /api/v1/chat/status` — the chat's own state, shown on the empty chat's ring (ISC-476). */
export interface ChatStatus {
    readonly model: string;
    readonly callsUsed: number;
    readonly callsLimit: number;
    readonly toolRounds: number;
    /** Calls on the second connection today — counted against both ceilings; null without one (ISC-510). */
    readonly secondCalls?: number | null;
    /** The second connection's own daily ceiling; null without a second connection (ISC-510). */
    readonly secondLimit?: number | null;
    /** What the chat spent today and this month; absent on a server from before ISC-521. */
    readonly spend?: ChatSpend;
}

/**
 * One connection's spend (ISC-521): stored costs only, in USD as decimal strings, so no float rounds a
 * cent away; the turns a billed model without a price left out of each sum are counted instead.
 */
export interface ChatConnectionSpend {
    readonly name: ChatConnectionName;
    /** The sum today — the day the call ceilings count — e.g. `"0.012207"` or `"0"`. */
    readonly today: string;
    /** The sum this calendar month, in the same zone. */
    readonly month: string;
    /** Turns today that stored tokens but no cost, because their model has no price. */
    readonly unpricedToday: number;
    /** The same for the calendar month. */
    readonly unpricedMonth: number;
    /**
     * Turns today left out of the sum because the provider did not report their usage, or not all of it;
     * absent on a server that counted them as unpriced.
     */
    readonly unreportedToday?: number;
    /** The same for the calendar month. */
    readonly unreportedMonth?: number;
}

/** `ChatStatus.spend` (ISC-521): every configured connection, even at `"0"`, and the totals over all. */
export interface ChatSpend {
    readonly connections: readonly ChatConnectionSpend[];
    readonly today: string;
    readonly month: string;
}

/**
 * Whether the status says the second connection's day is spent (ISC-510): its calls have reached its own
 * ceiling. False while either number is unknown — without a status, or without a second connection.
 */
export function secondSpent(status: ChatStatus | null): boolean {
    const calls = status?.secondCalls;
    const limit = status?.secondLimit;
    return calls != null && limit != null && calls >= limit;
}

/** Why a connection's models cannot be picked now: a catalog key and what it interpolates. */
export interface ChatPickReason {
    readonly key: string;
    readonly params: Readonly<Record<string, string>>;
}

/**
 * Why a connection's models cannot be picked now, or null when they can (ISC-510, ISC-511): it did not
 * answer the catalog, the catalog named a reason, or — for the second connection — the status says its
 * day is spent. The one rule: the select disables by it, and the store drops a pick by it.
 */
export function unpickableReason(connection: ChatConnection, status: ChatStatus | null): ChatPickReason | null {
    const said = connection.reason?.trim() ?? '';
    if (!connection.reachable) return said === '' ? {key: 'chat.model.unreachableBare', params: {}} : {key: 'chat.model.unreachable', params: {reason: said}};
    if (said !== '') return {key: 'chat.model.unavailable', params: {reason: said}};
    if (connection.name === 'SECOND' && secondSpent(status)) return {key: 'chat.model.spent', params: {}};
    return null;
}

/**
 * Whether the catalog offers a choice of model, which is when the picker shows: more than one model in
 * all, counting the default as an option of its own where no listed model is flagged as it — that option
 * stands for a default the catalog lists nowhere. One model in all is no choice. Without a catalog, none.
 * The one counting rule for the picker and for the store, which asks for the status the picker shows.
 */
export function hasModelChoice(models: ChatModels | null): boolean {
    if (models === null) return false;
    const listed = models.connections.reduce((sum, c) => sum + c.models.length, 0);
    const flagged = models.connections.some((c) => c.models.some((m) => m.defaultModel));
    return listed + (flagged ? 0 : 1) > 1;
}
