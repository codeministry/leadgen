package de.codeministry.leadgen.score;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

/**
 * Everything scoring reads about an offer, after the filter and after enrichment.
 *
 * @param fullText the original ad, when enrichment could fetch it. Null leaves the score
 *     resting on the newsletter's two-line summary, which is exactly the situation the
 *     enrichment stage exists to avoid — so an incomplete offer scores lower on
 *     `project_setup` honestly, rather than being penalised for it twice.
 */
public record ScoreCandidate(
        long id,
        String title,
        String description,
        String fullText,
        List<String> tags,
        BigDecimal rateEur,
        String duration,
        String workload,
        LocalDate startsOn,
        boolean incomplete) {

    /**
     * The columns, named once. Three queries select this row — what is due, one offer on
     * request, and what a batch is waiting on — and a column added to only two of them is a
     * field that is null on some scoring paths and not on others.
     */
    static final String COLUMNS =
            """
            id, title, description, full_text, tags, rate_eur, duration, workload,
            starts_on, enrichment_note""";

    static ScoreCandidate of(ResultSet rs, int row) throws SQLException {
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
