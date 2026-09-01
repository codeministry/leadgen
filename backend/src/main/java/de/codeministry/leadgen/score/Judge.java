package de.codeministry.leadgen.score;

import java.util.List;

/**
 * The half of the score that needs judgement: role fit, and the three penalties.
 *
 * <p>An interface with one production implementation, because the point is that there may
 * be <em>none</em>. With no key configured the pipeline runs without a judge and produces
 * an unscored shortlist rather than failing — the tool has to work weaker, not stop.
 */
public interface Judge {

    /** The factors a judge is asked about. The rest are decided by {@link RuleScorer}. */
    List<String> JUDGED = List.of("role_fit", "stack_mismatch_dominant", "role_mismatch", "vague_description");

    /** Which model answered, for the record on every score it produced. */
    String model();

    /**
     * @return one reason per factor the judge has an opinion about. An empty list is a
     *     legitimate answer and means the offer earns no role-fit points and no penalties.
     */
    List<ScoreReason> judge(ScoreCandidate offer);
}
