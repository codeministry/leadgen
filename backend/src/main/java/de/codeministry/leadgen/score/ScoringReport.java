package de.codeministry.leadgen.score;

/**
 * What one scoring pass did.
 *
 * @param scored offers that got a number, which requires a judge.
 * @param unscored offers left without a number because no judge was configured. They
 *     still carry their deterministic reasons; see {@link Score#value()} for why the
 *     total is withheld rather than computed from half the weights.
 * @param shortlisted at or above `scoring.thresholds.auto_shortlist`.
 * @param review between `review` and `auto_shortlist`.
 */
public record ScoringReport(int considered, int scored, int unscored, int shortlisted, int review) {

    public static ScoringReport nothing() {
        return new ScoringReport(0, 0, 0, 0, 0);
    }
}
