package de.codeministry.leadgen.config;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * What the sources screen reads: the configured sources, and what each one last did.
 *
 * <p>The configuration is the list, not the database. A source that has never run still
 * has to appear — otherwise a misconfigured source is invisible in exactly the situation
 * where somebody is looking for it.
 */
@Service
public class SourceQueryService {

    private static final String LAST_RUN =
            """
            SELECT DISTINCT ON (s.name) s.name, r.ran_at, r.documents, r.extracted, r.announced
            FROM source s JOIN source_run r ON r.source_id = s.id
            ORDER BY s.name, r.ran_at DESC
            """;

    /**
     * Primaries only, the same set the shortlist and the funnel count. Counting duplicates
     * as survivors makes this column disagree with the list it is about — 104 here against
     * 96 on the screen, with nothing saying which is right.
     */
    private static final String SURVIVORS =
            """
            SELECT s.name, count(*) FILTER (WHERE o.status = 'PASSED' AND o.duplicate_of_id IS NULL) AS survived
            FROM source s LEFT JOIN offer o ON o.source_id = s.id
            GROUP BY s.name
            """;

    private final ConfigRegistry config;
    private final ConfigProperties properties;
    private final JdbcClient jdbc;

    SourceQueryService(ConfigRegistry config, ConfigProperties properties, DataSource dataSource) {
        this.config = config;
        this.properties = properties;
        this.jdbc = JdbcClient.create(dataSource);
    }

    public List<SourceSummary> summaries() {
        Map<String, Run> runs = runs();
        Map<String, Integer> survivors = survivors();
        // One lookup for the whole file: every source in it came from the same layer,
        // because the two layers override each other file by file and never key by key.
        String layer = ConfigSource.resolve(properties.configDirectory(), ConfigLoader.SOURCES_FILE)
                .map(source -> source.isDefault() ? "default" : "config-dir")
                .orElse("default");

        List<SourceSummary> summaries = new ArrayList<>();
        for (var source : config.snapshot().sources().sources()) {
            Run run = runs.get(source.id());
            summaries.add(new SourceSummary(
                    source.id(),
                    source.type(),
                    source.enabled(),
                    layer,
                    run == null ? null : run.ranAt,
                    run == null ? 0 : run.documents,
                    run == null ? 0 : run.extracted,
                    run == null ? null : run.announced,
                    survivors.getOrDefault(source.id(), 0)));
        }
        return summaries;
    }

    private Map<String, Run> runs() {
        Map<String, Run> byName = new LinkedHashMap<>();
        jdbc.sql(LAST_RUN)
                .query((rs, index) -> byName.put(
                        rs.getString("name"),
                        new Run(
                                rs.getTimestamp("ran_at").toInstant(),
                                rs.getInt("documents"),
                                rs.getInt("extracted"),
                                rs.getObject("announced", Integer.class))))
                .list();
        return byName;
    }

    private Map<String, Integer> survivors() {
        Map<String, Integer> byName = new LinkedHashMap<>();
        jdbc.sql(SURVIVORS)
                .query((rs, index) -> byName.put(rs.getString("name"), rs.getInt("survived")))
                .list();
        return byName;
    }

    private record Run(Instant ranAt, int documents, int extracted, Integer announced) {}
}
