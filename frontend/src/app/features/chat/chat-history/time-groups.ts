import {ConversationSummary} from '@core/model/chat';

/** The five time groups of the conversation list (ISC-447), in the order they are drawn. */
export type TimeGroupKey = 'today' | 'yesterday' | 'week' | 'month' | 'older';

export interface TimeGroup {
    readonly key: TimeGroupKey;
    readonly rows: readonly ConversationSummary[];
}

/**
 * When a conversation last moved: `lastActivityAt`, or `updatedAt` from a summary that predates it —
 * a stub or a cached answer. Never an Invalid Date, which `Intl` refuses with a RangeError mid-render.
 */
export function activityOf(conversation: Pick<ConversationSummary, 'lastActivityAt' | 'updatedAt'>): string {
    const at = conversation.lastActivityAt as string | undefined;
    return at !== undefined && !Number.isNaN(Date.parse(at)) ? at : conversation.updatedAt;
}

const ORDER: readonly TimeGroupKey[] = ['today', 'yesterday', 'week', 'month', 'older'];
const DAY_MS = 86_400_000;

/** The calendar day an instant falls on in `zone`, as whole days since the epoch — so two days subtract. */
function dayIn(instant: Date, zone: string): number {
    if (Number.isNaN(instant.getTime())) return Number.NEGATIVE_INFINITY;
    const parts = new Intl.DateTimeFormat('en-CA', {timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit'}).formatToParts(instant);
    const part = (type: string) => Number(parts.find((p) => p.type === type)?.value);
    return Date.UTC(part('year'), part('month') - 1, part('day')) / DAY_MS;
}

/**
 * Which group a day belongs to, seen from `today`. Calendar words, read as calendars read them:
 * "this week" began on Monday (ISO), "this month" on the 1st. A yesterday that was last week's
 * Sunday is still yesterday; an instant ahead of the clock is today.
 */
function groupOf(day: number, today: number): TimeGroupKey {
    const back = today - day;
    if (back <= 0) return 'today';
    if (back === 1) return 'yesterday';
    const sinceMonday = (new Date(today * DAY_MS).getUTCDay() + 6) % 7;
    if (back <= sinceMonday) return 'week';
    const now = new Date(today * DAY_MS);
    const then = new Date(day * DAY_MS);
    return now.getUTCFullYear() === then.getUTCFullYear() && now.getUTCMonth() === then.getUTCMonth() ? 'month' : 'older';
}

/**
 * The list by last activity, in `zone` (the browser's own in the component): the five groups in
 * order, newest first inside each, and no group at all where nothing fell. The server already
 * orders newest first; sorting again here keeps the rule true of any input rather than of one.
 */
export function groupByActivity(conversations: readonly ConversationSummary[], now: Date, zone: string): readonly TimeGroup[] {
    const today = dayIn(now, zone);
    const buckets = new Map<TimeGroupKey, ConversationSummary[]>();
    const newestFirst = [...conversations].sort((a, b) => Date.parse(activityOf(b)) - Date.parse(activityOf(a)));
    for (const conversation of newestFirst) {
        const key = groupOf(dayIn(new Date(activityOf(conversation)), zone), today);
        buckets.set(key, [...(buckets.get(key) ?? []), conversation]);
    }
    return ORDER.filter((key) => buckets.has(key)).map((key) => ({key, rows: buckets.get(key) ?? []}));
}

/**
 * The time beside a row: the clock for today and yesterday, the short weekday for this week, the
 * date for anything older — in the reader's language and zone.
 */
export function activityLabel(instant: string, group: TimeGroupKey, lang: string, zone: string): string {
    const options: Intl.DateTimeFormatOptions =
        group === 'today' || group === 'yesterday'
            ? {hour: '2-digit', minute: '2-digit', hourCycle: 'h23'}
            : group === 'week'
                ? {weekday: 'short'}
                : {day: 'numeric', month: 'short', ...(group === 'older' ? {year: 'numeric'} : {})};
    const date = new Date(instant);
    return Number.isNaN(date.getTime()) ? '' : new Intl.DateTimeFormat(lang, {...options, timeZone: zone}).format(date);
}
