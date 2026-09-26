/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.fields;

import de.codeministry.leadgen.concurrent.BoundedWork;
import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.config.model.PipelineConfig;
import de.codeministry.leadgen.content.ContentText;
import de.codeministry.leadgen.llm.LlmBudget;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

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
@RequiredArgsConstructor
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
     *
     * <p>And, when {@code fields} holds a key of its own, everything read under a different
     * model: the parameter is the model this run's extractor asks, trimmed, never the raw key.
     * It is null when the scoring fallback answered, and {@code COALESCE} then compares the
     * column with itself — a changed {@code scoring} moves the judge, not this stage. A switched
     * {@code fields} key makes exactly this stage's adverts due again, and {@link #RECORD}
     * re-judges only those whose values moved.
     */
    private static final String DUE = """
        SELECT id, title, description, full_text, content_blocks,
               start_text, starts_on, duration, duration_months, apply_by_text, apply_by
        FROM offer
        WHERE status = 'PASSED' AND duplicate_of_id IS NULL AND archived_at IS NULL
          AND (fields_at IS NULL OR fields_model IS NULL
               OR fields_model IS DISTINCT FROM COALESCE(?::text, fields_model))
        ORDER BY id
        """;

    /**
     * The same predicate as {@link #DUE}, narrowed to one id and derived from it, so a change to
     * the condition cannot reach the night and miss the button. {@code runFor} is called by the
     * manual-fetch orchestrator after it has reset {@code fields_at} for that offer, so the
     * predicate already matches it; a second look here (rather than trusting the caller) is
     * what keeps the button from ever recording an offer the night would not touch — the same
     * reasoning {@code run()} applies to every row it reads.
     */
    private static final String DUE_FOR = DUE.replace("ORDER BY id", "AND id = ?");

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
    private static final String RECORD = """
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
    private final LlmBudget budget;
    private final JdbcClient jdbc;

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
        List<Due> due =
                jdbc.sql(DUE).param(pinned(model)).query(FieldsService::due).list();

        // Up to `llm.concurrency` adverts at once, each writing only its own row. A spent budget
        // stops the adverts not yet handed out: they keep their columns and stay due, which is
        // the same thing an unanswered request leaves behind — so the next run simply
        // continues. The ones already asking finish and keep their answers.
        PipelineConfig.Llm llm = config.snapshot().application().llm();
        int width = llm == null || due.isEmpty() ? 1 : llm.concurrency();
        List<Optional<Attempt>> attempts = BoundedWork.forEach(
                width, due, offer -> attempt(extractor.get(), model, offer), Attempt::budgetExhausted);

        int extracted = 0;
        int requests = 0;
        int stated = 0;
        int rejudged = 0;

        for (Optional<Attempt> entry : attempts) {
            if (entry.isEmpty() || entry.get().budgetExhausted()) {
                continue;
            }
            Attempt attempt = entry.get();
            requests++;
            if (attempt.fields().isEmpty()) {
                // Not settled: the offer keeps its columns and comes back on the next run.
                continue;
            }
            extracted++;
            if (!attempt.fields().get().isEmpty()) {
                stated++;
            }
            if (attempt.changed()) {
                rejudged++;
            }
        }

        var report = new FieldsReport(due.size(), extracted, requests, stated, rejudged, width);
        log.info(
                "Fields: {} due, {} read, {} asked a model, {} stated something, {} have to be judged again{}",
                report.considered(),
                report.extracted(),
                report.requests(),
                report.stated(),
                report.rejudged(),
                BoundedWork.atWidth(report.width()));
        return report;
    }

    /**
     * The one-offer path the manual-fetch orchestrator calls after a successful refetch, once
     * {@code fields_at} has been reset for that id. Disabled stage, no model configured, or the
     * offer not matching {@link #DUE_FOR} (already handled, archived, no longer PASSED) all mean
     * the same thing here as a night with nothing to do: nothing is written and nothing thrown —
     * the caller does not have to tell "skipped" apart from "the model did not answer".
     *
     * @return whether a model actually answered and its fields were recorded; {@code false}
     *     covers every way the stage did nothing, deliberately kept as one boolean rather than a
     *     reason enum, because the orchestrator's only decision is whether to move on
     */
    public boolean runFor(long id) {
        PipelineConfig.Fields settings = config.snapshot().application().fields();
        if (settings == null || !settings.enabled()) {
            return false;
        }

        Optional<FieldExtractor> extractor = extractors.current();
        if (extractor.isEmpty()) {
            return false;
        }

        Optional<Due> offer = jdbc.sql(DUE_FOR)
                .param(pinned(extractor.get().model()))
                .param(id)
                .query(FieldsService::due)
                .optional();
        if (offer.isEmpty()) {
            return false;
        }

        Attempt attempt = attempt(extractor.get(), extractor.get().model(), offer.get());
        return attempt.fields().isPresent();
    }

    /**
     * The per-offer step {@code run()} and {@code runFor} both need: the budget check, the
     * model call, and the write. Pulled out once both had to run it against a single {@link Due}
     * — {@code run()} still decides for itself whether a spent budget ends its loop or a
     * candidate is a candidate, which is why that decision stays outside this method rather than
     * inside it.
     */
    private Attempt attempt(FieldExtractor extractor, String model, Due offer) {
        if (!budget.take()) {
            return Attempt.noBudget();
        }
        Optional<ExtractedFields> answer = extractor.extract(offer.asCandidate());
        if (answer.isEmpty()) {
            return Attempt.notAnswered();
        }
        ExtractedFields fields = answer.get();
        // Against all six columns as the row holds them, not against "was anything stated": a
        // switched `fields` key re-reads every advert, and a new model that states what the old
        // one did must not buy a re-judge for a score that cannot move.
        boolean changed = !fields.equals(offer.current());
        record(offer.id(), fields, model, changed);
        return Attempt.recorded(fields, changed);
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

    /**
     * The model a switched key is compared against: the one this run asks, but only when
     * {@code llm.models.fields} named it. Decided from the configured value, the way
     * {@code ModelChoice.decidedBy} decides it, and not by comparing model names.
     */
    private String pinned(String asked) {
        PipelineConfig.Llm llm = config.snapshot().application().llm();
        String own = llm == null || llm.models() == null ? null : llm.models().fields();
        return own != null && !own.isBlank() ? asked : null;
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
                new ExtractedFields(
                        rs.getString("start_text"),
                        rs.getObject("starts_on", LocalDate.class),
                        rs.getString("duration"),
                        rs.getObject("duration_months", Integer.class),
                        rs.getString("apply_by_text"),
                        rs.getObject("apply_by", LocalDate.class)));
    }

    /**
     * @param current the six columns as the row holds them before this pass — whatever an
     *                earlier model or the enrichment patterns left — which is what an answer is
     *                compared against to decide whether scoring has to read it again
     */
    private record Due(long id, String title, String description, String advert, ExtractedFields current) {

        FieldExtractor.Candidate asCandidate() {
            return new FieldExtractor.Candidate(
                    id, title, description, advert, current.startsOn(), current.durationText());
        }
    }

    /**
     * What {@link #attempt} found out for one offer. {@code fields} empty with
     * {@code budgetExhausted} false is "the model did not answer" (the offer stays due);
     * {@code budgetExhausted} true is the one case {@code run()}'s loop has to tell apart from
     * that, because it is the only one that ends the whole pass rather than moving to the next
     * candidate.
     */
    private record Attempt(boolean budgetExhausted, Optional<ExtractedFields> fields, boolean changed) {

        private static Attempt noBudget() {
            return new Attempt(true, Optional.empty(), false);
        }

        private static Attempt notAnswered() {
            return new Attempt(false, Optional.empty(), false);
        }

        private static Attempt recorded(ExtractedFields fields, boolean changed) {
            return new Attempt(false, Optional.of(fields), changed);
        }
    }
}
