package de.codeministry.leadgen.score;

import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The one place a score reaches the database.
 *
 * <p>Extracted because two paths now produce one: the synchronous run and the collector
 * that picks up a batch minutes later. A second copy of these two statements would drift
 * the first time a column is added, and the drift would be invisible — the batched half of
 * the shortlist would simply be missing something.
 */
@Component
class ScoreWriter {

    private final JdbcClient jdbc;

    ScoreWriter(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    void write(long offerId, Score score, int autoShortlist, int review) {
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
}
