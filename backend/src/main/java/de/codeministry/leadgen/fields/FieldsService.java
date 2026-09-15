/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.fields;

import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.config.model.PipelineConfig;
import de.codeministry.leadgen.content.ContentText;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Reads the start, the duration and the application deadline out of an advert.
 *
 * <p>Runs after content segmentation, because it reads the advert through {@link ContentText}
 * and must not find a deadline in a portal footer, and before scoring, because what it writes
 * feeds {@code RuleScorer}'s {@code project_setup} factor and the judge's description of an
 * offer.
 *
 * <p><b>Deliberately not {@code @Transactional}</b>, the shape {@code EnrichmentService},
 * {@code ContentService} and {@code ScoringService} all document: each offer is one statement,
 * nothing needs atomicity across offers, and a pass that waits on a model for minutes would
 * otherwise hold a write lock on every offer it had touched — with any concurrent filter
 * stage, which writes a verdict on every row, sitting behind it.
 */
@Slf4j
@Service
public class FieldsService {

    /**
     * Never read this way, plus — once an extractor exists — everything a run without one
     * left unasked. The second half is the self-healing shape {@code score_model IS NULL} and
     * {@code content_model IS NULL} already use: configure a key at five in the afternoon and
     * the standing backlog becomes due, with no migration and nothing to remember.
     *
     * <p>Without a model the stage does not run at all, so there is no second query here the
     * way {@code ContentService} has one: there is no deterministic half that could decide
     * anything for free.
     */
    private static final String DUE = """
        SELECT id, title, description, full_text, content_blocks, starts_on, duration
        FROM offer
        WHERE status = 'PASSED' AND duplicate_of_id IS NULL AND archived_at IS NULL
          AND (fields_at IS NULL OR fields_model IS NULL)
        ORDER BY id
        """;

    /**
     * {@code fields_at} is stamped only when the pass is finished with the offer: a model that
     * was configured and did not answer leaves it null, so the offer comes back rather than
     * being remembered as read by nobody.
     *
     * <p>Nulling {@code score_model} is what makes scoring read this again, and it fires only
     * when a value actually changed — an advert that states none of the three, or states what
     * the row already said, costs no re-judge. It is the mechanism already documented as
     * self-healing rather than a fourth staleness criterion invented for the scoring stage.
     */
    private static final String RECORD =
        """
            UPDATE offer
            SET start_text = ?,
                starts_on = ?,
                duration = ?,
                duration_months = ?,
                apply_by_text = ?,
                apply_by = ?,
                fields_at = now(),
                fields_model = ?,
                score_model = CASE WHEN ?::boolean THEN NULL ELSE score_model END
            WHERE id = ?
            """;

    private final ConfigRegistry config;
    private final FieldExtractors extractors;
    private final JdbcClient jdbc;

    FieldsService(ConfigRegistry config, FieldExtractors extractors, DataSource dataSource) {
        this.config = config;
        this.extractors = extractors;
        this.jdbc = JdbcClient.create(dataSource);
    }

    public FieldsReport run() {
        PipelineConfig.Fields settings = config.snapshot().application().fields();
        if (settings == null || !settings.enabled()) {
            log.info("Field extraction is disabled; start and duration stay as the enrichment patterns left them");
            return FieldsReport.skipped();
        }

        Optional<FieldExtractor> extractor = extractors.current();
        if (extractor.isEmpty()) {
            // Rules before model: without one the columns keep whatever the regexes wrote,
            // which is less than this stage would find and is not nothing. Saying so out
            // loud, because a silently skipped stage looks exactly like an advert that
            // stated nothing.
            log.info("Field extraction needs a language model and none is configured; skipping");
            return FieldsReport.skipped();
        }

        String model = extractor.get().model();
        List<Due> due = jdbc.sql(DUE).query(FieldsService::due).list();

        int extracted = 0;
        int requests = 0;
        int stated = 0;
        int rejudged = 0;

        for (Due offer : due) {
            requests++;
            Optional<ExtractedFields> answer = extractor.get().extract(offer.asCandidate());
            if (answer.isEmpty()) {
                // Not settled: the offer keeps its columns and comes back on the next run.
                continue;
            }
            ExtractedFields fields = answer.get();
            boolean changed = fields.changes(offer.startsOn(), offer.duration());
            record(offer.id(), fields, model, changed);
            extracted++;
            if (!fields.isEmpty()) {
                stated++;
            }
            if (changed) {
                rejudged++;
            }
        }

        var report = new FieldsReport(due.size(), extracted, requests, stated, rejudged);
        log.info(
            "Fields: {} due, {} read, {} asked a model, {} stated something, {} have to be judged again",
            report.considered(),
            report.extracted(),
            report.requests(),
            report.stated(),
            report.rejudged());
        return report;
    }

    private void record(long id, ExtractedFields fields, String model, boolean changed) {
        jdbc.sql(RECORD)
            .params(
                fields.startText(),
                fields.startsOn(),
                fields.durationText(),
                fields.durationMonths(),
                fields.applyByText(),
                fields.applyBy(),
                model,
                changed,
                id)
            .update();
    }

    private static Due due(ResultSet rs, int row) throws SQLException {
        return new Due(
            rs.getLong("id"),
            rs.getString("title"),
            rs.getString("description"),
            // The advert as the content stage left it, never the raw page: a deadline
            // read out of a portal footer is the same class of error as a tag cloud
            // counted as skill overlap.
            ContentText.of(rs.getString("content_blocks"), rs.getString("full_text")),
            rs.getObject("starts_on", LocalDate.class),
            rs.getString("duration"));
    }

    private record Due(
        long id, String title, String description, String advert, LocalDate startsOn, String duration) {

        FieldExtractor.Candidate asCandidate() {
            return new FieldExtractor.Candidate(id, title, description, advert, startsOn, duration);
        }
    }
}
