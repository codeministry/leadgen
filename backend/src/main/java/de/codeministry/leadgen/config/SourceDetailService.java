/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.config;

import de.codeministry.leadgen.config.model.SourcesConfig;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * One source, opened: the block that defines it and the runs it has had.
 *
 * <p>Apart from {@link SourceQueryService} because it answers a different question with
 * different SQL — that one is every source's latest run, this one is one source's every run —
 * and because only this one reads file text, which is the half that can do damage.
 *
 * <h2>What must not reach the browser</h2>
 *
 * <p><b>The file's own bytes, never the bound snapshot.</b> The snapshot has every
 * {@code ${IMAP_PASSWORD}} already resolved to what it stands for, and this endpoint stands
 * behind nothing: {@code security.auth} has one implemented value, and the container overrides
 * {@code server.address} off loopback because a process bound to it inside one is reachable
 * through nothing at all. The bytes also carry the comments, which are most of what makes a
 * block worth showing.
 *
 * <p><b>Masked anyway</b>, by {@link YamlMask}, because the second configuration layer exists
 * precisely so that somebody can write their own file, and nothing stops them writing a literal
 * where the shipped one writes a placeholder.
 *
 * <p><b>The id selects, it never addresses.</b> It is looked up in the snapshot's own list and
 * answers empty when it names nothing; it is never joined to a path, because a request
 * parameter that reaches the filesystem is the shape of every directory traversal.
 */
@Service
public class SourceDetailService {

    /**
     * The window's rows, newest first, each carrying what the run before it extracted.
     *
     * <p>The {@code lag()} is the whole reason this is SQL rather than a loop: "changed" is
     * decided once, on the server, beside the rows it describes. The subquery orders ascending
     * so the window looks backwards in time and the outer query turns it around, because the
     * panel reads newest first.
     *
     * <p>{@code ran_at::date} is deliberate. A mailbox read at 03:14 says when the operator's
     * cron runs and nothing this panel is for, and this screen's pictures get published.
     */
    private static final String HISTORY = """
        SELECT h.ran_on, h.documents, h.extracted, h.written, h.announced, h.previous_extracted
        FROM (SELECT r.ran_at,
                     r.ran_at::date AS ran_on,
                     r.documents,
                     r.extracted,
                     r.written,
                     r.announced,
                     lag(r.extracted) OVER (ORDER BY r.ran_at) AS previous_extracted
              FROM source s
                       JOIN source_run r ON r.source_id = s.id
              WHERE s.name = ?) h
        ORDER BY h.ran_at DESC
        LIMIT ?
        """;

    /**
     * Fifty is well past what anybody reads and still one screen of scrolling; the panel says
     * how many it is showing either way, so the list never pretends to be the whole history.
     */
    static final int MAX_RUNS = 50;

    static final int DEFAULT_RUNS = 30;

    private final ConfigRegistry config;
    private final ConfigProperties properties;
    private final JdbcClient jdbc;

    SourceDetailService(ConfigRegistry config, ConfigProperties properties, DataSource dataSource) {
        this.config = config;
        this.properties = properties;
        this.jdbc = JdbcClient.create(dataSource);
    }

    public Optional<SourceDetail> detail(String id, int runs) {
        Optional<SourcesConfig.Source> configured = config.snapshot().sources().sources().stream()
                .filter(source -> source.id().equals(id))
                .findFirst();
        if (configured.isEmpty()) {
            return Optional.empty();
        }
        SourcesConfig.Source source = configured.get();

        // Read at open rather than carried over from the list: the file is hot-reloadable, so
        // between the table load and this click it may have been rewritten, and a stale block
        // beside a current history is the worst of both.
        Optional<ConfigSource> file = ConfigSource.resolve(properties.configDirectory(), ConfigLoader.SOURCES_FILE);
        // Cut from the file's own bytes, then mask what is actually shown. The other order —
        // mask the whole file and cut the result — was tried and is broken by YAML itself: the
        // mask is `********`, a plain scalar beginning with `*` is an alias, and the masked
        // document stops parsing, so every block lookup after it comes back empty. Found by the
        // test with a literal password in it, which is the only fixture that can find it.
        //
        // The connection is looked up separately for the same reason it is shown at all: an
        // imap source is half-defined by a block somewhere else in the file. Cutting does not
        // narrow what gets masked, because both blocks go through the masker.
        String text = file.map(ConfigSource::content).orElse("");

        YamlBlock block = masked(YamlBlocks.item(text, "sources", id).orElse(null));
        YamlBlock connection = source.connection() == null
                ? null
                : masked(YamlBlocks.item(text, "connections", source.connection())
                        .orElse(null));

        List<Row> history = history(id, Math.clamp(runs <= 0 ? DEFAULT_RUNS : runs, 1, MAX_RUNS));
        return Optional.of(new SourceDetail(
                source.id(),
                source.type(),
                source.enabled(),
                new SourceDetail.ConfigFile(
                        ConfigLoader.SOURCES_FILE,
                        file.map(ConfigSource::isDefault).orElse(true) ? "default" : "config-dir",
                        file.map(ConfigSource::origin).orElse(null)),
                block,
                connection,
                history.stream().map(Row::run).toList(),
                trend(history)));
    }

    /**
     * The block as it may be published. The line numbers are the file's own and survive
     * untouched, because masking is line for line: every line in, one line out.
     */
    private static YamlBlock masked(YamlBlock block) {
        return block == null
                ? null
                : new YamlBlock(YamlMask.apply(block.text()).stripTrailing(), block.firstLine(), block.lastLine());
    }

    private List<Row> history(String id, int limit) {
        return jdbc.sql(HISTORY)
                .params(id, limit)
                .query((rs, row) -> new Row(
                        rs.getObject("ran_on", LocalDate.class),
                        rs.getInt("documents"),
                        rs.getInt("extracted"),
                        rs.getInt("written"),
                        (Integer) rs.getObject("announced"),
                        (Integer) rs.getObject("previous_extracted")))
                .list();
    }

    /**
     * The most recent row where something moved, walking back from the newest.
     *
     * <p>The comparison itself came out of the {@code lag()} window; what happens here is
     * picking the first row that already says so, which is reading the answer rather than
     * forming a second opinion about it.
     *
     * <p>A source whose count has been flat for the whole window reports no change rather than
     * a change at the oldest row — the window's edge is not an event, and saying "changed on
     * 18 Aug" because that is as far back as we looked would be the panel inventing one.
     */
    private SourceTrend trend(List<Row> history) {
        if (history.isEmpty()) {
            return new SourceTrend(null, null, null, null, null, false);
        }
        LocalDate changedOn = null;
        Integer before = null;
        for (Row row : history) {
            if (row.previous() != null && row.previous() != row.extracted()) {
                changedOn = row.ranOn();
                before = row.previous();
                break;
            }
        }
        LocalDate divergedOn = null;
        Integer missing = null;
        boolean stated = false;
        for (Row row : history) {
            if (row.announced() == null) {
                continue;
            }
            stated = true;
            if (row.announced() != row.extracted() && divergedOn == null) {
                divergedOn = row.ranOn();
                missing = row.announced() - row.extracted();
            }
        }
        return new SourceTrend(changedOn, before, history.getFirst().extracted(), divergedOn, missing, stated);
    }

    /**
     * One history row, still carrying the previous run's count so the trend can be read off it.
     */
    private record Row(
            LocalDate ranOn, int documents, int extracted, int written, Integer announced, Integer previous) {

        SourceRun run() {
            return new SourceRun(ranOn, documents, extracted, written, announced);
        }
    }
}
