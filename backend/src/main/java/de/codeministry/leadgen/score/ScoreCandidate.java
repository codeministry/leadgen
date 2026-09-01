package de.codeministry.leadgen.score;

import java.math.BigDecimal;
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
        boolean incomplete) {}
