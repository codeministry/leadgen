package de.codeministry.leadgen.filter;

import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.config.ConfigSnapshot;
import java.sql.Array;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Applies {@link HardFilter} to everything stored and records the verdict on each row. */
@Slf4j
@Service
public class FilterService {

    /** An offer that has never been judged, or was judged under rules that have since changed. */
    private static final String SELECT_ALL =
            """
            SELECT id, title, description, location, tags, published_on
            FROM offer
            ORDER BY id
            """;

    private static final String RECORD_VERDICT =
            """
            UPDATE offer SET status = ?, filter_stage = ?, filter_reason = ? WHERE id = ?
            """;

    private final ConfigRegistry config;
    private final JdbcClient jdbc;
    private final JdbcTemplate template;

    FilterService(ConfigRegistry config, DataSource dataSource) {
        this.config = config;
        this.jdbc = JdbcClient.create(dataSource);
        this.template = new JdbcTemplate(dataSource);
    }

    /**
     * Judges every stored offer against the current rules and writes the verdict back.
     *
     * <p>Every offer, not only the ones never judged: the rules are hot-reloadable, and a
     * filter that skipped what it had already seen would leave the archive split between
     * two rule sets with nothing saying which row was decided under which.
     */
    @Transactional
    public FilterReport run() {
        return run(LocalDate.now());
    }

    /** @param today what the freshness rule measures against; see {@link HardFilter#judge}. */
    @Transactional
    public FilterReport run(LocalDate today) {
        ConfigSnapshot snapshot = config.snapshot();
        HardFilter filter = new HardFilter(snapshot.rules(), snapshot.profile());

        List<FilterCandidate> candidates = jdbc.sql(SELECT_ALL)
                .query((rs, row) -> new FilterCandidate(
                        rs.getLong("id"),
                        rs.getString("title"),
                        rs.getString("description"),
                        rs.getString("location"),
                        tags(rs.getArray("tags")),
                        rs.getObject("published_on", LocalDate.class)))
                .list();

        Map<FilterStage, Integer> removed = new EnumMap<>(FilterStage.class);
        List<Object[]> updates = new ArrayList<>(candidates.size());
        int passed = 0;

        for (FilterCandidate candidate : candidates) {
            FilterVerdict verdict = filter.judge(candidate, today);
            if (verdict.passed()) {
                passed++;
                updates.add(new Object[] {"PASSED", null, null, candidate.id()});
            } else {
                removed.merge(verdict.stage(), 1, Integer::sum);
                updates.add(new Object[] {
                    "FILTERED_OUT", verdict.stage().name(), verdict.reason(), candidate.id()
                });
            }
        }

        template.batchUpdate(RECORD_VERDICT, updates);

        FilterReport report = new FilterReport(Map.copyOf(removed), passed, candidates.size());
        log.info("Hard filter: {} of {} passed ({}), removed {}",
                report.passed(),
                report.considered(),
                "%.1f %%".formatted(report.passRate() * 100),
                removed.isEmpty() ? "nothing" : removed);
        return report;
    }

    private static List<String> tags(Array array) throws SQLException {
        return array == null ? List.of() : List.of((String[]) array.getArray());
    }
}
