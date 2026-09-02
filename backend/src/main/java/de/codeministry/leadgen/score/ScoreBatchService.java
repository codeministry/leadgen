package de.codeministry.leadgen.score;

import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.config.model.MatchingRules;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The half of scoring that does not finish inside the run that started it.
 *
 * <p>Batched requests cost half and answer later, so a run hands them over and ends. What
 * would have happened at the end of that run — packaging, the digest — happens after the
 * collection instead, which is why {@code ScoreBatchCollector} exists and why this class
 * says whether anything actually changed.
 *
 * <p><b>`offer.score_batch_id` is what makes this safe.</b> The staleness guard asks what
 * still needs judging, and an offer already sitting in a submitted batch does not: without
 * the pointer the next run would submit it again and pay for the same answer twice. It is
 * also why "what is in flight" survives a restart — the answer is in the database, not in
 * a field on a bean.
 */
@Slf4j
@Service
public class ScoreBatchService {

    private static final String OPEN =
            """
            SELECT id, provider_id, model, ruleset_version, offers
            FROM score_batch
            WHERE status = 'SUBMITTED'
            ORDER BY id
            """;

    private static final String WAITING =
            "SELECT " + ScoreCandidate.COLUMNS + " FROM offer WHERE score_batch_id = ? ORDER BY id";

    private final ConfigRegistry config;
    private final Judges judges;
    private final ScoreWriter writer;
    private final JdbcClient jdbc;

    ScoreBatchService(ConfigRegistry config, Judges judges, ScoreWriter writer, DataSource dataSource) {
        this.config = config;
        this.judges = judges;
        this.writer = writer;
        this.jdbc = JdbcClient.create(dataSource);
    }

    /**
     * Hand the whole set over and record what is now in flight.
     *
     * <p>The deterministic half is written immediately, unscored. An offer waiting for a
     * batch therefore reads as an offer whose reasons are known and whose total is not,
     * which is a state this application already has a rendering for — and it means a batch
     * that never arrives costs nothing but the wait.
     *
     * @return how many offers were submitted, or zero when the submission did not happen.
     *     Zero is not a failure: nothing was written, nothing is in flight, and every offer
     *     is still due for the next run.
     */
    @Transactional
    int submit(BatchJudge judge, List<ScoreCandidate> due, RuleScorer scorer, MatchingRules rules) {
        Optional<String> providerId = judge.submit(due);
        if (providerId.isEmpty()) {
            return 0;
        }
        String rulesetVersion = String.valueOf(rules.version());
        int autoShortlist = rules.scoring().thresholds().autoShortlist();
        int review = rules.scoring().thresholds().review();

        Long batchId = jdbc.sql(
                        """
                        INSERT INTO score_batch (provider_id, model, ruleset_version, offers)
                        VALUES (?, ?, ?, ?)
                        RETURNING id
                        """)
                .params(providerId.get(), judge.model(), rulesetVersion, due.size())
                .query(Long.class)
                .single();

        for (ScoreCandidate candidate : due) {
            writer.write(candidate.id(), Score.unscored(scorer.score(candidate), rulesetVersion), autoShortlist, review);
            jdbc.sql("UPDATE offer SET score_batch_id = ? WHERE id = ?")
                    .params(batchId, candidate.id())
                    .update();
        }
        log.info("Submitted {} offers as batch {} to {}", due.size(), providerId.get(), judge.model());
        return due.size();
    }

    /**
     * Ask every batch still in flight whether it has ended, and write what came back.
     *
     * <p>Three answers and three different things to do, which is why the outcome is not a
     * nullable map: ask again, write these, or give up on this one. Both of the last two
     * clear the pointer — an offer must never be held by a batch that is finished, whatever
     * the reason it finished.
     */
    @Transactional
    public ScoreBatchCollection collect() {
        List<OpenBatch> open = jdbc.sql(OPEN)
                .query((rs, row) -> new OpenBatch(
                        rs.getLong("id"),
                        rs.getString("provider_id"),
                        rs.getString("model"),
                        rs.getInt("offers")))
                .list();
        if (open.isEmpty()) {
            return ScoreBatchCollection.nothing();
        }

        Optional<Judge> current = judges.current();
        if (current.isEmpty() || !(current.get() instanceof BatchJudge judge)) {
            // The configuration moved to a provider that cannot collect these. Failing them
            // releases the offers so the next run judges them normally; leaving them in
            // flight would hold them until somebody noticed, and nothing would say so.
            for (OpenBatch batch : open) {
                fail(batch, "no batching judge is configured any more, so this batch cannot be collected");
            }
            log.warn("{} submitted batches were given up on: nothing configured can collect them", open.size());
            return new ScoreBatchCollection(open.size(), 0);
        }

        MatchingRules rules = config.snapshot().rules();
        RuleScorer scorer = new RuleScorer(rules, config.snapshot().profile());
        int ended = 0;
        int scored = 0;

        for (OpenBatch batch : open) {
            BatchOutcome outcome = judge.collect(batch.providerId());
            switch (outcome.status()) {
                case PENDING -> log.debug("Batch {} has not ended yet", batch.providerId());
                case FAILED -> {
                    fail(batch, outcome.note());
                    ended++;
                    log.warn("Batch {} was given up on: {}", batch.providerId(), outcome.note());
                }
                case ENDED -> {
                    scored += writeAll(batch, outcome.reasons(), scorer, rules);
                    jdbc.sql("UPDATE score_batch SET status = 'COLLECTED', collected_at = now() WHERE id = ?")
                            .param(batch.id())
                            .update();
                    release(batch);
                    ended++;
                    log.info("Batch {} collected: {} of {} offers came back with an answer",
                            batch.providerId(), outcome.reasons().size(), batch.offers());
                }
            }
        }
        return new ScoreBatchCollection(ended, scored);
    }

    /**
     * <b>An offer with no answer stays unscored, it does not get a total from half the
     * weights.</b> The same rule the keyless path follows: five of nine weights do not make
     * a number comparable to one from all nine. Written that way it is also self-healing,
     * because the staleness guard sees a null model and makes the offer due again.
     */
    private int writeAll(OpenBatch batch, Map<Long, List<ScoreReason>> judged, RuleScorer scorer, MatchingRules rules) {
        String rulesetVersion = String.valueOf(rules.version());
        int autoShortlist = rules.scoring().thresholds().autoShortlist();
        int review = rules.scoring().thresholds().review();
        int scored = 0;

        for (ScoreCandidate candidate : jdbc.sql(WAITING).param(batch.id()).query(ScoreCandidate::of).list()) {
            List<ScoreReason> reasons = new java.util.ArrayList<>(scorer.score(candidate));
            List<ScoreReason> answer = judged.get(candidate.id());
            if (answer == null) {
                writer.write(candidate.id(), Score.unscored(reasons, rulesetVersion), autoShortlist, review);
                continue;
            }
            reasons.addAll(answer);
            writer.write(
                    candidate.id(), Score.of(reasons, batch.model(), rulesetVersion), autoShortlist, review);
            scored++;
        }
        return scored;
    }

    private void fail(OpenBatch batch, String note) {
        jdbc.sql("UPDATE score_batch SET status = 'FAILED', collected_at = now(), note = ? WHERE id = ?")
                .params(note, batch.id())
                .update();
        release(batch);
    }

    /** By batch rather than by offer: an entry missing from the results still has to be let go. */
    private void release(OpenBatch batch) {
        jdbc.sql("UPDATE offer SET score_batch_id = NULL WHERE score_batch_id = ?")
                .param(batch.id())
                .update();
    }

    private record OpenBatch(long id, String providerId, String model, int offers) {}
}
