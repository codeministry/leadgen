/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.answer;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.config.ConfigSnapshot;
import de.codeministry.leadgen.config.model.PipelineConfig;
import de.codeministry.leadgen.content.BlockDigest;
import de.codeministry.leadgen.content.ContentBlock;
import de.codeministry.leadgen.content.ContentClassifier;
import de.codeministry.leadgen.content.ContentKind;
import de.codeministry.leadgen.content.ContentText;
import de.codeministry.leadgen.content.Decider;
import de.codeministry.leadgen.enrich.AdExtractor;
import de.codeministry.leadgen.enrich.Enrichment;
import de.codeministry.leadgen.fields.ExtractedFields;
import de.codeministry.leadgen.fields.FieldExtractor;
import de.codeministry.leadgen.llm.Answers;
import de.codeministry.leadgen.llm.ChatModels;
import de.codeministry.leadgen.llm.LlmBudget;
import de.codeministry.leadgen.llm.ModelChoice;
import de.codeministry.leadgen.offer.BadShortlistRequest;
import de.codeministry.leadgen.score.ChatClientJudge;
import de.codeministry.leadgen.score.Judge;
import de.codeministry.leadgen.score.ScoreCandidate;
import de.codeministry.leadgen.score.ScoreReason;
import java.io.IOException;
import java.sql.Date;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * One candidate model, one bounded question, one advert — and the stored answer beside it.
 *
 * <p>The refusals come first and in the order a caller can act on them: a model nobody
 * configured, an offer nobody has, an advert with no text to read, an advert with no stored
 * answer to compare against, a judgement made under another ruleset. All five are cheap
 * reads, none of them spends a call, and each is a different sentence on the wire. The only row a call may change is the budget counter.
 *
 * <p><b>The question is the stage's, byte for byte.</b> The prompt comes from the stage's own
 * builder and the reply goes through the stage's own reader, bounds included — the classifier,
 * the field extractor and the judge each expose both for exactly this. A second copy of either
 * would make a measured disagreement a fact about the copy rather than about the model.
 *
 * <p>Nothing here calls a stage's run, a label cache or a score writer: a candidate's answer is
 * something to compare, never something to keep.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnswerService {

    /**
     * Its own mapper, not the web one, for the reason every stage that reads a model's answer
     * has one: this is plain JSON with none of the conventions the HTTP layer is configured for.
     */
    private final ObjectMapper json = new ObjectMapper();

    private final JdbcClient jdbc;
    private final ConfigRegistry config;
    private final ChatModels chatModels;
    private final LlmBudget budget;

    /**
     * What {@code model} answers to {@code question} about offer {@code offerId}, beside what is
     * stored for it.
     *
     * @throws BadShortlistRequest when no model is named, or one the configuration does not
     *                             name under any key — see {@link #configured}.
     * @throws NoSuchOffer         when there is no offer with that id.
     * @throws NothingToRead       when the advert has no text.
     * @throws NoStoredAnswer      when nothing is stored for this question on this advert.
     * @throws RulesetMoved        when the stored judgement was made under another ruleset.
     * @throws BudgetSpent         when the day's budget refuses the call.
     */
    public ModelAnswer answer(long offerId, AnswerQuestion question, String model) {
        // Required here, where Judges.check lets a blank name through to mean "the default":
        // a measurement that silently answered with the default would be recorded as the
        // candidate's agreement.
        ConfigSnapshot snapshot = config.snapshot();
        List<String> configured = configured(snapshot.application().llm());
        if (model == null || model.isBlank()) {
            throw new BadShortlistRequest("name a model; the configuration names %s".formatted(names(configured)));
        }
        String candidate = model.trim();
        if (!configured.contains(candidate)) {
            throw new BadShortlistRequest(
                    "model %s is not configured; the configuration names %s".formatted(candidate, names(configured)));
        }

        String advert = advert(offerId).orElseThrow(() -> new NoSuchOffer(offerId));
        if (advert.isBlank()) {
            throw new NothingToRead(offerId);
        }
        // Before the budget: an advert with nothing to compare against would buy an answer
        // that measures nothing.
        QuestionAnswer stored = stored(offerId, question).orElseThrow(() -> new NoStoredAnswer(offerId, question));
        if (question == AnswerQuestion.JUDGE) {
            // Before the budget too: the weight table bounds the judge's reply, so a stored
            // judgement under another table differs from any candidate by the table.
            requireCurrentRuleset(offerId, snapshot);
        }

        Optional<ChatModel> chatModel = chatModels.of(snapshot.application().llm(), candidate);
        if (chatModel.isEmpty()) {
            // The configuration cannot reach a model at all, and ChatModels has said why.
            // Nothing was asked, so nothing is spent and nothing is answered.
            return new ModelAnswer(question.key(), candidate, null, stored, null, 0L);
        }
        Asked asked =
                switch (question) {
                    case BLOCKS -> blocks(offerId, chatModel.get(), candidate);
                    case FIELDS -> fields(offerId, chatModel.get(), candidate, snapshot);
                    case JUDGE -> judge(offerId, chatModel.get(), candidate, snapshot);
                };

        if (!budget.take()) {
            throw new BudgetSpent();
        }
        long started = System.nanoTime();
        String raw;
        try {
            raw = Answers.textOf(ChatClient.create(chatModel.get())
                    .prompt()
                    .system(asked.system())
                    .user(asked.user())
                    .call()
                    .chatResponse());
        } catch (RuntimeException e) {
            // Unreachable, refused or timed out: there is no reply to read, which is "nothing
            // answered" and not a reply that disagreed. The time it took is still measured.
            log.warn(
                    "Candidate {} did not answer '{}' for offer {}: {}",
                    candidate,
                    question.key(),
                    offerId,
                    e.getMessage());
            return new ModelAnswer(question.key(), candidate, null, stored, null, since(started));
        }
        long millis = since(started);
        return new ModelAnswer(question.key(), candidate, asked.reader().apply(raw), stored, raw, millis);
    }

    /**
     * Every model the configuration names for any question, resolved the way each stage
     * resolves its own: the judge list ({@code scoring} plus {@code scoring_options}), then
     * {@code content}, {@code fields} and {@code extraction}, each falling back to the default
     * scoring choice when blank. Not {@code Judges.check}: once routing is adopted the incumbent
     * of {@code blocks} or {@code fields} is named under its own key only, and putting it on the
     * judge list just to measure it would offer it to the rescore's model select.
     */
    static List<String> configured(PipelineConfig.Llm llm) {
        PipelineConfig.Llm.Models models = llm == null ? null : llm.models();
        return Stream.of(
                        ModelChoice.scoring(llm).stream(),
                        ModelChoice.content(models).stream(),
                        ModelChoice.fields(models).stream(),
                        ModelChoice.extraction(models).stream())
                .flatMap(names -> names)
                .collect(Collectors.toCollection(LinkedHashSet::new))
                .stream()
                .toList();
    }

    private static String names(List<String> configured) {
        return configured.isEmpty() ? "none" : String.join(", ", configured);
    }

    /**
     * Refuses a stored judgement written under a {@code version:} other than the rules in
     * force, compared the way {@code ScoringService} finds a stale score: as text, a missing
     * one differing from any.
     */
    private void requireCurrentRuleset(long offerId, ConfigSnapshot snapshot) {
        String current = snapshot.rules() == null
                ? null
                : String.valueOf(snapshot.rules().version());
        String judged = jdbc.sql("SELECT ruleset_version FROM offer WHERE id = :id")
                .param("id", offerId)
                .query((rs, row) -> Optional.ofNullable(rs.getString("ruleset_version")))
                .single()
                .orElse(null);
        if (!Objects.equals(judged, current)) {
            throw new RulesetMoved(offerId, judged, current);
        }
    }

    /**
     * The classifier's question over exactly the blocks a model decided — the ones the stage put
     * to a model, since a rule's or the cache's block never was — each offered the way the stage
     * offers it: its index and its sample.
     */
    private Asked blocks(long offerId, ChatModel chatModel, String candidate) {
        List<ContentClassifier.Candidate> offered = modelBlocks(offerId).stream()
                .map(block -> new ContentClassifier.Candidate(block.index(), BlockDigest.sample(block.text())))
                .toList();
        ContentClassifier classifier = new ContentClassifier(chatModel, candidate, json);
        return new Asked(
                ContentClassifier.instructions(),
                ContentClassifier.describe(title(offerId), offered),
                raw -> labelled(offered, readLabels(classifier, raw, offered)));
    }

    private static Map<Integer, ContentClassifier.Labelled> readLabels(
            ContentClassifier classifier, String raw, List<ContentClassifier.Candidate> offered) {
        try {
            return classifier.read(raw, offered);
        } catch (IOException e) {
            return Map.of();
        }
    }

    /** One entry per block offered; an omitted index is the classifier's "this is the advert". */
    private static BlocksAnswer labelled(
            List<ContentClassifier.Candidate> offered, Map<Integer, ContentClassifier.Labelled> answered) {
        return new BlocksAnswer(offered.stream()
                .map(block -> {
                    ContentClassifier.Labelled label = answered.get(block.index());
                    return new LabelledBlock(block.index(), label == null ? ContentKind.CONTENT : label.kind());
                })
                .toList());
    }

    /**
     * The extractor's question over the advert as {@code FieldsService} feeds it: the same
     * columns and the content stage's text — but the "already read by a pattern" half as the
     * patterns read it, never as the row states it now.
     *
     * <p>That half is what {@code starts_on} and {@code duration} held when the stage asked,
     * and the stage then overwrote both with its own answer. Passing the row as it is now would
     * hand the candidate the incumbent's answer inside the question, and its agreement would
     * measure copying. So the enrichment patterns are run again, deterministically, over what
     * is stored.
     */
    private Asked fields(long offerId, ChatModel chatModel, String candidate, ConfigSnapshot snapshot) {
        PatternValues known = patternValues(offerId, snapshot);
        FieldExtractor.Candidate offer = jdbc.sql("""
                        SELECT id, title, description, full_text, content_blocks
                        FROM offer WHERE id = :id
                        """)
                .param("id", offerId)
                .query((rs, row) -> new FieldExtractor.Candidate(
                        rs.getLong("id"),
                        rs.getString("title"),
                        rs.getString("description"),
                        ContentText.of(rs.getString("content_blocks"), rs.getString("full_text")),
                        known.startsOn(),
                        known.duration()))
                .single();
        FieldExtractor extractor = new FieldExtractor(chatModel, candidate, json);
        return new Asked(FieldExtractor.instructions(), FieldExtractor.describe(offer), raw -> {
            try {
                ExtractedFields read = extractor.read(raw);
                return new FieldsAnswer(
                        new DatedField(read.startText(), day(read.startsOn())),
                        new DurationField(read.durationText(), read.durationMonths()),
                        new DatedField(read.applyByText(), day(read.applyBy())));
            } catch (IOException e) {
                return new FieldsAnswer(
                        new DatedField(null, null), new DurationField(null, null), new DatedField(null, null));
            }
        });
    }

    /**
     * The judge's question as a run would put it — this snapshot's weight table and profile,
     * which is what {@code Judges} builds a judge from — over the offer as scoring reads it, and
     * the reply bounded by the same table. Only the judged factors are kept; a topic is not one.
     */
    private Asked judge(long offerId, ChatModel chatModel, String candidate, ConfigSnapshot snapshot) {
        ScoreCandidate offer = jdbc.sql("SELECT " + ScoreCandidate.COLUMNS + " FROM offer WHERE id = :id")
                .param("id", offerId)
                .query(ScoreCandidate::of)
                .single();
        var rules = snapshot.rules();
        ChatClientJudge judge = new ChatClientJudge(
                chatModel,
                candidate,
                json,
                ChatClientJudge.boundsOf(rules == null ? null : rules.scoring()),
                snapshot.profile());
        return new Asked(judge.instructions(), ChatClientJudge.describe(offer), raw -> {
            Map<String, Integer> points = new HashMap<>();
            for (ScoreReason reason : judge.reasonsOf(raw, offerId)) {
                if (reason.topic() == null && Judge.JUDGED.contains(reason.factor())) {
                    points.merge(reason.factor(), reason.points(), Integer::sum);
                }
            }
            return judgeAnswer(points);
        });
    }

    /**
     * What the enrichment patterns read out of this advert, recomputed with the configured
     * {@code enrichment.extract} rules through the same {@link AdExtractor} the enrichment
     * stage builds. {@code starts_on} maps from {@code start_date} and {@code duration} from
     * {@code duration}, as the stage writes them.
     *
     * <p>The patterns ran over the fetched page, so the stored page is read first; when it is
     * gone, over {@code full_text}, which is the page's advert without its furniture — close, and
     * a field the page stated only outside the advert is then missed. Without a
     * {@code patterns} strategy nothing was read by a pattern, and both values are null.
     */
    private PatternValues patternValues(long offerId, ConfigSnapshot snapshot) {
        PipelineConfig.Enrichment enrichment = snapshot.application().enrichment();
        if (enrichment == null
                || enrichment.extract() == null
                || !"patterns".equals(enrichment.extract().strategy())) {
            return new PatternValues(null, null);
        }
        AdExtractor extractor = new AdExtractor(enrichment.extract());
        return jdbc.sql("""
                        SELECT o.url, o.full_text, p.body
                        FROM offer o LEFT JOIN fetched_page p ON p.url = o.url
                        WHERE o.id = :id
                        """)
                .param("id", offerId)
                .query((rs, row) -> {
                    String page = rs.getString("body") != null ? rs.getString("body") : rs.getString("full_text");
                    if (page == null || page.isBlank()) {
                        return new PatternValues(null, null);
                    }
                    Enrichment read = extractor.extract(page, rs.getString("url"));
                    return new PatternValues(read.startsOn(), read.duration());
                })
                .single();
    }

    private String title(long offerId) {
        return jdbc.sql("SELECT title FROM offer WHERE id = :id")
                .param("id", offerId)
                .query((rs, row) -> Optional.ofNullable(rs.getString("title")))
                .single()
                .orElse(null);
    }

    private static long since(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    /**
     * Empty when the offer does not exist; blank when it exists with nothing fetched. Read with a
     * {@code RowMapper} and {@code getString}, never {@code listOfRows()}: {@code content_blocks}
     * is jsonb and arrives as a {@code PGobject}.
     */
    private Optional<String> advert(long offerId) {
        return jdbc.sql("SELECT content_blocks, full_text FROM offer WHERE id = :id")
                .param("id", offerId)
                .query((rs, row) ->
                        Optional.ofNullable(ContentText.of(rs.getString("content_blocks"), rs.getString("full_text"))))
                .optional()
                .map(text -> text.orElse(""));
    }

    /** The stored answer to one question, or empty when the stage never answered it. */
    Optional<QuestionAnswer> stored(long offerId, AnswerQuestion question) {
        return switch (question) {
            case BLOCKS -> storedBlocks(offerId);
            case FIELDS -> storedFields(offerId);
            case JUDGE -> storedJudge(offerId);
        };
    }

    /**
     * The blocks a model decided, from the stored column and never from a live walk of the rules
     * and the label cache: those have moved on since, and the comparison is with what was
     * written.
     */
    private Optional<QuestionAnswer> storedBlocks(long offerId) {
        List<LabelledBlock> blocks = modelBlocks(offerId).stream()
                .map(block ->
                        new LabelledBlock(block.index(), block.kind() == null ? ContentKind.CONTENT : block.kind()))
                .toList();
        return blocks.isEmpty() ? Optional.empty() : Optional.of(new BlocksAnswer(blocks));
    }

    /**
     * The stored blocks whose {@code by} is {@code MODEL}, ascending by index. The stored answer
     * and the candidate's question are both built from this one list, so they cannot disagree
     * about which blocks are being compared.
     */
    private List<ContentBlock> modelBlocks(long offerId) {
        String column = jdbc.sql("SELECT content_blocks FROM offer WHERE id = :id")
                .param("id", offerId)
                .query((rs, row) -> Optional.ofNullable(rs.getString("content_blocks")))
                .optional()
                .flatMap(value -> value)
                .orElse(null);
        return ContentText.parse(column).stream()
                .filter(block -> block.by() == Decider.MODEL)
                .sorted(Comparator.comparingInt(ContentBlock::index))
                .toList();
    }

    /** The three field columns, when the field extractor answered at all ({@code fields_model}). */
    private Optional<QuestionAnswer> storedFields(long offerId) {
        return jdbc.sql("""
                        SELECT start_text, starts_on, duration, duration_months, apply_by_text, apply_by
                        FROM offer WHERE id = :id AND fields_model IS NOT NULL
                        """)
                .param("id", offerId)
                .query((rs, row) -> (QuestionAnswer) new FieldsAnswer(
                        new DatedField(rs.getString("start_text"), day(rs.getDate("starts_on"))),
                        new DurationField(rs.getString("duration"), rs.getObject("duration_months", Integer.class)),
                        new DatedField(rs.getString("apply_by_text"), day(rs.getDate("apply_by")))))
                .optional();
    }

    /**
     * The judged rows as written, read directly rather than through {@code ScoringService}. Only
     * {@link Judge#JUDGED} factors, never a topic row; a factor without a row reads 0. Empty when
     * no judge scored the offer or it left no judged row.
     */
    private Optional<QuestionAnswer> storedJudge(long offerId) {
        Map<String, Integer> points = new HashMap<>();
        jdbc.sql("""
                        SELECT r.factor, r.points
                        FROM offer o JOIN offer_score_reason r ON r.offer_id = o.id
                        WHERE o.id = :id AND o.score_model IS NOT NULL
                          AND r.topic IS NULL AND r.factor IN (:judged)
                        """).param("id", offerId).param("judged", Judge.JUDGED).query(rs -> {
            points.merge(rs.getString("factor"), rs.getInt("points"), Integer::sum);
        });
        if (points.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(judgeAnswer(points));
    }

    /** Every judged factor in {@link Judge#JUDGED} order, an absent one reading 0. */
    private static JudgeAnswer judgeAnswer(Map<String, Integer> points) {
        return new JudgeAnswer(Judge.JUDGED.stream()
                .map(factor -> new JudgedPoints(factor, points.getOrDefault(factor, 0)))
                .toList());
    }

    private static String day(Date date) {
        return date == null ? null : date.toLocalDate().toString();
    }

    private static String day(LocalDate date) {
        return date == null ? null : date.toString();
    }

    /**
     * One question as it is put: the stage's system prompt, the stage's message for this advert,
     * and the stage's reader turning a reply into the stored answer's shape. A reply the reader
     * cannot parse becomes that shape's empty answer, which scores as a disagreement.
     */
    private record Asked(String system, String user, Function<String, QuestionAnswer> reader) {}

    /** The start and the duration as the enrichment patterns read them, either possibly null. */
    private record PatternValues(LocalDate startsOn, String duration) {}

    /** An id nobody has. A 404 on the wire. */
    public static class NoSuchOffer extends RuntimeException {
        NoSuchOffer(long offerId) {
            super("no offer with id " + offerId);
        }
    }

    /** The advert has no text to ask about. A 409 on the wire. */
    public static class NothingToRead extends RuntimeException {
        NothingToRead(long offerId) {
            super("offer " + offerId + " has no fetched text to ask a model about");
        }
    }

    /** The stage never answered this question for this advert, so there is nothing to compare. A 409. */
    public static class NoStoredAnswer extends RuntimeException {
        NoStoredAnswer(long offerId, AnswerQuestion question) {
            this("offer " + offerId + " has no stored answer to " + question.key());
        }

        NoStoredAnswer(String message) {
            super(message);
        }
    }

    /**
     * The stored judgement was made under another ruleset, so nothing comparable is stored. A
     * 409, as a kind of {@link NoStoredAnswer}: the handler that answers that one answers this
     * one, and the sentence says which.
     */
    public static class RulesetMoved extends NoStoredAnswer {
        RulesetMoved(long offerId, String judged, String current) {
            super("offer %d was judged under ruleset %s; the current ruleset is %s"
                    .formatted(offerId, judged == null ? "none" : judged, current == null ? "none" : current));
        }
    }

    /** The day's {@code llm.budget} refused the call; nothing was asked. A 429. */
    public static class BudgetSpent extends RuntimeException {
        public BudgetSpent() {
            super("today's llm.budget is spent");
        }
    }
}
