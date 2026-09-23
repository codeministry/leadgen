/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.offer;

import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.config.model.MatchingRules;
import de.codeministry.leadgen.content.ContentText;
import de.codeministry.leadgen.retrieval.SemanticFilter;
import de.codeministry.leadgen.score.ScoreReason;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.*;
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
     * <p>The first `%s` is where the filters and the page clause go, built by {@link #where};
     * the second is the ordering, built by {@link ShortlistSort}. <b>The ordering is the
     * page's key as well as its sort</b>, which is why it is composed from one place: the
     * `coalesce` rather than `NULLS LAST` so that every row has a value the cursor can
     * compare, and `id` last so two offers sharing a key and a second cannot straddle a page
     * boundary. What each sentinel is and why is on `ShortlistSort`.
     */
    private static final String SHORTLIST =
            """
        SELECT o.*, s.name AS source_name
        FROM offer o
        JOIN source s ON s.id = o.source_id
        WHERE o.status = 'PASSED' AND o.duplicate_of_id IS NULL
        %s
        ORDER BY %s
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
    private final SemanticFilter semantic;

    OfferQueryService(DataSource dataSource, ConfigRegistry config, SemanticFilter semantic) {
        this.jdbc = JdbcClient.create(dataSource);
        this.config = config;
        this.semantic = semantic;
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
        // Resolved before the clause is built, because this is the one filter that can refuse:
        // a relatedness question this installation cannot answer is a 400 with a sentence, never
        // a parameter quietly dropped. Dropped, the list would widen under a heading saying it
        // was narrowed, and the count beside it would be true about a set nobody asked for.
        var narrowing =
                semantic.narrow(query.related().semantic(), query.related().similarTo());
        // Never refuses: without the index a topic is answered by its stored alias matches alone.
        var topicNeighbourhood = semantic.topicNeighbourhood(query.topic()).orElse(null);
        var filters = where(query, thresholds, narrowing, topicNeighbourhood);

        // The page clause on the list and deliberately not on the count. Formatted into
        // MATCHED as well — which is what shipped — it counted the rows *after* the cursor,
        // so the number beside the list shrank as the reader scrolled. That is precisely the
        // defect that moved this count to the server in the first place, reappearing on the
        // other side of the wire.
        List<Row> rows = bind(
                        jdbc.sql(SHORTLIST.formatted(
                                filters.sql() + filters.page(), query.sort().orderBy())),
                        filters.params(),
                        filters.pageParams())
                .param("limit", query.limit())
                .query(OfferQueryService::row)
                .list();
        var counts = bind(jdbc.sql(MATCHED.formatted(filters.sql())), filters.params())
                .query((rs, n) -> new int[] {rs.getInt("matched"), rs.getInt("unscored")})
                .single();
        // The archive clause alone, and none of the filters: these two describe the set the
        // filters are being applied to, not the match.
        int total = jdbc.sql(TOTAL.formatted(filters.archive()))
                .query(Integer.class)
                .single();
        List<String> portals = jdbc.sql(PORTALS.formatted(filters.archive()))
                .query(String.class)
                .list();

        return new ShortlistPage(
                entries(rows),
                cursorAfter(rows, query.limit(), query.sort()),
                counts[0],
                counts[1],
                total,
                portals,
                semantic.coverage(),
                query.related().similarTo() == null
                        ? null
                        : semantic.titleOf(query.related().similarTo()));
    }

    /**
     * The cursor for the next page, or null when this was the last.
     *
     * <p>Null when the page came back short: a full page is not proof that more exists, but
     * a short one is proof that it does not, and one wasted request at the end is cheaper
     * than a count on every page.
     */
    private static String cursorAfter(List<Row> rows, int limit, ShortlistSort sort) {
        if (rows.size() < limit) {
            return null;
        }
        Row last = rows.getLast();
        // Exhaustive on purpose, and this is the only reason it is a switch rather than a
        // method on the enum: a sort key added without deciding what its cursor carries fails
        // the build here, at the one place that has to change, instead of failing on a Tuesday
        // at a page boundary. It has already earned that twice — the two duration sorts share
        // an arm because they read one column, and FRESH needed one of its own.
        long key =
                switch (sort) {
                    case SCORE -> sort.carried(last.scoreValue());
                    case START -> sort.carried(last.offer().startsOn());
                    case DEADLINE -> sort.carried(last.offer().applyBy());
                    case DURATION, DURATION_SHORT -> sort.carried(last.offer().durationMonths());
                    case FRESH -> sort.carried(last.ingestedAt());
                };
        return new Cursor(sort, key, last.ingestedAt(), last.id()).encoded();
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
     * @param archive    which side of the archive is being read, on its own. Two queries need
     *                   that clause without the filters, and assembling it twice is how the two disagree.
     * @param sql        the archive clause and every filter. What the match count is read with, and
     *                   what the page is read with once the page clause is appended.
     * @param page       the cursor clause and nothing else. <b>Apart from the filters, because the
     *                   page is not part of the match:</b> formatted into MATCHED it counts the rows after
     *                   the cursor, so the number beside the list shrinks as the reader scrolls. Two maps
     *                   for the same reason — the count must not be handed parameters its SQL never names.
     */
    private record Filters(
            String archive, String sql, String page, Map<String, Object> params, Map<String, Object> pageParams) {}

    @SafeVarargs
    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec statement, Map<String, Object>... maps) {
        var bound = statement;
        for (Map<String, Object> map : maps) {
            for (var param : map.entrySet()) {
                bound = bound.param(param.getKey(), param.getValue());
            }
        }
        return bound;
    }

    /**
     * The filters, as SQL and parameters.
     *
     * <p>The search reads the title, the description, the tags and <b>the advert the enrichment
     * stage fetched</b> — the same text {@link ContentText#of} hands to the judge and the
     * packager, and by the same rule: the content blocks when the advert was segmented, and
     * {@code full_text} only when it was not. Searching {@code full_text} unconditionally would
     * put the portal furniture back into the one place it was taken out of, and a search for an
     * agency's postal address would then match every advert that agency ever posted.
     *
     * <p>The portal matches the offer's own or any of its duplicates', because a project
     * reaching the shortlist through portal-c is on portal-c even when portal-a holds the
     * primary — the dropdown offers those portals, so the filter has to accept them.
     */
    private static Filters where(
            ShortlistQuery query,
            MatchingRules.Scoring.Thresholds thresholds,
            SemanticFilter.Narrowing narrowing,
            SemanticFilter.Narrowing topicNeighbourhood) {
        // The third part of "this is on my list today", beside PASSED and primaries-only.
        // It is a literal rather than a parameter because it is a choice between two
        // clauses, not a value: `archived_at = :x` cannot express "is null".
        String archive = query.archived() ? " AND o.archived_at IS NOT NULL\n" : " AND o.archived_at IS NULL\n";
        var sql = new StringBuilder(archive);
        Map<String, Object> params = new LinkedHashMap<>();

        if (query.q() != null && !query.q().isBlank()) {
            // The CASE is `ContentText.of`'s fallback written as SQL: a segmented advert is
            // searched through its CONTENT blocks, and an advert that was never segmented —
            // or whose blocks were all furniture — through `full_text`, which is the only
            // text it has. `jsonb_array_elements` returns no rows for a null column, so an
            // offer enrichment never reached lands in the ELSE and matches on nothing.
            sql.append(
                    """
                AND (o.title ILIKE :q OR o.description ILIKE :q
                     OR EXISTS (SELECT 1 FROM unnest(o.tags) AS tag WHERE tag ILIKE :q)
                     OR CASE WHEN EXISTS (SELECT 1 FROM jsonb_array_elements(o.content_blocks) AS b
                                           WHERE b ->> 'kind' = 'CONTENT')
                             THEN EXISTS (SELECT 1 FROM jsonb_array_elements(o.content_blocks) AS b
                                           WHERE b ->> 'kind' = 'CONTENT' AND b ->> 'text' ILIKE :q)
                             ELSE o.full_text ILIKE :q
                        END)
                """);
            params.put("q", "%" + query.q().trim() + "%");
        }
        // The score axis, all three spellings of it in one block because they are one filter.
        // Which of them may be asked for at a time is decided in `ScoreFilter`, before any of
        // this runs, so nothing here has to reason about a band inside a range.
        ScoreFilter score = query.score();
        if ("shortlist".equals(score.band())) {
            sql.append(" AND o.score_value >= :shortlistAt\n");
            params.put("shortlistAt", thresholds.autoShortlist());
        } else if ("review".equals(score.band())) {
            sql.append(" AND o.score_value >= :reviewAt AND o.score_value < :shortlistAt\n");
            params.put("reviewAt", thresholds.review());
            params.put("shortlistAt", thresholds.autoShortlist());
        }
        if (score.min() != null) {
            // `NULL >= 60` already drops an unjudged offer; the IS NOT NULL is written because
            // the exclusion is a decision and not a property of three-valued logic somebody
            // has to know. The same sentence stands over `minMonths` twenty lines down, and
            // `ScoreState.UNSCORED` is how the excluded set is asked for instead.
            sql.append(" AND o.score_value IS NOT NULL AND o.score_value >= :minScore\n");
            params.put("minScore", score.min());
        }
        if (score.max() != null) {
            sql.append(" AND o.score_value IS NOT NULL AND o.score_value <= :maxScore\n");
            params.put("maxScore", score.max());
        }
        sql.append(score.state().clause());

        if (!query.portals().isEmpty()) {
            // `IN (:portals)` and deliberately not `= ANY (:portals)`. JdbcClient is named or
            // positional per statement, and a *named* parameter holding a collection is
            // expanded into a `?, ?, ?` list — which turns `= ANY (:portals)` into
            // `= ANY (?, ?, ?)`, a syntax error only a real Postgres reports. The two array
            // bindings in this file that do use ANY are positional for exactly that reason,
            // and this one cannot be: the rest of the clause is named.
            sql.append(
                    """
                AND EXISTS (SELECT 1 FROM offer p
                            WHERE (p.id = o.id OR p.duplicate_of_id = o.id) AND p.portal IN (:portals))
                """);
            params.put("portals", query.portals());
        }
        // The window's four values partition the set, which is why "unknown" is one of them
        // rather than an absence: the three dated clauses all carry IS NOT NULL, so without
        // it their union is not the unfiltered list, and `starts_on` is set only where the
        // advert named a day somebody could resolve.
        sql.append(query.startWindow().clause());
        if (query.minMonths() != null) {
            // The IS NOT NULL is redundant — `NULL >= 6` already drops the row — and it is
            // written because the exclusion is a decision rather than a property of
            // three-valued logic somebody has to know: "at least six months" is a claim about
            // the offer, and an offer that says nothing does not make it.
            sql.append(" AND o.duration_months IS NOT NULL AND o.duration_months >= :minMonths\n");
            params.put("minMonths", query.minMonths());
        }
        if (query.possibleDuplicates()) {
            // A reason to look rather than a verdict, so it narrows the list instead of
            // changing what the list is: the archive axis and the score bands still apply.
            sql.append(" AND o.possible_duplicate_of_id IS NOT NULL\n");
        }
        if (query.topic() != null) {
            // A column predicate and nothing else. The scorer matched the alias against the
            // advert and stored the topic it found; a text search here would be a second matcher
            // with its own idea of a word boundary, and the two would disagree. Any band, the
            // unscored and the discarded included, because the band is a filter of its own.
            sql.append(
                    " AND (EXISTS (SELECT 1 FROM offer_score_reason r WHERE r.offer_id = o.id AND r.topic = :topic)");
            params.put("topic", query.topic());
            // The paraphrase half, when a measured floor exists: an advert that names none of the
            // aliases but sits near the topic's name. It widens this one filter and nothing else.
            if (topicNeighbourhood != null) {
                sql.append(topicNeighbourhood.sql());
                params.putAll(topicNeighbourhood.params());
            }
            sql.append(")\n");
        }
        if (query.deadlineOpen()) {
            // The opposite treatment of null from the clause immediately above, on purpose:
            // "still open" is the absence of proof that it closed, so an advert that states no
            // deadline has not missed one. Exactly the pair a later tidy-up harmonises into a
            // bug. `current_date` is the server's and never a date the browser sends: two
            // readers in two timezones must not get two lists.
            sql.append(" AND (o.apply_by IS NULL OR o.apply_by >= current_date)\n");
        }

        // The relatedness neighbourhood, and it goes in `sql` with every other filter rather
        // than anywhere near the ordering. It narrows the set without redefining the key, so the
        // cursor, the ORDER BY and the match count all keep working untouched — which is the
        // entire reason this is a filter and not a seventh sort.
        if (narrowing != null) {
            sql.append(narrowing.sql());
            params.putAll(narrowing.params());
        }

        // The page, kept out of `sql` — see the note on Filters.
        String page = "";
        Map<String, Object> pageParams = new LinkedHashMap<>();
        if (query.cursor() != null && !query.cursor().isBlank()) {
            // One row comparison walks the key, which is legal only while every column moves
            // in the same direction — so the direction belongs to the tuple, and the sort
            // composes both the clause and the ORDER BY from one expression.
            Cursor cursor = Cursor.parse(query.cursor(), query.sort());
            page = query.sort().pageClause();
            pageParams.put("cKey", query.sort().kind().bind(cursor.key()));
            pageParams.put("cAt", java.sql.Timestamp.from(cursor.at()));
            pageParams.put("cId", cursor.id());
        }
        return new Filters(archive, sql.toString(), page, params, pageParams);
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

        int total = jdbc.sql("SELECT count(*) FROM offer WHERE duplicate_of_id IS NULL AND archived_at IS NULL")
                .query(Integer.class)
                .single();
        int archived = jdbc.sql("SELECT count(*) FROM offer WHERE duplicate_of_id IS NULL AND archived_at IS NOT NULL")
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
        int survived =
                total - stages.stream().mapToInt(FunnelView.Stage::removed).sum();
        return new FunnelView(total, stages, survived, archived);
    }

    private static String capitalize(String label) {
        return label.isEmpty() ? label : Character.toUpperCase(label.charAt(0)) + label.substring(1);
    }

    public Optional<ShortlistEntry> find(long id) {
        // Not restricted to PASSED: the detail is also how somebody looks at an offer the
        // filter rejected and asks whether the rule was right.
        return jdbc.sql(
                        """
                SELECT o.*, s.name AS source_name
                FROM offer o JOIN source s ON s.id = o.source_id
                WHERE o.id = ?
                """)
                .param(id)
                .query(OfferQueryService::row)
                .optional()
                .map(row -> entry(row, reasonsFor(List.of(id)), clustersFor(List.of(id)), true));
    }

    private ShortlistEntry entry(
            Row row, Map<Long, List<ScoreReason>> reasons, Map<Long, List<OfferSourceRef>> clusters) {
        return entry(row, reasons, clusters, false);
    }

    /**
     * @param withContent whether to parse the advert's blocks. Only the detail asks for them:
     *                    the list shares this mapper and would otherwise carry a second copy
     *                    of every advert for a column no card renders.
     */
    private ShortlistEntry entry(
            Row row,
            Map<Long, List<ScoreReason>> reasons,
            Map<Long, List<OfferSourceRef>> clusters,
            boolean withContent) {
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
                new OfferFlags(
                        row.enrichedAt == null || row.enrichmentNote != null,
                        row.remotePercent == null,
                        row.possibleDuplicateOfId != null),
                sources,
                withContent ? ContentText.parse(row.contentBlocks) : List.of());
    }

    private Map<Long, List<ScoreReason>> reasonsFor(List<Long> ids) {
        Map<Long, List<ScoreReason>> byOffer = new LinkedHashMap<>();
        jdbc.sql(
                        """
                    SELECT offer_id, factor, label, points, max_points, topic FROM offer_score_reason
            WHERE offer_id = ANY (?) ORDER BY offer_id, position
            """)
                .param(ids.toArray(Long[]::new))
                .query((rs, index) -> {
                    byOffer.computeIfAbsent(rs.getLong("offer_id"), key -> new ArrayList<>())
                            .add(new ScoreReason(
                                    rs.getString("factor"),
                                    rs.getString("label"),
                                    rs.getInt("points"),
                                    // Zero for a row written before the column existed, which
                                    // renders as no denominator rather than as "0 of 0".
                                    rs.getInt("max_points"),
                                    rs.getString("topic")));
                    return null;
                })
                .list();
        return byOffer;
    }

    /**
     * The other portals advertising the same project, keyed by the primary they point at.
     */
    private Map<Long, List<OfferSourceRef>> clustersFor(List<Long> ids) {
        Map<Long, List<OfferSourceRef>> byPrimary = new LinkedHashMap<>();
        jdbc.sql(
                        """
            SELECT duplicate_of_id, portal, agency, url FROM offer
            WHERE duplicate_of_id = ANY (?) ORDER BY duplicate_of_id, id
            """)
                .param(ids.toArray(Long[]::new))
                .query((rs, index) -> {
                    byPrimary
                            .computeIfAbsent(rs.getLong("duplicate_of_id"), key -> new ArrayList<>())
                            .add(new OfferSourceRef(
                                    rs.getString("portal"), rs.getString("agency"), rs.getString("url")));
                    return null;
                })
                .list();
        return byPrimary;
    }

    /**
     * The row plus the few columns the entry needs but the view does not carry.
     */
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
            /** The raw JSON. Parsed only for the detail — see {@code entry(..., withContent)}. */
            String contentBlocks,
            /** Carried only so the cursor can name the row it stopped at. */
            java.time.Instant ingestedAt,
            /** The older offer the similarity pass thinks this might be, or null. */
            Long possibleDuplicateOfId) {}

    private static Row row(ResultSet rs, int index) throws SQLException {
        long id = rs.getLong("id");
        var ingestedAt = rs.getTimestamp("ingested_at").toInstant();
        var offer = new OfferView(
                id,
                rs.getString("source_name"),
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
                rs.getString("start_text"),
                rs.getString("duration"),
                rs.getObject("duration_months", Integer.class),
                rs.getObject("apply_by", LocalDate.class),
                rs.getString("apply_by_text"),
                rs.getString("workload"),
                rs.getString("language"),
                rs.getString("full_text"),
                rs.getString("package_dir"),
                ingestedAt,
                rs.getTimestamp("archived_at") == null
                        ? null
                        : rs.getTimestamp("archived_at").toInstant(),
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
                rs.getString("content_blocks"),
                ingestedAt,
                // `getObject` with the type, never `getLong`: that one answers 0 for SQL NULL
                // and 0 is an offer id nobody has, so every row would carry a badge.
                rs.getObject("possible_duplicate_of_id", Long.class));
    }

    private static List<String> tags(ResultSet rs) throws SQLException {
        var array = rs.getArray("tags");
        return array == null ? List.of() : List.of((String[]) array.getArray());
    }

    /**
     * `timestamptz` does not convert straight to an `Instant`; the driver throws instead.
     */
    private static java.time.Instant instant(ResultSet rs) throws SQLException {
        var value = rs.getTimestamp("enriched_at");
        return value == null ? null : value.toInstant();
    }
}
