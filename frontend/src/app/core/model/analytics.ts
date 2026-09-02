/**
 * Mirrors `de.codeministry.leadgen.analytics.AnalyticsView`.
 *
 * <p>Nothing here names a filter stage, an application status or a ruleset: those are the
 * server's enums, and a union type in TypeScript would disagree with it the first time one
 * of them grew. What the browser gets is whatever the payload carries.
 */
export interface AnalyticsView {
  readonly zone: string;
  readonly generatedAt: string;
  /** Null on an archive nothing has arrived in yet. */
  readonly from: string | null;
  readonly to: string | null;
  readonly funnel: AnalyticsFunnel;
  readonly intake: IntakeSeries;
  readonly market: MarketView;
  readonly scores: ScoreDistribution;
  readonly applications: ApplicationAnalytics;
  readonly runs: RunSeries;
  readonly scales: readonly ScaleInUse[];
}

/** The same shape `core/model/funnel.ts` carries, reused rather than re-fetched. */
export interface AnalyticsFunnel {
  readonly total: number;
  readonly survived: number;
  readonly stages: readonly { readonly id: string; readonly label: string; readonly removed: number }[];
}

export interface IntakeSeries {
  readonly byIngestedAt: readonly IntakeDay[];
  readonly byPublishedOn: readonly IntakeDay[];
  /** When the mail carrying the offer arrived. The market's tempo, not the operator's. */
  readonly byReceivedAt: readonly IntakeDay[];
  readonly withoutPublishedOn: number;
  readonly publishedOutOfRange: number;
  readonly withoutReceivedAt: number;
}

/**
 * One day. `primaries` and `duplicates` are measurements — an arrival date is written once
 * and never rewritten. `passed` and the four bands are today's verdict on that day's
 * offers, because the filter re-judges the whole archive on every run.
 */
export interface IntakeDay {
  readonly day: string;
  readonly primaries: number;
  readonly duplicates: number;
  readonly passed: number;
  readonly shortlisted: number;
  readonly review: number;
  readonly discarded: number;
  readonly unscored: number;
}

export interface MarketView {
  readonly portals: readonly PortalStat[];
  readonly tags: readonly TagStat[];
  readonly locations: readonly LocationStat[];
  readonly reach: ReachCounts;
  readonly stageMix: readonly StageDay[];
}

/** `listings` counts what a portal published; `projects` counts what it brought in first. */
export interface PortalStat {
  readonly portal: string;
  readonly listings: number;
  readonly projects: number;
  readonly passed: number;
  readonly shortlisted: number;
}

/** A search tag of the source, not a skill read out of the advert. */
export interface TagStat {
  readonly tag: string;
  readonly projects: number;
  readonly passed: number;
}

export interface LocationStat {
  readonly location: string;
  readonly projects: number;
  readonly passed: number;
}

export interface ReachCounts {
  readonly outOfReach: number;
  readonly abroad: number;
  readonly remoteShare: number;
}

/** One day and one stage. Bucketed in the browser, like the intake series. */
export interface StageDay {
  readonly day: string;
  readonly stage: string;
  readonly removed: number;
}

export interface ScoreDistribution {
  readonly bucketSize: number;
  readonly buckets: readonly { readonly floor: number; readonly count: number }[];
  readonly unscored: number;
  readonly shortlistAt: number;
  readonly reviewAt: number;
}

export interface ApplicationAnalytics {
  readonly byStatus: readonly { readonly status: string; readonly applications: number }[];
  readonly transitions: readonly { readonly day: string; readonly toStatus: string; readonly moves: number }[];
  readonly response: ResponseMetrics;
}

/** The medians are null until something has been answered. Null, not zero. */
export interface ResponseMetrics {
  readonly sent: number;
  readonly answered: number;
  readonly backdated: number;
  readonly medianDaysToFirstReply: number | null;
  readonly p90DaysToFirstReply: number | null;
  readonly won: number;
  readonly lost: number;
  readonly rejected: number;
}

export interface RunSeries {
  readonly days: readonly RunDay[];
  /** Whole runs, oldest first, as each run reported itself. Capped at the last thirty. */
  readonly passes: readonly RunPass[];
  /** When the run history starts. Null means nothing has been recorded yet. */
  readonly historySince: string | null;
}

/**
 * One run, written down before the next one overwrote the evidence. The only series on the
 * screen that is neither the current archive nor a recomputation of it — and the only one
 * that cannot be backfilled.
 */
export interface RunPass {
  readonly finishedAt: string;
  readonly status: string;
  readonly rulesetVersion: string | null;
  readonly scoreModel: string | null;
  readonly extracted: number;
  readonly written: number;
  readonly filterConsidered: number;
  readonly filterPassed: number;
  readonly scored: number;
  readonly shortlisted: number;
  readonly packaged: number;
}

export interface RunDay {
  readonly day: string;
  readonly runs: number;
  readonly documents: number;
  readonly extracted: number;
  readonly written: number;
  readonly announced: number | null;
}

/** One ruleset and judge the archive's scores were produced under. */
export interface ScaleInUse {
  readonly rulesetVersion: string | null;
  readonly scoreModel: string | null;
  readonly offers: number;
  readonly firstScoredAt: string | null;
  readonly lastScoredAt: string | null;
}
