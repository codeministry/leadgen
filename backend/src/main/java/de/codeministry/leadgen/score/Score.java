package de.codeministry.leadgen.score;

import java.util.List;

/**
 * What an offer is worth, and why.
 *
 * @param value 0 to 100, or <b>null</b> when no language model was available. Null is not
 *     zero: the deterministic factors still produced reasons, but a total computed from
 *     part of the weights would not be comparable to one computed from all of them, and
 *     two runs of the same offer would differ by whether a key happened to be configured.
 * @param reasons every factor that contributed, deterministic and judged alike. A number
 *     without a reason gets ignored within a week.
 * @param model which model judged, or null when none did.
 * @param rulesetVersion the `version` of the rules that produced this, so a score can be
 *     told apart from one produced under different weights.
 */
public record Score(
        Integer value, boolean hardPass, List<ScoreReason> reasons, String model, String rulesetVersion) {

    public static Score unscored(List<ScoreReason> deterministic, String rulesetVersion) {
        return new Score(null, true, deterministic, null, rulesetVersion);
    }

    /** The band names in `scoring.thresholds`, which the shortlist and the digest read. */
    public String band(int autoShortlist, int review) {
        if (value == null) {
            return "UNSCORED";
        }
        if (value >= autoShortlist) {
            return "SHORTLISTED";
        }
        return value >= review ? "REVIEW" : "DISCARDED";
    }
}
