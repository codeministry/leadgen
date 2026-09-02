import { IntakeDay } from '@core/model/analytics';
import { bucketBy, bucketLabel, perWeek, share, startOf, suggestedGranularity } from './analytics-aggregate';

function day(date: string, primaries: number): IntakeDay {
  return {
    day: date,
    primaries,
    duplicates: 0,
    passed: primaries,
    shortlisted: 0,
    review: 0,
    discarded: 0,
    unscored: 0,
  };
}

describe('analytics-aggregate', () => {
  it('starts a week on Monday, as Postgres does', () => {
    // date_trunc('week', …) is ISO. A Sunday-start chart drawn over Monday-start data is
    // off by a day and looks entirely plausible.
    expect(startOf('2026-09-02', 'week')).toBe('2026-08-31');
    expect(startOf('2026-08-31', 'week')).toBe('2026-08-31');
    expect(startOf('2026-08-30', 'week')).toBe('2026-08-24');
  });

  it('reads a date as the day the server meant, not as local midnight', () => {
    // Parsed as local time, a plain date west of Greenwich lands on the previous day and
    // the whole series shifts by one.
    expect(startOf('2026-09-01', 'month')).toBe('2026-09-01');
  });

  it('sums days into weeks', () => {
    const days = [day('2026-08-31', 3), day('2026-09-01', 4), day('2026-09-07', 5)];

    const weeks = bucketBy(days, 'week');

    expect(weeks).toHaveLength(2);
    expect(weeks[0].day).toBe('2026-08-31');
    expect(weeks[0].primaries).toBe(7);
    expect(weeks[0].days).toBe(2);
    expect(weeks[1].primaries).toBe(5);
  });

  it('leaves days alone at day granularity', () => {
    const days = [day('2026-09-01', 2)];

    expect(bucketBy(days, 'day')[0].days).toBe(1);
  });

  it('says nothing rather than extrapolating from a span shorter than a week', () => {
    // Two days holding 241 offers extrapolate to 844 a week — a number the archive has
    // never seen, resting on the assumption that the next five days look like these two.
    expect(perWeek([day('2026-09-01', 112), day('2026-09-02', 129)])).toBeNull();
  });

  it('averages over the span rather than over the busy days', () => {
    // A fortnight with one busy day is a quiet fortnight, not a busy day.
    const days = [
      day('2026-09-01', 14),
      ...[...Array(13).keys()].map((index) => day(`2026-09-${String(index + 2).padStart(2, '0')}`, 0)),
    ];

    expect(perWeek(days)).toBe(7);
  });

  it('says nothing rather than zero when there is nothing to divide by', () => {
    expect(share(0, 0)).toBeNull();
    expect(share(1, 4)).toBe(25);
  });

  it('does not bucket two days into a week', () => {
    // Measured on the real archive: two days of mail bucketed by week became one bar
    // labelled with that week's Monday, and read as "every offer arrived on the 31st".
    expect(suggestedGranularity([day('2026-08-31', 112), day('2026-09-01', 129)])).toBe('day');
  });

  it('moves up a bucket once the span can carry one', () => {
    expect(suggestedGranularity([...Array(30).keys()].map((i) => day(`2026-09-${i + 1}`, 1)))).toBe('week');
    expect(suggestedGranularity([...Array(200).keys()].map((i) => day(`2026-09-${i + 1}`, 1)))).toBe('month');
  });

  it('labels a week as the week it is, not as the Monday it starts on', () => {
    expect(bucketLabel('2026-08-31', 'week')).toBe('2026-08-31 – 2026-09-06');
    expect(bucketLabel('2026-08-31', 'day')).toBe('2026-08-31');
    expect(bucketLabel('2026-08-01', 'month')).toBe('2026-08');
  });

  it('is empty for an empty archive', () => {
    expect(bucketBy([], 'month')).toEqual([]);
    expect(perWeek([])).toBeNull();
  });
});
