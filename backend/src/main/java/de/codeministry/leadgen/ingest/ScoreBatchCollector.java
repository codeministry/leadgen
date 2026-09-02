package de.codeministry.leadgen.ingest;

import de.codeministry.leadgen.analytics.PipelineRunRecorder;
import de.codeministry.leadgen.digest.DigestService;
import de.codeministry.leadgen.packaging.PackagingService;
import de.codeministry.leadgen.score.ScoreBatchCollection;
import de.codeministry.leadgen.score.ScoreBatchService;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The second half of a batched run, arriving minutes after the first.
 *
 * <p>{@code IngestService} ends the moment the scoring requests are handed over, because
 * the answers are not coming back inside that request. Packaging and the digest are what
 * would have followed, and they still have to follow — just from here. Same order, same two
 * calls, and the digest is still the last thing that happens.
 *
 * <p><b>It lives beside {@code IngestService} rather than in the score package</b>, because
 * this is the class that knows what a finished run consists of. Scoring knows how to write
 * a score, and it has no business knowing that a package folder exists.
 *
 * <p>The poll runs whether or not batching is configured. That is deliberate: the check is
 * one indexed query for submitted batches, and switching the flag off must not strand the
 * batches that were already handed over.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class ScoreBatchCollector {

    private final ScoreBatchService batches;
    private final PackagingService packaging;
    private final DigestService digest;
    private final PipelineRunRecorder history;

    /**
     * <b>Nothing here may throw.</b> A scheduled method that does gets its stack trace
     * logged by the framework and is then simply run again next time, which turns a
     * misconfigured provider into a wall of identical traces every five minutes.
     */
    @Scheduled(fixedDelayString = "${leadgen.score-batch-poll-interval:PT5M}")
    void poll() {
        try {
            ScoreBatchCollection collected = batches.collect();
            if (!collected.anythingHappened()) {
                return;
            }
            // Packaging before the digest, so the digest can say which offers already have
            // a folder. The same order the run itself uses, for the same reason.
            var packages = packaging.run();
            var written = digest.render(LocalDate.now()).orElse(null);
            // The run that submitted this batch left a row saying so. It is finished here,
            // not by the request that started it: that request returned before any of this
            // existed, and a row completed there would state the previous run's shortlist.
            history.complete(collected.scored(), packages.built(), written != null);
            log.info("Collected {} scoring batch(es), {} offers scored; {} package(s) built, digest {}",
                    collected.ended(), collected.scored(), packages.built(),
                    written == null ? "not written" : written);
        } catch (RuntimeException e) {
            log.error("Collecting the scoring batches failed: {}", e.getMessage(), e);
        }
    }
}
