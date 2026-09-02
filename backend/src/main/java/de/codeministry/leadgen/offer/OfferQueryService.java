package de.codeministry.leadgen.offer;

import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.config.model.MatchingRules;
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
     *
     * <p>`%s` is where the filters go, built by {@link #where}. The ordering is the page's
     * key as well as its sort: `coalesce(score_value, -1)` rather than `NULLS LAST` so the
     * unscored have a value the cursor can compare, and `id` last so two offers with the
     * same score and second cannot straddle a page boundary.
     */
    private static final String SHORTLIST =
            """
            SELECT o.*
            FROM offer o
            WHERE o.status = 'PASSED' AND o.duplicate_of_id IS NULL
            %s
            ORDER BY coalesce(o.score_value, -1) DESC, o.ingested_at DESC, o.id DESC
            LIMIT :limit
            """;

    /**
     * The same predicate without the page, so the counts describe what the filters matched
     * rather than what happens to be loaded.
     *
     * <p>The unscored count comes with it because it sits in the same sentence on the
     * screen. Counted in the browser it counted the loaded pages, which reads as a
     * statement about the whole list and shrinks as you scroll — the same defect the portal
     * dropdown had.
     */
    private static final String MATCHED =
            """
            SELECT count(*) AS matched,
                   count(*) FILTER (WHERE o.score_value IS NULL) AS unscored
            FROM offer o
            WHERE o.status = 'PASSED' AND o.duplicate_of_id IS NULL
            %s
            """;

    /**
     * What the match was narrowed from — the working list, or the archive when that is what
     * is on screen. It carries the archive clause and none of the filters, so the sentence
     * beside the list reads "12 of 2219" and not "12 of 12".
     */
    private static final String TOTAL =
            """
            SELECT count(*) FROM offer o
            WHERE o.status = 'PASSED' AND o.duplicate_of_id IS NULL
            %s
            """;

    /**
     * Every portal the shortlist knows, including the ones only a duplicate was seen on —
     * the filter matches those too, so offering fewer choices than it accepts would be a
     * filter that finds things it never listed.
     */
    private static final String PORTALS =
            """
            SELECT DISTINCT p.portal
            FROM offer o
            JOIN offer p ON p.id = o.id OR p.duplicate_of_id = o.id
            WHERE o.status = 'PASSED' AND o.duplicate_of_id IS NULL AND p.portal IS NOT NULL
            %s
            ORDER BY 1
            """;

    private final JdbcClient jdbc;
    private final ConfigRegistry config;

    OfferQueryService(DataSource dataSource, ConfigRegistry config) {
        this.jdbc = JdbcClient.create(dataSource);
        this.config = config;
    }

    /**
     * One page of the shortlist, filtered in SQL.
     *
     * <p>The filters live here rather than in the browser because a page of a
     * browser-filtered list is meaningless: page two of "everything" is not page two of
     * "everything matching Java". Moving them also removed the second implementation — the
     * band boundaries are the configured thresholds, read once, instead of two literals in
     * TypeScript that decided which offers a button showed.
     */
    public ShortlistPage shortlist(ShortlistQuery query) {
        var thresholds = config.snapshot().rules().scoring().thresholds();
        var filters = where(query, thresholds);

        List<Row> rows = bind(jdbc.sql(SHORTLIST.formatted(filters.sql())), filters)
                .param("limit", query.limit())
                .query(OfferQueryService::row)
                .list();
        var counts = bind(jdbc.sql(MATCHED.formatted(filters.sql())), filters)
                .query((rs, n) -> new int[] {rs.getInt("matched"), rs.getInt("unscored")})
                .single();
        // The archive clause alone, and none of the filters: these two describe the set the
        // filters are being applied to, not the match.
        int total = jdbc.sql(TOTAL.formatted(filters.archive())).query(Integer.class).single();
        List<String> portals =
                jdbc.sql(PORTALS.formatted(filters.archive())).query(String.class).list();

        return new ShortlistPage(
                entries(rows), cursorAfter(rows, query.limit()), counts[0], counts[1], total, portals);
    }

    /**
     * The cursor for the next page, or null when this was the last.
     *
     * <p>Null when the page came back short: a full page is not proof that more exists, but
     * a short one is proof that it does not, and one wasted request at the end is cheaper
     * than a count on every page.
     */
    private static String cursorAfter(List<Row> rows, int limit) {
        if (rows.size() < limit) {
            return null;
        }
        Row last = rows.getLast();
        // Microseconds, not milliseconds. Postgres stores `timestamptz` to the microsecond
        // and `now()` is the transaction's clock, so every row an ingest batch writes
        // carries the same value down to the microsecond. Truncated to milliseconds the
        // cursor names an instant *before* the row it came from, and the next page's
        // `<` then excludes every row sharing that millisecond — the rest of the batch,
        // silently. Measured: a tie test asking for four offers two at a time got three.
        return "%d|%d|%d"
                .formatted(
                        last.scoreValue() == null ? -1 : last.scoreValue(),
                        micros(last.ingestedAt()),
                        last.id());
    }

    /** Lossless for anything Postgres can store; `toEpochMilli` is not. */
    private static long micros(java.time.Instant instant) {
        return instant.getEpochSecond() * 1_000_000L + instant.getNano() / 1_000L;
    }

    private List<ShortlistEntry> entries(List<Row> rows) {
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
     * The filter clause and the values it needs, kept together so neither can be forgotten.
     *
     * @param archive which side of the archive is being read, on its own. Two queries need
     *     that clause without the filters, and assembling it twice is how the two disagree.
     * @param sql the archive clause and every filter, which is what the page and the match
     *     count are read with.
     */
    private record Filters(String archive, String sql, Map<String, Object> params) {}

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec statement, Filters filters) {
        var bound = statement;
        for (var param : filters.params().entrySet()) {
            bound = bound.param(param.getKey(), param.getValue());
        }
        return bound;
    }

    /**
     * The filters, as SQL and parameters.
     *
     * <p>The search matches what the browser's did: title, description and the tags. The
     * portal matches the offer's own or any of its duplicates', because a project reaching
     * the shortlist through gulp is on gulp even when freelancermap holds the primary — the
     * dropdown offers those portals, so the filter has to accept them.
     */
    private static Filters where(ShortlistQuery query, MatchingRules.Scoring.Thresholds thresholds) {
        // The third part of "this is on my list today", beside PASSED and primaries-only.
        // It is a literal rather than a parameter because it is a choice between two
        // clauses, not a value: `archived_at = :x` cannot express "is null".
        String archive = query.archived()
                ? " AND o.archived_at IS NOT NULL\n"
                : " AND o.archived_at IS NULL\n";
        var sql = new StringBuilder(archive);
        Map<String, Object> params = new LinkedHashMap<>();

        if (query.q() != null && !query.q().isBlank()) {
            sql.append("""
                    AND (o.title ILIKE :q OR o.description ILIKE :q
                         OR EXISTS (SELECT 1 FROM unnest(o.tags) AS tag WHERE tag ILIKE :q))
                    """);
            params.put("q", "%" + query.q().trim() + "%");
        }
        if ("shortlist".equals(query.band())) {
            sql.append(" AND o.score_value >= :shortlistAt\n");
            params.put("shortlistAt", thresholds.autoShortlist());
        } else if ("review".equals(query.band())) {
            sql.append(" AND o.score_value >= :reviewAt AND o.score_value < :shortlistAt\n");
            params.put("reviewAt", thresholds.review());
            params.put("shortlistAt", thresholds.autoShortlist());
        }
        if (query.portal() != null && !query.portal().isBlank()) {
            sql.append("""
                    AND EXISTS (SELECT 1 FROM offer p
                                WHERE (p.id = o.id OR p.duplicate_of_id = o.id) AND p.portal = :portal)
                    """);
            params.put("portal", query.portal());
        }
        if (query.cursor() != null && !query.cursor().isBlank()) {
            String[] parts = query.cursor().split("\\|");
            // All three columns descend together, so one row comparison walks the key.
            sql.append(" AND (coalesce(o.score_value, -1), o.ingested_at, o.id) < (:cScore, :cAt, :cId)\n");
            params.put("cScore", Integer.parseInt(parts[0]));
            params.put(
                    "cAt",
                    java.sql.Timestamp.from(java.time.Instant.EPOCH.plus(
                            Long.parseLong(parts[1]), java.time.temporal.ChronoUnit.MICROS)));
            params.put("cId", Long.parseLong(parts[2]));
        }
        return new Filters(archive, sql.toString(), params);
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
        // Primaries only and not archived, on both sides of the subtraction. Deduplication
        // runs before the filter and rejections are written on duplicates too, so counting
        // every rejection against a primaries-only total made the rail claim -45 survivors —
        // visibly wrong, which is the only reason it was caught. The archive is the same
        // trap a second time, and it is the larger of the two: after a week it holds most
        // of the table.
        jdbc.sql(
                        """
                        SELECT filter_stage, count(*) AS removed FROM offer
                        WHERE filter_stage IS NOT NULL AND duplicate_of_id IS NULL
                          AND archived_at IS NULL
                        GROUP BY 1
                        """)
                .query((rs, index) -> removed.put(rs.getString("filter_stage"), rs.getInt("removed")))
                .list();

        int total = jdbc.sql(
                        "SELECT count(*) FROM offer WHERE duplicate_of_id IS NULL AND archived_at IS NULL")
                .query(Integer.class)
                .single();
        int archived = jdbc.sql(
                        "SELECT count(*) FROM offer WHERE duplicate_of_id IS NULL AND archived_at IS NOT NULL")
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
        return new FunnelView(total, stages, survived, archived);
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
            String enrichmentNote,
            /** Carried only so the cursor can name the row it stopped at. */
            java.time.Instant ingestedAt) {}

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
                rs.getString("package_dir"),
                rs.getTimestamp("archived_at") == null ? null : rs.getTimestamp("archived_at").toInstant(),
                rs.getString("archive_source"));
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
                rs.getString("enrichment_note"),
                rs.getTimestamp("ingested_at").toInstant());
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
