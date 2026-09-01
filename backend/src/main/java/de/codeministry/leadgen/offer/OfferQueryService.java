package de.codeministry.leadgen.offer;

import de.codeministry.leadgen.score.ScoreReason;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * The read side of the pipeline: what survived, why, and under how many portals.
 *
 * <p>Read-only and deliberately separate from the stages that write. Every one of those
 * owns a narrow slice of the `offer` row; this owns the whole row as a person reads it.
 */
@Service
public class OfferQueryService {

    /**
     * Primaries only. A row with `duplicate_of_id` set is the same project reaching the
     * pipeline through a second portal, and it belongs inside the entry rather than beside
     * it.
     */
    private static final String SHORTLIST =
            """
            SELECT o.*
            FROM offer o
            WHERE o.status = 'PASSED' AND o.duplicate_of_id IS NULL
            ORDER BY o.score_value DESC NULLS LAST, o.ingested_at DESC
            """;

    private final JdbcClient jdbc;

    OfferQueryService(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    public List<ShortlistEntry> shortlist() {
        List<Row> rows = jdbc.sql(SHORTLIST).query(OfferQueryService::row).list();
        if (rows.isEmpty()) {
            return List.of();
        }
        // Two queries for the whole list rather than two per entry: a shortlist is a
        // morning's worth of offers and the reasons are the part that gets read.
        List<Long> ids = rows.stream().map(row -> row.id).toList();
        Map<Long, List<ScoreReason>> reasons = reasonsFor(ids);
        Map<Long, List<OfferSourceRef>> clusters = clustersFor(ids);

        List<ShortlistEntry> entries = new ArrayList<>(rows.size());
        for (Row row : rows) {
            entries.add(entry(row, reasons, clusters));
        }
        return entries;
    }

    /**
     * What the filter did to the whole archive, stage by stage.
     *
     * <p>Counted from `filter_stage`, which the filter writes on every rejected offer for
     * exactly this reason: a rejection without its reason is a number nobody trusts a week
     * later. Stages with nothing in them are still listed, because a stage that removed
     * nothing is information too.
     */
    public FunnelView funnel() {
        Map<String, Integer> removed = new LinkedHashMap<>();
        // Primaries only, on both sides of the subtraction. Deduplication runs before the
        // filter and rejections are written on duplicates too, so counting every rejection
        // against a primaries-only total made the rail claim -45 survivors — visibly wrong,
        // which is the only reason it was caught.
        jdbc.sql(
                        """
                        SELECT filter_stage, count(*) AS removed FROM offer
                        WHERE filter_stage IS NOT NULL AND duplicate_of_id IS NULL GROUP BY 1
                        """)
                .query((rs, index) -> removed.put(rs.getString("filter_stage"), rs.getInt("removed")))
                .list();

        int total = jdbc.sql("SELECT count(*) FROM offer WHERE duplicate_of_id IS NULL")
                .query(Integer.class)
                .single();

        var stages = new ArrayList<FunnelView.Stage>();
        for (var stage : de.codeministry.leadgen.filter.FilterStage.values()) {
            stages.add(new FunnelView.Stage(
                    stage.name().toLowerCase().replace('_', '-'),
                    // The enum writes its description as a sentence fragment, because that
                    // is how it reads in a log line. On a chart it is a label.
                    capitalize(stage.description()),
                    removed.getOrDefault(stage.name(), 0)));
        }
        int survived = total - stages.stream().mapToInt(FunnelView.Stage::removed).sum();
        return new FunnelView(total, stages, survived);
    }

    private static String capitalize(String label) {
        return label.isEmpty() ? label : Character.toUpperCase(label.charAt(0)) + label.substring(1);
    }

    public Optional<ShortlistEntry> find(long id) {
        // Not restricted to PASSED: the detail is also how somebody looks at an offer the
        // filter rejected and asks whether the rule was right.
        return jdbc.sql("SELECT o.* FROM offer o WHERE o.id = ?")
                .param(id)
                .query(OfferQueryService::row)
                .optional()
                .map(row -> entry(row, reasonsFor(List.of(id)), clustersFor(List.of(id))));
    }

    private ShortlistEntry entry(
            Row row, Map<Long, List<ScoreReason>> reasons, Map<Long, List<OfferSourceRef>> clusters) {
        var sources = new ArrayList<>(List.of(new OfferSourceRef(row.portal, row.agency, row.url)));
        sources.addAll(clusters.getOrDefault(row.id, List.of()));
        return new ShortlistEntry(
                row.offer,
                new OfferScoreView(
                        row.scoreValue,
                        "PASSED".equals(row.status),
                        reasons.getOrDefault(row.id, List.of()),
                        row.scoreModel,
                        row.rulesetVersion),
                new OfferFlags(row.enrichedAt == null || row.enrichmentNote != null, row.remotePercent == null),
                sources);
    }

    private Map<Long, List<ScoreReason>> reasonsFor(List<Long> ids) {
        Map<Long, List<ScoreReason>> byOffer = new LinkedHashMap<>();
        jdbc.sql(
                        """
                        SELECT offer_id, factor, label, points FROM offer_score_reason
                        WHERE offer_id = ANY (?) ORDER BY offer_id, position
                        """)
                .param(ids.toArray(Long[]::new))
                .query((rs, index) -> {
                    byOffer.computeIfAbsent(rs.getLong("offer_id"), key -> new ArrayList<>())
                            .add(new ScoreReason(rs.getString("factor"), rs.getString("label"), rs.getInt("points")));
                    return null;
                })
                .list();
        return byOffer;
    }

    /** The other portals advertising the same project, keyed by the primary they point at. */
    private Map<Long, List<OfferSourceRef>> clustersFor(List<Long> ids) {
        Map<Long, List<OfferSourceRef>> byPrimary = new LinkedHashMap<>();
        jdbc.sql(
                        """
                        SELECT duplicate_of_id, portal, agency, url FROM offer
                        WHERE duplicate_of_id = ANY (?) ORDER BY duplicate_of_id, id
                        """)
                .param(ids.toArray(Long[]::new))
                .query((rs, index) -> {
                    byPrimary.computeIfAbsent(rs.getLong("duplicate_of_id"), key -> new ArrayList<>())
                            .add(new OfferSourceRef(rs.getString("portal"), rs.getString("agency"), rs.getString("url")));
                    return null;
                })
                .list();
        return byPrimary;
    }

    /** The row plus the few columns the entry needs but the view does not carry. */
    private record Row(
            long id,
            OfferView offer,
            String status,
            Integer scoreValue,
            String scoreModel,
            String rulesetVersion,
            String portal,
            String agency,
            String url,
            Integer remotePercent,
            java.time.Instant enrichedAt,
            String enrichmentNote) {}

    private static Row row(ResultSet rs, int index) throws SQLException {
        long id = rs.getLong("id");
        var offer = new OfferView(
                id,
                rs.getString("external_id"),
                rs.getString("title"),
                rs.getString("description"),
                rs.getString("url"),
                rs.getString("location"),
                rs.getString("portal"),
                rs.getString("agency"),
                rs.getObject("published_on", LocalDate.class),
                tags(rs),
                rs.getObject("rate_eur", BigDecimal.class),
                rs.getObject("remote_percent", Integer.class),
                rs.getObject("starts_on", LocalDate.class),
                rs.getString("duration"),
                rs.getString("workload"),
                rs.getString("language"),
                rs.getString("full_text"),
                rs.getString("package_dir"));
        return new Row(
                id,
                offer,
                rs.getString("status"),
                rs.getObject("score_value", Integer.class),
                rs.getString("score_model"),
                rs.getString("ruleset_version"),
                rs.getString("portal"),
                rs.getString("agency"),
                rs.getString("url"),
                rs.getObject("remote_percent", Integer.class),
                instant(rs),
                rs.getString("enrichment_note"));
    }

    private static List<String> tags(ResultSet rs) throws SQLException {
        var array = rs.getArray("tags");
        return array == null ? List.of() : List.of((String[]) array.getArray());
    }

    /** `timestamptz` does not convert straight to an `Instant`; the driver throws instead. */
    private static java.time.Instant instant(ResultSet rs) throws SQLException {
        var value = rs.getTimestamp("enriched_at");
        return value == null ? null : value.toInstant();
    }
}
