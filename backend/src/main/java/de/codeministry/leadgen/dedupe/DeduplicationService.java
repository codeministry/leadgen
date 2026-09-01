package de.codeministry.leadgen.dedupe;

import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.config.model.MatchingRules.Deduplication;
import de.codeministry.leadgen.config.model.MatchingRules.Deduplication.Strategy;
import java.util.List;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Collapses the listings of one project into one cluster.
 *
 * <p>This is not the upsert in {@link de.codeministry.leadgen.ingest.store.OfferStore}.
 * That one collapses a <em>listing</em> seen twice, which is what re-reading a newsletter
 * produces. This one collapses one <em>project</em> that several portals advertise at
 * once, which is what 12.3 % of the measured corpus is.
 *
 * <p><b>The fingerprint is the normalized title and nothing else</b>, and that is a
 * measurement rather than a preference. The configured field list names {@code city},
 * {@code start_date}, {@code duration_months} and {@code top_skills}; all four arrive
 * from enrichment, which runs after this stage, so at this point only the title exists.
 * Adding the one field that does exist, the stated location, was measured over the corpus
 * and is worse: it collapses 111 offers instead of 159, and the 48 it gives up are
 * overwhelmingly correct merges lost to the same ad writing its location as "Nürnberg" in
 * one portal and "Remote und Nürnberg" in the next. A location has to be parsed before it
 * can be compared, and parsing it is enrichment's job. Until then the fingerprint stays
 * one field.
 */
@Slf4j
@Service
public class DeduplicationService {

    /** The only strategy that needs no model. The others are logged and skipped. */
    private static final String EXACT_FINGERPRINT = "exact_fingerprint";

    private static final String MERGE = "merge";

    /**
     * One statement, and idempotent by construction: the primary of a group is recomputed
     * from the group itself every run, so a second run assigns exactly what the first did
     * and a listing arriving later attaches to the primary that is already there instead
     * of starting a rival cluster.
     *
     * <p>The final predicate restricts the write to rows whose assignment actually
     * changes, which is what makes the returned count mean "moved" rather than "seen".
     */
    private static final String CLUSTER =
            """
            WITH ranked AS (
                SELECT id,
                       first_value(id) OVER (
                           PARTITION BY fingerprint
                           ORDER BY ingested_at, id
                       ) AS primary_id
                FROM offer
                WHERE fingerprint <> ''
                  AND ingested_at >= now() - make_interval(days => :ttl)
            )
            UPDATE offer o
            SET duplicate_of_id = CASE WHEN r.primary_id = o.id THEN NULL ELSE r.primary_id END
            FROM ranked r
            WHERE o.id = r.id
              AND o.duplicate_of_id IS DISTINCT FROM
                  (CASE WHEN r.primary_id = o.id THEN NULL ELSE r.primary_id END)
            """;

    private final ConfigRegistry config;
    private final JdbcClient jdbc;

    DeduplicationService(ConfigRegistry config, DataSource dataSource) {
        this.config = config;
        this.jdbc = JdbcClient.create(dataSource);
    }

    /**
     * Clusters every offer inside the configured window and returns how many are attached
     * to a primary afterwards.
     *
     * <p>The number returned is the standing total, not the rows this run moved. A second
     * run moves nothing, and reporting zero there would read as "deduplication stopped
     * working" rather than "there was nothing left to do".
     */
    @Transactional
    public int run() {
        Deduplication rules = config.snapshot().rules().deduplication();
        warnAboutUnsupported(rules.strategies());

        if (!mergesOnExactFingerprint(rules.strategies())) {
            log.warn("No 'exact_fingerprint' strategy with action 'merge' is configured; nothing is clustered");
            return attached(rules.ttlDays());
        }

        int moved = jdbc.sql(CLUSTER).param("ttl", rules.ttlDays()).update();
        int attached = attached(rules.ttlDays());
        log.info("Deduplication: {} offers attached to a primary within {} days, {} moved this run",
                attached, rules.ttlDays(), moved);
        return attached;
    }

    private int attached(int ttlDays) {
        return jdbc.sql(
                        """
                        SELECT count(*) FROM offer
                        WHERE duplicate_of_id IS NOT NULL
                          AND ingested_at >= now() - make_interval(days => :ttl)
                        """)
                .param("ttl", ttlDays)
                .query(Integer.class)
                .single();
    }

    private boolean mergesOnExactFingerprint(List<Strategy> strategies) {
        return strategies != null
                && strategies.stream()
                        .anyMatch(s -> EXACT_FINGERPRINT.equals(s.type()) && MERGE.equals(s.action()));
    }

    /**
     * Loud but not fatal. The shipped configuration lists two embedding strategies, so
     * failing here would break the defaults; running silently would leave the operator
     * believing a similarity pass happened. Both alternatives are worse than a warning.
     */
    private void warnAboutUnsupported(List<Strategy> strategies) {
        if (strategies == null) {
            return;
        }
        strategies.stream()
                .filter(s -> !EXACT_FINGERPRINT.equals(s.type()))
                .forEach(s -> log.warn(
                        "Deduplication strategy '{}' is configured but not implemented; it is skipped", s.type()));
    }
}
