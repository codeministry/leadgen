/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.content;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.config.model.PipelineConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Decides which parts of a fetched advert are the advert.
 *
 * <p>Runs after enrichment, because it needs {@code full_text}, and before scoring, because
 * scoring must read what this leaves rather than what the portal wrapped it in. A portal's
 * own tag cloud is sixty technology names the client never asked for, and folded into the
 * skill haystack it inflates the overlap that decides the shortlist — so this is a fix to the
 * score first and to the screen second.
 *
 * <p><b>Fail open, always.</b> A block that no rule matched, that the cache does not know and
 * that the model did not answer about stays {@link ContentKind#CONTENT} and stays on the
 * screen. Nothing is ever hidden without a positive decision to hide it, which is what makes
 * the whole feature safe to switch on before anybody trusts it.
 *
 * <p><b>Deliberately not {@code @Transactional}</b>, the shape {@code EnrichmentService} and
 * {@code ScoringService} both document: each offer is one statement, nothing needs atomicity
 * across offers, and a pass that waits on a model for minutes would otherwise hold a write
 * lock on every offer it had touched — with any concurrent filter stage, which writes a
 * verdict on every row, sitting behind it.
 */
@Slf4j
@Service
public class ContentService {

    /**
     * Never read this way, plus — once a classifier exists — everything a run without one
     * left half-decided. The second half is the self-healing shape {@code score_model IS NULL}
     * already uses: configure a key at five in the afternoon and the standing backlog becomes
     * due, with no migration and nothing to remember.
     */
    private static final String DUE = """
        SELECT id, portal, title, full_text FROM offer
        WHERE status = 'PASSED' AND archived_at IS NULL AND full_text IS NOT NULL
          AND content_at IS NULL
        ORDER BY id
        """;

    private static final String DUE_WITH_A_MODEL = """
        SELECT id, portal, title, full_text FROM offer
        WHERE status = 'PASSED' AND archived_at IS NULL AND full_text IS NOT NULL
          AND (content_at IS NULL OR content_model IS NULL)
        ORDER BY id
        """;

    /**
     * {@code content_at} is stamped only when the pass is finished with the offer: a model
     * that was configured and did not answer leaves it null, so the offer comes back rather
     * than being remembered as decided by nobody.
     *
     * <p>Nulling {@code score_model} is what makes scoring read this. It is the mechanism
     * already documented as self-healing rather than a fourth staleness criterion invented
     * for the scoring stage — and it fires only when something was actually taken out, so an
     * advert that is all advert costs no re-judge.
     */
    private static final String RECORD =
        """
            UPDATE offer
            SET content_blocks = ?::jsonb,
                content_at = CASE WHEN ?::boolean THEN now() ELSE NULL END,
                content_model = ?,
                content_undecided = ?,
                score_model = CASE WHEN ?::boolean THEN NULL ELSE score_model END
            WHERE id = ?
            """;

    private final ConfigRegistry config;
    private final Classifiers classifiers;
    private final BlockLabelStore labels;
    private final ObjectMapper json;
    private final JdbcClient jdbc;

    ContentService(ConfigRegistry config, Classifiers classifiers, BlockLabelStore labels, DataSource dataSource) {
        this.config = config;
        this.classifiers = classifiers;
        this.labels = labels;
        this.json = new ObjectMapper();
        this.jdbc = JdbcClient.create(dataSource);
    }

    public ContentReport run() {
        PipelineConfig.Content settings = config.snapshot().application().content();
        if (settings == null || !settings.enabled()) {
            log.info("Content segmentation is disabled; an advert is shown as the portal wrapped it");
            return ContentReport.skipped();
        }

        ContentRules rules = new ContentRules(settings.rules());
        Optional<ContentClassifier> classifier = classifiers.current();
        String model = classifier.map(ContentClassifier::model).orElse(null);

        List<Due> due = jdbc.sql(classifier.isPresent() ? DUE_WITH_A_MODEL : DUE)
            .query((rs, row) -> new Due(
                rs.getLong("id"), rs.getString("portal"), rs.getString("title"), rs.getString("full_text")))
            .list();

        int segmented = 0;
        int total = 0;
        int fromCache = 0;
        int requests = 0;
        int undecided = 0;

        for (Due offer : due) {
            Pass pass = segment(offer, rules, classifier);
            total += pass.blocks().size();
            fromCache += pass.fromCache();
            undecided += pass.undecided();
            if (pass.asked()) {
                requests++;
            }
            // The model is recorded whenever the pass finished under a configuration that had
            // one, whether or not this particular advert needed asking. It says which
            // configuration decided, and it is what stops a fully cached offer from being
            // picked up as unfinished on every run for the rest of its life.
            record(offer.id(), pass, pass.settled() ? model : null);
            if (pass.settled()) {
                segmented++;
            }
        }

        var report = new ContentReport(due.size(), segmented, total, fromCache, requests, undecided);
        log.info(
            "Content: {} due, {} segmented, {} blocks, {} already decided, {} asked a model,"
                + " {} nobody had a label for",
            report.considered(),
            report.segmented(),
            report.blocks(),
            report.fromCache(),
            report.requests(),
            report.undecided());
        return report;
    }

    /**
     * One advert, read block by block: a rule first because it is free and explicit, then the
     * cache because it is free and already decided, then the model for whatever is left.
     */
    private Pass segment(Due offer, ContentRules rules, Optional<ContentClassifier> classifier) {
        List<String> texts = MarkdownBlocks.split(offer.fullText());
        List<ContentBlock> blocks = new ArrayList<>(texts.size());
        List<ContentClassifier.Candidate> unknown = new ArrayList<>();
        int fromCache = 0;

        for (int index = 0; index < texts.size(); index++) {
            String text = texts.get(index);
            String normalised = BlockDigest.normalise(text);
            String digest = BlockDigest.of(text);

            Optional<ContentKind> byRule = rules.kindOf(normalised);
            if (byRule.isPresent()) {
                blocks.add(new ContentBlock(index, text, byRule.get(), "A configured rule matched.", Decider.RULE));
                labels.remember(
                    offer.portal(),
                    digest,
                    byRule.get(),
                    "A configured rule matched.",
                    Decider.RULE,
                    null,
                    BlockDigest.sample(text));
                fromCache++;
                continue;
            }

            Optional<BlockLabelStore.Label> cached = labels.find(offer.portal(), digest);
            if (cached.isPresent()) {
                blocks.add(new ContentBlock(
                    index, text, cached.get().kind(), cached.get().reason(), Decider.CACHE));
                labels.seenAgain(offer.portal(), digest);
                fromCache++;
                continue;
            }

            blocks.add(ContentBlock.undecided(index, text));
            unknown.add(new ContentClassifier.Candidate(index, BlockDigest.sample(text)));
        }

        if (unknown.isEmpty() || classifier.isEmpty()) {
            // Settled either because everything was already known, or because there is no
            // model to ask and rules-only is a legitimate terminal state rather than a
            // failure to keep retrying.
            return new Pass(blocks, fromCache, false, true);
        }

        Optional<Map<Integer, ContentClassifier.Labelled>> answered =
            classifier.get().classify(offer.title(), unknown);
        if (answered.isEmpty()) {
            // A model was configured and did not answer. Keep what the rules and the cache
            // decided, but leave the offer due so the next run finishes it.
            return new Pass(blocks, fromCache, true, false);
        }

        Map<Integer, ContentClassifier.Labelled> labelled = answered.get();
        for (ContentClassifier.Candidate candidate : unknown) {
            int index = candidate.index();
            ContentBlock block = blocks.get(index);
            ContentClassifier.Labelled answer = labelled.get(index);
            if (answer == null) {
                // Omitting a block is how the model says "this is the advert", so it is an
                // answer and not a gap — which is why it stops counting as undecided. It is
                // deliberately not remembered: an advert's own paragraphs are unique, so
                // caching them would add a row per offer that can never be hit again.
                blocks.set(index, new ContentBlock(index, block.text(), ContentKind.CONTENT, null, Decider.MODEL));
                continue;
            }
            blocks.set(index, new ContentBlock(index, block.text(), answer.kind(), answer.reason(), Decider.MODEL));
            labels.remember(
                offer.portal(),
                BlockDigest.of(block.text()),
                answer.kind(),
                answer.reason(),
                Decider.MODEL,
                classifier.get().model(),
                BlockDigest.sample(block.text()));
        }
        return new Pass(blocks, fromCache, true, true);
    }

    private void record(long id, Pass pass, String model) {
        // Only when something was actually taken out. An advert that is all advert reads the
        // same either way, and re-judging it would be a language-model call bought for a
        // score that cannot move.
        boolean changed = pass.blocks().stream().anyMatch(block -> !block.isContent());
        jdbc.sql(RECORD)
            .params(write(pass.blocks()), pass.settled(), model, pass.undecided(), changed, id)
            .update();
    }

    /**
     * The blocks as JSON. A failure here is the offer's, not the run's: the row keeps whatever
     * it had and the offer stays due, which is the same answer every other failure in this
     * stage gives.
     */
    private String write(List<ContentBlock> blocks) {
        try {
            return json.writeValueAsString(blocks);
        } catch (JsonProcessingException e) {
            log.warn("Could not write content blocks: {}", e.getMessage());
            return null;
        }
    }

    private record Due(long id, String portal, String title, String fullText) {
    }

    /**
     * @param asked   whether a model was actually called, so the report counts requests and
     *                not offers
     * @param settled whether this offer is finished with. False means a configured model did
     *                not answer, and the offer has to come back.
     */
    private record Pass(List<ContentBlock> blocks, int fromCache, boolean asked, boolean settled) {

        int undecided() {
            return (int) blocks.stream()
                .filter(block -> block.by() == Decider.DEFAULT)
                .count();
        }
    }
}
