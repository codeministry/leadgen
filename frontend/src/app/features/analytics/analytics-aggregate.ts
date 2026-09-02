import { IntakeDay, IntakeSeries } from '@core/model/analytics';

/** Which date the series is read along. Both answer a different question. */
export type TimeAxis = 'published' | 'received' | 'ingested';

/** How wide a bar is. The server sends days; anything wider is summed here. */
export type Granularity = 'day' | 'week' | 'month';

/** One bar: a bucket start and the counts that fall inside it. */
export interface IntakeBucket extends IntakeDay {
  readonly days: number;
}

export function isTimeAxis(value: unknown): value is TimeAxis {
  return value === 'published' || value === 'received' || value === 'ingested';
}

export function isGranularity(value: unknown): value is Granularity {
  return value === 'day' || value === 'week' || value === 'month';
}

/**
 * The days of the chosen axis. Three dates, three different questions.
 *
 * <p>`published` is what the advert says about itself and is missing wherever it says
 * nothing. `received` is when the mail carrying it arrived — the only one of the three that
 * measures the market's own tempo. `ingested` is when this application wrote the row, which
 * also counts how often the tool was run, and moves for every row when the database is
 * refilled.
 */
export function daysOf(intake: IntakeSeries, axis: TimeAxis): readonly IntakeDay[] {
  switch (axis) {
    case 'published':
      return intake.byPublishedOn;
    case 'received':
      return intake.byReceivedAt;
    default:
      return intake.byIngestedAt;
  }
}

/**
 * Days summed into buckets.
 *
 * <p><b>Everything here is additive, and that is not an accident.</b> A count can be summed
 * into a wider bucket and stay true; a median or a rate cannot, which is why the response
 * metrics arrive from the server as scalars and never pass through this function. If a
 * non-additive number is ever added to `IntakeDay`, it does not belong in this sum.
 *
 * <p>The server already filled the empty days, so a gap in the input is a gap in the
 * archive rather than a day nothing ran — and the sum needs no gap logic at all.
 */
export function bucketBy(
  days: readonly IntakeDay[],
  granularity: Granularity,
): readonly IntakeBucket[] {
  if (granularity === 'day') {
    return days.map((day) => ({ ...day, days: 1 }));
  }

  const buckets = new Map<string, IntakeBucket>();
  for (const day of days) {
    const start = startOf(day.day, granularity);
    const current = buckets.get(start);
    buckets.set(start, current ? add(current, day) : { ...day, day: start, days: 1 });
  }
  return [...buckets.values()];
}

/**
 * The bucket a date belongs to.
 *
 * <p>Weeks start on Monday, matching `date_trunc('week', …)` in Postgres, which is ISO. The
 * two have to agree: a Monday-start chart and a Sunday-start one differ by a day, and
 * nobody ever notices which they are reading.
 *
 * <p>Parsed as UTC on purpose. The server already cut the day boundaries in its own zone
 * and sent plain dates; re-parsing them as local time would move a date across midnight in
 * a negative offset and shift a whole series by one day.
 */
export function startOf(day: string, granularity: Granularity): string {
  const date = new Date(`${day}T00:00:00Z`);
  if (granularity === 'month') {
    date.setUTCDate(1);
  } else if (granularity === 'week') {
    const weekday = (date.getUTCDay() + 6) % 7;
    date.setUTCDate(date.getUTCDate() - weekday);
  }
  return date.toISOString().slice(0, 10);
}

/** A week's worth of days. Below this there is no week to average over. */
const DAYS_IN_A_WEEK = 7;

/**
 * Offers per week, over the span the series actually covers — or null when the span is
 * shorter than a week.
 *
 * <p><b>Null, because the alternative is invention.</b> Two days holding 241 offers
 * extrapolate to 844 a week, which is a number the archive has never seen and cannot
 * support: it assumes the next five days look like these two. An em dash says "not yet",
 * and the tile is worth reading again in a fortnight.
 */
export function perWeek(days: readonly IntakeDay[]): number | null {
  if (days.length < DAYS_IN_A_WEEK) {
    return null;
  }
  const total = days.reduce((sum, day) => sum + day.primaries, 0);
  // The span, not the number of days carrying an offer: a fortnight with one busy day
  // averages to what actually happened rather than to that one day.
  return (total / days.length) * DAYS_IN_A_WEEK;
}

/**
 * The granularity a span of days can actually carry.
 *
 * <p>Two days bucketed by week are one bar labelled with the Monday of that week, which
 * reads as "everything happened on the 31st" — measured on the real archive, and the reason
 * this exists. A bucket wider than the data is not a summary, it is a collapse.
 */
export function suggestedGranularity(days: readonly IntakeDay[]): Granularity {
  if (days.length > 120) {
    return 'month';
  }
  return days.length > 21 ? 'week' : 'day';
}

/**
 * What a bucket covers, as a label.
 *
 * <p>A week bucket carries the Monday it starts on, and on its own that reads as a single
 * date. The range says which one it is. The end is the bucket's, not the data's: a week
 * holding two days is still a week.
 */
export function bucketLabel(day: string, granularity: Granularity): string {
  if (granularity === 'day') {
    return day;
  }
  if (granularity === 'month') {
    return day.slice(0, 7);
  }
  const end = new Date(`${day}T00:00:00Z`);
  end.setUTCDate(end.getUTCDate() + 6);
  return `${day} – ${end.toISOString().slice(0, 10)}`;
}

/** A share as a percentage, or null when there is nothing to divide by. */
export function share(part: number, whole: number): number | null {
  return whole === 0 ? null : (part / whole) * 100;
}

function add(bucket: IntakeBucket, day: IntakeDay): IntakeBucket {
  return {
    day: bucket.day,
    days: bucket.days + 1,
    primaries: bucket.primaries + day.primaries,
    duplicates: bucket.duplicates + day.duplicates,
    passed: bucket.passed + day.passed,
    shortlisted: bucket.shortlisted + day.shortlisted,
    review: bucket.review + day.review,
    discarded: bucket.discarded + day.discarded,
    unscored: bucket.unscored + day.unscored,
  };
}
