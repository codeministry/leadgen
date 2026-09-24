/**
 * What the sparkline draws: one day, two counts. Its own type rather than
 * `core/model/analytics`'s `SummaryIntakeDay`, because `shared/` imports nothing from the
 * layers above it — the two are structurally the same and the dashboard hands one in as
 * the other.
 */
export interface SparkDay {
    readonly day: string;
    readonly extracted: number;
    readonly shortlisted: number;
}

/** The four score bands, by the server's names, for the same reason. */
export interface BandCounts {
    readonly shortlisted: number;
    readonly review: number;
    readonly discarded: number;
    readonly unscored: number;
}
