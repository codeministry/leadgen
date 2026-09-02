/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.score;

import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.config.ConfigSnapshot;
import de.codeministry.leadgen.config.model.MatchingRules;
import de.codeministry.leadgen.config.model.PipelineConfig;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns what survived the filter into a ranked shortlist, with the reason behind every
 * number.
 *
 * <p>Two halves. {@link RuleScorer} decides everything the profile and the offer's own
 * fields can decide, for free. A {@link Judge} is asked about the rest — role fit and the
 * three penalties — and there may be none: with no key configured the pipeline still runs
 * and produces an unscored shortlist rather than failing.
 *
 * <p><b>Unscored is not zero.</b> The deterministic reasons are written either way, so the
 * operator sees "+45 core skill overlap, +10 rate fit" and can sort by judgement of their
 * own. What is withheld is the total: computed from five of the nine weights it would not
 * be comparable to one computed from all nine, and the same offer would score differently
 * depending on whether a key happened to be configured that morning.
 */
@Slf4j
@Service
public class ScoringService {

    /**
     * <b>What still needs judging, not everything that ever passed.</b> Enrichment already
     * works this way and scoring did not, so every run paid a language-model call for every
     * offer still open — a bill that grows with the standing backlog rather than with the
     * day's inflow, and grows silently, because a re-judged offer produces the same number
     * as before.
     *
     * <p>Three things make a score stale, and they are the three the score is only
     * comparable within: it was never written, the weights that produced it have changed,
     * or a different model answered. The last one is not caution about the model being
     * worse. A total from one judge and a total from another are two scales, and the
     * shortlist threshold is a single number read against both.
     *
     * <p>An offer sitting in a submitted batch is not due either, and that is the whole
     * job of `score_batch_id`: its answer is bought and on its way, so asking again would
     * be paying twice for it.
     */
    private static final String DUE = "SELECT " + ScoreCandidate.COLUMNS
            + """

            FROM offer
            WHERE status = 'PASSED'
              AND duplicate_of_id IS NULL
              AND archived_at IS NULL
              AND score_batch_id IS NULL
              AND (scored_at IS NULL
                   OR ruleset_version IS DISTINCT FROM CAST(? AS TEXT)
                   OR score_model IS DISTINCT FROM CAST(? AS TEXT))
            ORDER BY id
            """;

    /**
     * <b>The counts are standing totals, and only `scored` is this run's.</b> The same
     * reason {@code IngestReport.merged} is: once a run judges only what changed, a second
     * run legitimately judges nothing, and a report of "0 shortlisted" reads as scoring
     * having stopped working rather than as there being nothing new to do.
     */
    private static final String STANDING =
            """
            SELECT count(*)                                            AS considered,
                   count(*) FILTER (WHERE score_value IS NULL)         AS unscored,
                   count(*) FILTER (WHERE score_band = 'SHORTLISTED')  AS shortlisted,
                   count(*) FILTER (WHERE score_band = 'REVIEW')       AS review
            FROM offer
            WHERE status = 'PASSED' AND duplicate_of_id IS NULL AND archived_at IS NULL
            """;

    /**
     * The same row as {@link #DUE}, for one offer and without the staleness guard. It keeps
     * the shortlist's own two conditions: a rejected offer never entered scoring, and a
     * duplicate is judged through its primary.
     */
    private static final String ONE = "SELECT " + ScoreCandidate.COLUMNS
            + """

            FROM offer
            WHERE id = ? AND status = 'PASSED' AND duplicate_of_id IS NULL AND archived_at IS NULL
            """;

    private final ConfigRegistry config;
    private final Judges judges;
    private final ScoreBatchService batches;
    private final ScoreWriter writer;
    private final JdbcClient jdbc;

    ScoringService(
            ConfigRegistry config,
            Judges judges,
            ScoreBatchService batches,
            ScoreWriter writer,
            DataSource dataSource) {
        this.config = config;
        this.judges = judges;
        this.batches = batches;
        this.writer = writer;
        this.jdbc = JdbcClient.create(dataSource);
    }

    /**
     * Refuses a model nobody configured, before a run does any work. Asked through this
     * service rather than through {@code Judges} directly, so the pipeline keeps one door
     * to the question of which judge answers.
     *
     * @throws Judges.UnknownModel when the model is not one of the configured ones.
     */
    public void checkModel(String requestedModel) {
        judges.check(requestedModel);
    }

    /** The configured default model. Keeps every caller that has no reason to choose one. */
    public ScoringReport run() {
        return run(null);
    }

    /**
     * Judges everything stale with the model the configuration names.
     *
     * @param requestedModel the model to judge with, or null for the configured default.
     *     <p><b>A parameter rather than a setting, and that is the whole comparison.</b>
     *     {@code score_model} is one of the three staleness criteria, so naming a different
     *     model here makes the entire standing shortlist due again and re-judges it on the
     *     new scale. That is the point — two totals from two judges are not comparable, and
     *     the shortlist threshold is a single number read against both — and it is also the
     *     bill: one full pass at the chosen model's price, every time the choice changes.
     * @throws Judges.UnknownModel when the model is not one of the configured ones.
     */
    @Transactional
    public ScoringReport run(String requestedModel) {
        ConfigSnapshot snapshot = config.snapshot();
        MatchingRules rules = snapshot.rules();
        MatchingRules.Scoring scoring = rules.scoring();
        if (scoring == null) {
            log.warn("No scoring section is configured; the shortlist stays unranked");
            return ScoringReport.nothing();
        }

        RuleScorer scorer = new RuleScorer(rules, snapshot.profile());
        String rulesetVersion = String.valueOf(rules.version());
        int autoShortlist = scoring.thresholds().autoShortlist();
        int review = scoring.thresholds().review();

        Optional<Judge> judge = judges.current(requestedModel);
        if (judge.isEmpty()) {
            log.warn("No language model is configured; offers keep their deterministic reasons and stay unscored");
        }
        String model = judge.map(Judge::model).orElse(null);

        List<ScoreCandidate> due = jdbc.sql(DUE)
                .params(rulesetVersion, model)
                .query(ScoreCandidate::of)
                .list();

        // Batched, the run ends here: the requests are handed over at half the price and
        // the answers arrive minutes later, so packaging and the digest move behind the
        // collection instead of behind this method. A submission that does not happen
        // leaves every offer due rather than quietly scoring at full price, because a flag
        // that says "half" and bills "full" is worse than one that does nothing.
        PipelineConfig.Llm llm = snapshot.application().llm();
        if (!due.isEmpty() && llm != null && llm.batch() && judge.orElse(null) instanceof BatchJudge batchJudge) {
            int submitted = batches.submit(batchJudge, due, scorer, rules);
            var handedOver = standing(0, submitted);
            log.info(
                    "Scoring: {} due, {} submitted as a batch; standing: {} considered, {} unscored",
                    due.size(),
                    submitted,
                    handedOver.considered(),
                    handedOver.unscored());
            return handedOver;
        }

        int judged = 0;

        for (ScoreCandidate candidate : due) {
            List<ScoreReason> reasons = new java.util.ArrayList<>(scorer.score(candidate));
            Score score;

            if (judge.isEmpty()) {
                score = Score.unscored(reasons, rulesetVersion);
            } else {
                reasons.addAll(judge.get().judge(candidate));
                score = Score.of(reasons, model, rulesetVersion);
                judged++;
            }
            writer.write(candidate.id(), score, autoShortlist, review);
        }

        var report = standing(judged, 0);
        log.info(
                "Scoring: {} due, {} judged; standing: {} considered, {} unscored, {} shortlisted, {} for review",
                due.size(),
                judged,
                report.considered(),
                report.unscored(),
                report.shortlisted(),
                report.review());
        return report;
    }

    /**
     * One offer, judged again because somebody asked, and the only way past the staleness
     * guard.
     *
     * <p>The guard exists so a run does not pay for the standing backlog every night, and
     * the price of it is that a score outlives the reason it was wrong: a judge that
     * answered badly, an ad whose original page only became reachable later, a rule that
     * was tightened between two runs. Without a way to ask again, the only correction
     * available is editing the database, which nobody does and everybody works around.
     *
     * <p><b>Always synchronous, even when the nightly pass is batched.</b> Somebody is
     * looking at the page. Batching trades latency for half the price on a bulk of
     * hundreds; on one offer it trades a visible answer for a cent.
     *
     * @return the new score, or empty when the offer is not on the shortlist at all —
     *     rejected by the filter or attached to a primary, neither of which scoring ever saw.
     * @throws NoJudge when nothing is configured to answer. The caller can say so; silently
     *     rewriting the deterministic half would look like the button did nothing.
     */
    /** The configured default model, for a caller with no reason to choose one. */
    public Optional<Score> rescore(long offerId) {
        return rescore(offerId, null);
    }

    @Transactional
    public Optional<Score> rescore(long offerId, String requestedModel) {
        ConfigSnapshot snapshot = config.snapshot();
        MatchingRules rules = snapshot.rules();
        MatchingRules.Scoring scoring = rules.scoring();
        if (scoring == null) {
            throw new NoJudge("no scoring section is configured, so there are no weights to score against");
        }
        Judge judge = judges.current(requestedModel)
                .orElseThrow(
                        () -> new NoJudge(
                                "no language model is configured, so there is nothing to ask; the deterministic reasons are already written"));

        List<ScoreCandidate> found =
                jdbc.sql(ONE).param(offerId).query(ScoreCandidate::of).list();
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ScoreCandidate candidate = found.getFirst();

        List<ScoreReason> reasons =
                new java.util.ArrayList<>(new RuleScorer(rules, snapshot.profile()).score(candidate));
        reasons.addAll(judge.judge(candidate));
        Score score = Score.of(reasons, judge.model(), String.valueOf(rules.version()));

        writer.write(
                candidate.id(),
                score,
                scoring.thresholds().autoShortlist(),
                scoring.thresholds().review());
        log.info("Offer {} judged again on request: {} by {}", offerId, score.value(), judge.model());
        return Optional.of(score);
    }

    /** Nothing can answer. A reason rather than a stack trace, because it reaches a button. */
    public static class NoJudge extends RuntimeException {
        NoJudge(String message) {
            super(message);
        }
    }

    /** Everything but `scored` is counted from the table, so a quiet run still reports the list. */
    private ScoringReport standing(int judged, int submitted) {
        return jdbc.sql(STANDING)
                .query((rs, row) -> new ScoringReport(
                        rs.getInt("considered"),
                        judged,
                        rs.getInt("unscored"),
                        rs.getInt("shortlisted"),
                        rs.getInt("review"),
                        submitted))
                .single();
    }
}
