package de.codeministry.leadgen.score;

import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.config.ConfigSnapshot;
import de.codeministry.leadgen.config.model.MatchingRules;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns what survived the filter into a ranked shortlist, with the reason behind every
 * number.
 *
 * <p>Two halves. {@link RuleScorer} decides everything the profile and the offer's own
 * fields can decide, for free. A {@link Judge} is asked about the rest — role fit and the
 * three penalties — and there may be none: with no key configured the pipeline still runs
 * and produces an unscored shortlist rather than failing.
 *
 * <p><b>Unscored is not zero.</b> The deterministic reasons are written either way, so the
 * operator sees "+45 core skill overlap, +10 rate fit" and can sort by judgement of their
 * own. What is withheld is the total: computed from five of the nine weights it would not
 * be comparable to one computed from all nine, and the same offer would score differently
 * depending on whether a key happened to be configured that morning.
 */
@Slf4j
@Service
public class ScoringService {

    private static final String DUE =
            """
            SELECT id, title, description, full_text, tags, rate_eur, duration, workload,
                   starts_on, enrichment_note
            FROM offer
            WHERE status = 'PASSED' AND duplicate_of_id IS NULL
            ORDER BY id
            """;

    private final ConfigRegistry config;
    private final Judges judges;
    private final JdbcClient jdbc;

    ScoringService(ConfigRegistry config, Judges judges, DataSource dataSource) {
        this.config = config;
        this.judges = judges;
        this.jdbc = JdbcClient.create(dataSource);
    }

    @Transactional
    public ScoringReport run() {
        ConfigSnapshot snapshot = config.snapshot();
        MatchingRules rules = snapshot.rules();
        MatchingRules.Scoring scoring = rules.scoring();
        if (scoring == null) {
            log.warn("No scoring section is configured; the shortlist stays unranked");
            return ScoringReport.nothing();
        }

        RuleScorer scorer = new RuleScorer(rules, snapshot.profile());
        String rulesetVersion = String.valueOf(rules.version());
        int autoShortlist = scoring.thresholds().autoShortlist();
        int review = scoring.thresholds().review();

        Optional<Judge> judge = judges.current();
        if (judge.isEmpty()) {
            log.warn("No language model is configured; offers keep their deterministic reasons and stay unscored");
        }

        List<ScoreCandidate> candidates = jdbc.sql(DUE).query(ScoringService::toCandidate).list();
        int scored = 0;
        int unscored = 0;
        int shortlisted = 0;
        int inReview = 0;

        for (ScoreCandidate candidate : candidates) {
            List<ScoreReason> reasons = new java.util.ArrayList<>(scorer.score(candidate));
            Score score;

            if (judge.isEmpty()) {
                score = Score.unscored(reasons, rulesetVersion);
                unscored++;
            } else {
                reasons.addAll(judge.get().judge(candidate));
                int total = clamp(reasons.stream().mapToInt(ScoreReason::points).sum());
                score = new Score(total, true, reasons, judge.get().model(), rulesetVersion);
                scored++;
                if (total >= autoShortlist) {
                    shortlisted++;
                } else if (total >= review) {
                    inReview++;
                }
            }
            write(candidate.id(), score, autoShortlist, review);
        }

        var report = new ScoringReport(candidates.size(), scored, unscored, shortlisted, inReview);
        log.info("Scoring: {} considered, {} scored, {} unscored, {} shortlisted, {} for review",
                report.considered(), report.scored(), report.unscored(), report.shortlisted(), report.review());
        return report;
    }

    /**
     * The weights sum to 100 and the penalties are negative, so a heavily penalised offer
     * can go below zero and a generous weight table above 100. Both are clamped: the
     * thresholds are stated on a 0-100 scale and a score outside it cannot be read
     * against them.
     */
    private static int clamp(int total) {
        return Math.max(0, Math.min(100, total));
    }

    private void write(long offerId, Score score, int autoShortlist, int review) {
        jdbc.sql(
                        """
                        UPDATE offer
                        SET score_value = ?, score_band = ?, score_model = ?, ruleset_version = ?, scored_at = now()
                        WHERE id = ?
                        """)
                .params(score.value(), score.band(autoShortlist, review), score.model(), score.rulesetVersion(), offerId)
                .update();

        jdbc.sql("DELETE FROM offer_score_reason WHERE offer_id = ?").param(offerId).update();
        List<ScoreReason> reasons = score.reasons();
        for (int position = 0; position < reasons.size(); position++) {
            ScoreReason reason = reasons.get(position);
            jdbc.sql(
                            """
                            INSERT INTO offer_score_reason (offer_id, factor, label, points, position)
                            VALUES (?, ?, ?, ?, ?)
                            """)
                    .params(offerId, reason.factor(), reason.label(), reason.points(), position)
                    .update();
        }
    }

    private static ScoreCandidate toCandidate(java.sql.ResultSet rs, int row) throws SQLException {
        return new ScoreCandidate(
                rs.getLong("id"),
                rs.getString("title"),
                rs.getString("description"),
                rs.getString("full_text"),
                tags(rs.getArray("tags")),
                rs.getObject("rate_eur", BigDecimal.class),
                rs.getString("duration"),
                rs.getString("workload"),
                rs.getObject("starts_on", LocalDate.class),
                rs.getString("enrichment_note") != null);
    }

    private static List<String> tags(Array array) throws SQLException {
        return array == null ? List.of() : List.of((String[]) array.getArray());
    }
}
