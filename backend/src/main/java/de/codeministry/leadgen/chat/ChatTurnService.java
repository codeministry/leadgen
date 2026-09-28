/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.codeministry.leadgen.chat.tools.ApplicationTool;
import de.codeministry.leadgen.chat.tools.OfferSearchTool;
import de.codeministry.leadgen.chat.tools.PinnedContext;
import de.codeministry.leadgen.chat.tools.PinnedOfferLookup;
import de.codeministry.leadgen.chat.tools.PinnedOfferResult;
import de.codeministry.leadgen.chat.tools.ProfileTool;
import de.codeministry.leadgen.chat.tools.SemanticSearchTool;
import de.codeministry.leadgen.chat.tools.StatisticsTool;
import de.codeministry.leadgen.config.ConfigProperties;
import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.llm.ChatModels;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.execution.DefaultToolCallResultConverter;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * One chat turn, from the question to the last event: the model streams, asks for tools, gets
 * their masked results, streams again, and every piece of text passes the citation filter on its
 * way to the reader and into the stored answer.
 *
 * <p>The tool loop is driven here rather than left to Spring AI, because each step of it carries
 * a rule of this tool: every model request asks the chat's own call budget first, every tool round
 * asks the round bound, every tool result passes the masker before the model sees it, and every id
 * a tool returned goes into the turn's ledger so a citation can be checked against it. The model is
 * asked with tool execution left to the caller, and a response that asks for tools is answered by
 * this loop with one more request.
 *
 * <p>A turn runs on a small pool of its own, never on a servlet thread: a streamed answer takes as
 * long as the model takes, and the request thread is handed back as soon as the stream is open.
 * The pool is bounded because one operator asks one question at a time; a queue past that is a
 * browser gone wrong, and it is refused rather than left to pile up model calls.
 *
 * <p><b>Every turn ends with a terminal event and a terminal row.</b> Whatever throws inside a
 * turn — the database, the configuration, a bug — is logged, the turn is stored as
 * {@link ChatTurnState#INCOMPLETE} with the text it had, and the reader gets {@code error}; a turn
 * left at {@code STREAMING} would show a spinner for ever on every reopening. What a process that
 * died cannot do itself, {@link #sweep} does at the next start.
 *
 * <p><b>A database failure is not the model stopping.</b> Only the model's own stream sits inside
 * the model-failure catch; a {@link DataAccessException} from the row's writes or the citation
 * filter's reachability query is logged as what it is, the turn is ended without writing the text
 * again, and the reader's {@code error} says the answer could not be stored.
 *
 * <p><b>A turn has a deadline of its own.</b> {@code leadgen.chat.turn-timeout} (15 minutes unless set)
 * is enforced here, not only by the stream's container: a turn past it is stopped like a stop
 * request — the model call cancelled, the row {@link ChatTurnState#INCOMPLETE}, an {@code error}
 * that says so — so the loop never outlives the stream it was writing to and never keeps a pool
 * thread. The deadline counts from when a pool thread picks the turn up, not from the request, so
 * the controller's stream has no container timeout of its own: a queued turn's wait would have
 * come out of it, and the turn completes the stream itself once its last event is out.
 *
 * <p><b>Two rounds that both speak are two paragraphs.</b> The citation filter is flushed at the end
 * of every model round, so a held tail never joins the next round's words, and a round that speaks
 * after an earlier one did starts with a blank line — in the stream and in the stored answer alike.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatTurnService {

    /** Turns that may run at once, and turns that may wait for one of them. */
    static final int RUNNING = 4;

    static final int WAITING = 8;

    /**
     * How long streamed text may wait before it is written to the turn's row.
     *
     * <p>Writing each chunk as it went out was one {@code UPDATE} per token — hundreds per answer,
     * each one rewriting a growing {@code TEXT} value. A quarter of a second is below what a reader
     * notices and caps the writes at four a second; the text is also written at the end of every
     * model round and whenever the turn ends, so what is stored always equals what was streamed.
     * The one loss is a process that dies mid-turn, which gives up at most this much of the tail.
     */
    static final Duration FLUSH_INTERVAL = Duration.ofMillis(250);

    private static final String SYSTEM_PROMPT = "leadgen/chat-system.st";

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Writes the pinned lookup's result the way Spring AI writes every other tool's. */
    private static final DefaultToolCallResultConverter RESULTS = new DefaultToolCallResultConverter();

    private final ThreadPoolExecutor turns = new ThreadPoolExecutor(
            RUNNING,
            RUNNING,
            60,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(WAITING),
            Thread.ofPlatform().name("chat-turn-", 0).daemon().factory());

    private final ChatCapability capability;
    private final ConfigRegistry config;
    private final ChatModels chatModels;
    private final ChatBudget budget;
    private final ToolOutputMasker masker;
    private final ConversationRepository conversations;
    private final OfferSearchTool offerSearch;
    private final SemanticSearchTool semanticSearch;
    private final StatisticsTool statistics;
    private final ApplicationTool application;
    private final ProfileTool profile;
    private final PinnedOfferLookup pinnedOffer;

    /**
     * {@code leadgen.chat.turn-timeout}: how long one turn may take, from its first model call to
     * its last event. Bound on {@link ConfigProperties} so a test can shorten it; the controller
     * reads the same binding for its stream.
     */
    private final ConfigProperties properties;

    /**
     * The five tools and the system prompt, built once. Neither depends on the turn: rebuilding the
     * callbacks by reflection and re-reading the prompt from the classpath per turn was work done for
     * nothing, and a prompt missing from the jar now stops the start instead of every turn.
     */
    private Map<String, ToolCallback> tools;

    private List<ToolCallback> toolList;

    private String systemPrompt;

    /** The turns running now, by id, so a stop request can reach the one it names. */
    private final Map<Long, Turn> running = new ConcurrentHashMap<>();

    /**
     * When this process came up, on the database's clock: the {@link #sweep} ends only turns
     * created before it. Taken while the context is being built, which is before the web server
     * takes its first request, so no turn of this process can be older.
     */
    private OffsetDateTime processStart;

    @PostConstruct
    void prepare() {
        processStart = conversations.now();
        Map<String, ToolCallback> byName = new LinkedHashMap<>();
        for (ToolCallback tool : ToolCallbacks.from(offerSearch, semanticSearch, statistics, application, profile)) {
            byName.put(tool.getToolDefinition().name(), tool);
        }
        tools = Map.copyOf(byName);
        toolList = List.copyOf(byName.values());
        systemPrompt = loadSystemPrompt();
    }

    /** How long a turn may run before it is stopped; see the class comment. */
    public Duration timeout() {
        return properties.chat().turnTimeout();
    }

    /**
     * Starts a turn on the pool and returns at once; the events go to {@code sink} from the pool's
     * thread, ending with {@code done} or {@code error}.
     *
     * @throws java.util.concurrent.RejectedExecutionException when the pool and its queue are full
     */
    public void start(long conversationId, String question, Consumer<ChatEvent> sink, Runnable finished) {
        start(conversationId, question, null, sink, finished);
    }

    /**
     * Asks the question of {@code turnId} again, as a new turn that records the one it replaces;
     * both stay in the conversation. The question is looked up before anything starts, so a turn
     * that is not in this conversation is refused on the request thread.
     *
     * @return false when the conversation holds no turn {@code turnId}; nothing was started
     * @throws java.util.concurrent.RejectedExecutionException when the pool and its queue are full
     */
    public boolean regenerate(long conversationId, long turnId, Consumer<ChatEvent> sink, Runnable finished) {
        Optional<String> question = conversations.question(conversationId, turnId);
        question.ifPresent(asked -> start(conversationId, asked, turnId, sink, finished));
        return question.isPresent();
    }

    /**
     * Stops a running turn: the model call is cancelled, which closes its connection, and the turn
     * ends as {@link ChatTurnState#STOPPED} with the text it had, on its own thread.
     *
     * @return false when no turn {@code turnId} of this conversation is running
     */
    public boolean stop(long conversationId, long turnId) {
        Turn turn = running.get(turnId);
        if (turn == null || turn.conversationId != conversationId) {
            return false;
        }
        turn.stop();
        return true;
    }

    /**
     * Stops every running turn of a conversation and waits, at most {@code wait}, for each to end:
     * its model call cancelled — which closes that connection — and its terminal state written. A
     * delete asks this first, so the rows it removes are no longer being written under it.
     *
     * @return false when a turn was still running once the wait was over
     */
    public boolean stopAll(long conversationId, Duration wait) {
        List<CompletableFuture<Void>> ending = new ArrayList<>();
        for (Turn turn : running.values()) {
            if (turn.conversationId == conversationId) {
                turn.stop();
                ending.add(turn.over);
            }
        }
        if (ending.isEmpty()) {
            return true;
        }
        try {
            CompletableFuture.allOf(ending.toArray(CompletableFuture[]::new))
                    .get(wait.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException e) {
            return false;
        } catch (ExecutionException e) {
            // Never completed exceptionally: `over` is completed with null in a finally.
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Ends the turns a previous process left at {@code STREAMING}: they were running when it died,
     * and nothing else will ever finish them. Runs once the application is ready — but the web
     * server takes requests before that event, so a turn this process already started can be
     * streaming by then. Only rows created before {@link #processStart} are touched, so every row
     * it ends is somebody else's; with one instance at a time (the deployment recreates rather than
     * rolls) that somebody is gone.
     */
    @EventListener(ApplicationReadyEvent.class)
    void sweep() {
        int left = conversations.abandonStreaming(processStart);
        if (left > 0) {
            log.info("Marked {} chat turn(s) a previous process left streaming as incomplete", left);
        }
    }

    private void start(
            long conversationId, String question, Long replaces, Consumer<ChatEvent> sink, Runnable finished) {
        turns.execute(() -> {
            try {
                ask(conversationId, question, replaces, sink);
            } finally {
                finished.run();
            }
        });
    }

    /** Runs one turn on the calling thread. {@link #start} is the way in from a request. */
    public void ask(long conversationId, String question, Consumer<ChatEvent> sink) {
        ask(conversationId, question, null, sink);
    }

    private void ask(long conversationId, String question, Long replaces, Consumer<ChatEvent> sink) {
        Turn turn = null;
        try {
            String modelName = capability.model().orElse(null);
            long turnId = conversations.startTurn(conversationId, question, modelName, replaces);
            turn = new Turn(turnId, conversationId, question, modelName, sink);
            running.put(turnId, turn);
            sink.accept(new ChatTurnStarted(turnId));
            turn.run();
        } catch (RuntimeException e) {
            if (turn == null) {
                log.error("A chat turn of conversation {} could not be started", conversationId, e);
                sink.accept(new ChatError(ChatErrorReason.MODEL, "the turn could not be started"));
            } else if (e instanceof DataAccessException && turn.vanished()) {
                // The reader deleted the conversation mid-turn: the cascade took the row, and the
                // terminal write failed on it. That is no database failure and nothing to retry.
                log.info("Chat turn {} ended unstored: its conversation was deleted while it ran", turn.id);
                turn.deleted();
            } else if (e instanceof DataAccessException) {
                log.error("Chat turn {} could not be stored: the database failed while it ran", turn.id, e);
                turn.unstored();
            } else {
                log.error("Chat turn {} broke off on the server", turn.id, e);
                turn.broke();
            }
        } finally {
            if (turn != null) {
                running.remove(turn.id);
                turn.over.complete(null);
            }
        }
    }

    @PreDestroy
    void shutdown() {
        turns.shutdownNow();
    }

    /** The state of one running turn; lives exactly as long as the turn. */
    private final class Turn {

        private final long id;
        private final long conversationId;
        private final String question;
        private final String modelName;
        private final Consumer<ChatEvent> sink;
        private final TurnLedger ledger = new TurnLedger();
        private final CitationFilter filter;

        /** Completed once the turn has ended and left {@link #running}, whichever way it ended. */
        private final CompletableFuture<Void> over = new CompletableFuture<>();

        /** When this turn is out of time; see {@code timeout}. */
        private final long deadline = System.nanoTime() + timeout().toNanos();

        /** The conversation's pins, read when the turn runs and handed to every tool call (ISC-452). */
        private PinnedContext pins = PinnedContext.NONE;

        /** Text the reader has and the row does not yet; see {@link #FLUSH_INTERVAL}. */
        private final StringBuilder unsaved = new StringBuilder();

        private long savedAt = System.nanoTime();

        /** Whether an earlier model round of this turn said anything, so the next one starts a paragraph. */
        private boolean answered;

        /** Whether the row holds its terminal state; after that only events are left to send. */
        private boolean ended;

        /**
         * Set by a stop request from another thread. The flag is what the turn's own thread reads
         * between chunks; the signal is what cancels a model call that is waiting for its next one.
         */
        private volatile boolean stopped;

        /** Set when the deadline passed while the model was streaming; read like {@link #stopped}. */
        private volatile boolean expired;

        private final Sinks.Empty<Void> stopSignal = Sinks.empty();

        Turn(long id, long conversationId, String question, String modelName, Consumer<ChatEvent> sink) {
            this.id = id;
            this.conversationId = conversationId;
            this.question = question;
            this.modelName = modelName;
            this.sink = sink;
            this.filter = new CitationFilter(ledger, this::reachable);
        }

        /**
         * The repository's reachability. The pinned offers the lookup returned are the filter's own
         * exception ({@link CitationFilter#pinned}), handed over once the lookup has run.
         */
        private boolean reachable(ChatSourceKind kind, Long rowId) {
            return conversations.reachable(kind, rowId);
        }

        /** What is left of this turn's time; zero or less once it is out. */
        private Duration remaining() {
            return Duration.ofNanos(deadline - System.nanoTime());
        }

        void run() {
            ChatModel model = modelName == null
                    ? null
                    : chatModels
                            .of(config.snapshot().application().llm(), modelName)
                            .orElse(null);
            if (model == null) {
                end(ChatErrorReason.MODEL, "no chat model can be reached with the configured llm block");
                return;
            }
            ChatOptions options = options(model);
            List<Message> messages = messages();
            pins = pins(
                    conversations.context(conversationId),
                    conversations.pinnedOffer(conversationId).orElse(null));
            if (!pins.offers().isEmpty()) {
                pinned(pins.offers(), messages);
            }
            ChatBudget.Rounds rounds = budget.rounds();
            while (true) {
                if (stopped) {
                    complete(ChatTurnState.STOPPED);
                    return;
                }
                Duration remaining = remaining();
                if (expired || remaining.isNegative() || remaining.isZero()) {
                    outOfTime();
                    return;
                }
                if (!budget.take()) {
                    end(ChatErrorReason.BUDGET, "chat.max_calls_per_day is spent for today");
                    return;
                }
                StringBuilder said = new StringBuilder();
                List<AssistantMessage.ToolCall> calls = new ArrayList<>();
                boolean spoke = false;
                // Only the model's own stream sits inside the model-failure catch. What this turn
                // does with a chunk — the citation filter's reachability query, the flush to the
                // row — is the database, and a failure there is reported as what it is (see ask).
                Stream<ChatResponse> stream;
                try {
                    stream = model.stream(new Prompt(messages, options))
                            .takeUntilOther(stopSignal.asMono())
                            // The deadline cancels the call exactly as a stop does.
                            .takeUntilOther(Mono.delay(remaining).doOnNext(tick -> expired = true))
                            .toStream();
                } catch (RuntimeException e) {
                    modelStopped(e);
                    return;
                }
                // Closing the stream cancels the model call, whichever way this block is left.
                try (stream) {
                    Iterator<ChatResponse> responses = stream.iterator();
                    while (!stopped && !expired) {
                        ChatResponse response;
                        try {
                            if (!responses.hasNext()) {
                                break;
                            }
                            response = responses.next();
                        } catch (RuntimeException e) {
                            modelStopped(e);
                            return;
                        }
                        for (Generation generation : response.getResults()) {
                            AssistantMessage output = generation.getOutput();
                            // A NUL is refused by the TEXT column: one such token would fail the
                            // flush and end the turn unstored. Dropped here, before the text is
                            // streamed or buffered, so the stream and the stored answer stay equal.
                            String text = ConversationRepository.withoutNul(output.getText());
                            if (text != null && !text.isEmpty()) {
                                said.append(text);
                                // Two rounds that both speak are two paragraphs. The break goes
                                // through the filter, so its defusing stage sees the whitespace.
                                text(filter.accept(!spoke && answered ? "\n\n" + text : text));
                                spoke = true;
                            }
                            calls.addAll(output.getToolCalls());
                        }
                    }
                }
                // A round is a whole message: a tail the filter still holds was never a marker, and
                // held across a tool round it would join the next round's first words into one.
                text(filter.finish());
                answered |= spoke;
                save();
                if (stopped) {
                    complete(ChatTurnState.STOPPED);
                    return;
                }
                if (expired) {
                    outOfTime();
                    return;
                }
                if (calls.isEmpty()) {
                    break;
                }
                if (!rounds.next()) {
                    end(ChatErrorReason.ROUNDS, "chat.max_tool_rounds is reached for this turn");
                    return;
                }
                messages.add(AssistantMessage.builder()
                        .content(said.toString())
                        .toolCalls(calls)
                        .build());
                List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
                for (AssistantMessage.ToolCall call : calls) {
                    responses.add(new ToolResponseMessage.ToolResponse(
                            call.id(), call.name(), call(call.name(), call.arguments(), () -> invoke(call))));
                }
                messages.add(ToolResponseMessage.builder().responses(responses).build());
            }
            complete(ChatTurnState.DONE);
        }

        void stop() {
            stopped = true;
            stopSignal.tryEmitEmpty();
        }

        /**
         * The conversation's pinned offers — every one of them (ISC-453) — looked up before the model
         * is first asked and recorded exactly like a tool call — a step, a {@code chat_tool_call}
         * row, their ids in the ledger, the result masked — then handed to the model as the answer to
         * a call it did not have to make. Without it "Ask about this offer" asks about nothing: the
         * pin was stored and never read.
         */
        private void pinned(List<Long> offerIds, List<Message> messages) {
            AssistantMessage.ToolCall lookup = new AssistantMessage.ToolCall(
                    "pinned-offer",
                    "function",
                    PinnedOfferLookup.NAME,
                    "{\"offerIds\":"
                            + offerIds.stream()
                                    .map(String::valueOf)
                                    .collect(java.util.stream.Collectors.joining(",", "[", "]"))
                            + "}");
            String result = call(
                    lookup.name(),
                    lookup.arguments(),
                    () -> RESULTS.convert(pinnedOffer.lookup(offerIds), PinnedOfferResult.class));
            // ISC-454: the exception covers each pin the lookup actually returned, and nothing else.
            filter.pinned(returned(result).stream()
                    .filter(ref -> ref.kind() == ChatSourceKind.OFFER && offerIds.contains(ref.id()))
                    .map(TurnLedger.Ref::id)
                    .toList());
            messages.add(AssistantMessage.builder()
                    .content("")
                    .toolCalls(List.of(lookup))
                    .build());
            messages.add(ToolResponseMessage.builder()
                    .responses(List.of(new ToolResponseMessage.ToolResponse(lookup.id(), lookup.name(), result)))
                    .build());
        }

        /** Ends the turn with an answer, or with the part of one a stop left: sources, then done. */
        private void complete(ChatTurnState state) {
            text(filter.finish());
            save();
            List<TurnLedger.Citation> citations = ledger.citations();
            // Resolved before the row is ended, so a failure here still leaves it to `broke`.
            List<ChatSourceItem> sources = sources(citations);
            conversations.finish(id, state, ledger.calls(), citations);
            ended = true;
            sink.accept(new ChatSources(sources));
            sink.accept(new ChatDone(state));
        }

        /** Runs one tool call: step events around it, the result masked, the ids into the ledger. */
        private String call(String name, String arguments, Supplier<String> invoke) {
            int ordinal = ledger.calls().size() + 1;
            String label = label(name, arguments);
            sink.accept(new ChatStep(ordinal, name, label, ChatStepState.RUNNING, null, null));
            long started = System.nanoTime();
            String result;
            StatisticsSource data = null;
            try {
                String raw = invoke.get();
                // Read from the tool's own JSON before the model sees a digit of it; the card an
                // answer draws never depends on what the model then writes (ISC-460).
                if (StatisticsTool.NAME.equals(name)) {
                    data = StatisticsSource.of(ordinal, raw);
                }
                result = masker.mask(raw);
            } catch (RuntimeException e) {
                log.info("Chat tool {} failed in turn {}: {}", name, id, e.toString());
                result = masker.mask(error(e.getMessage() == null ? e.toString() : e.getMessage()));
            }
            long duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            List<TurnLedger.Ref> returned = returned(result);
            ledger.record(name, label, arguments, returned, duration, data);
            sink.accept(new ChatStep(ordinal, name, label, ChatStepState.DONE, returned.size(), duration));
            return result;
        }

        private String invoke(AssistantMessage.ToolCall call) {
            ToolCallback tool = tools.get(call.name());
            // ISC-452: every call carries the conversation's pins; a pinned view or window wins
            // over the model's arguments inside the tool, where the model cannot argue with it.
            return tool == null
                    ? error("there is no tool called " + call.name())
                    : tool.call(call.arguments(), pins.toolContext());
        }

        private void text(String delta) {
            if (delta.isEmpty()) {
                return;
            }
            unsaved.append(delta);
            sink.accept(new ChatText(delta));
            if (System.nanoTime() - savedAt >= FLUSH_INTERVAL.toNanos()) {
                save();
            }
        }

        /** Writes the text the reader has and the row does not. */
        private void save() {
            if (!unsaved.isEmpty()) {
                conversations.append(id, unsaved.toString());
                unsaved.setLength(0);
            }
            savedAt = System.nanoTime();
        }

        /** The model's stream failed — its call, its connection, its answer — and nothing else did. */
        private void modelStopped(RuntimeException e) {
            log.warn("The chat model stopped answering turn {}: {}", id, e.toString());
            end(ChatErrorReason.MODEL, "the model stopped answering before it finished");
        }

        /** The deadline passed: the turn ends like a stopped one, but incomplete and with a reason. */
        private void outOfTime() {
            log.warn(
                    "Chat turn {} ran past its {} s and was stopped",
                    id,
                    timeout().toSeconds());
            end(
                    ChatErrorReason.MODEL,
                    "the turn ran out of time after " + timeout().toSeconds() + " s");
        }

        /**
         * Ends the row after something threw: the full terminal write first, and if the database
         * refuses that too — a tool-call row it will not take — the state alone in a statement of its
         * own. Retrying the same rejected write left the turn {@code STREAMING} until a restart.
         */
        private void endRow() {
            try {
                conversations.finish(
                        id, ChatTurnState.INCOMPLETE, ChatErrorReason.MODEL, ledger.calls(), ledger.citations());
                ended = true;
                return;
            } catch (RuntimeException e) {
                log.warn("Chat turn {} could not be stored whole, ending it by its state: {}", id, e.toString());
            }
            try {
                conversations.incomplete(id);
                ended = true;
            } catch (RuntimeException e) {
                log.error("Chat turn {} stays STREAMING until the next start sweeps it", id, e);
            }
        }

        /**
         * Ends the turn short of an answer: the partial text is kept, the turn is incomplete. When
         * the partial text cited anything, {@code sources} goes out before the {@code error}, from
         * the same ledger the row stores — the live pills need their rows as much as a reopened
         * turn's do, and without them they pointed at nothing until the conversation was reloaded.
         */
        private void end(ChatErrorReason reason, String message) {
            text(filter.finish());
            save();
            List<TurnLedger.Citation> citations = ledger.citations();
            // Resolved before the row is ended, so a failure here still leaves it to `unstored`.
            List<ChatSourceItem> sources = sources(citations);
            conversations.finish(id, ChatTurnState.INCOMPLETE, reason, ledger.calls(), citations);
            ended = true;
            if (!sources.isEmpty()) {
                sink.accept(new ChatSources(sources));
            }
            sink.accept(new ChatError(reason, message));
        }

        /** The cited rows in citation order, then the turn's statistics calls in call order. */
        private List<ChatSourceItem> sources(List<TurnLedger.Citation> citations) {
            List<ChatSourceItem> sources = new ArrayList<>(conversations.sources(citations));
            sources.addAll(ledger.statistics());
            return List.copyOf(sources);
        }

        /**
         * Whether this turn's row is gone — its conversation was deleted while the turn ran and the
         * cascade took the row. Asked only after a database failure; a check that fails itself
         * answers no, so a database that is really down is still reported as one.
         */
        boolean vanished() {
            try {
                return !conversations.turnExists(id);
            } catch (RuntimeException e) {
                return false;
            }
        }

        /**
         * Ends a turn whose conversation the reader deleted. Nothing is written — there is no row
         * left to write to, and a fallback against it would only fail again — and the stream still
         * gets its one terminal event, so a reader that stayed on it is not left waiting.
         */
        void deleted() {
            ended = true;
            sink.accept(new ChatError(ChatErrorReason.MODEL, "the conversation was deleted while this turn ran"));
        }

        /**
         * Ends a turn something threw in. Each step is tried on its own, because the thing that
         * threw may be the database the next step writes to; the row is ended by {@link #endRow},
         * and one even that cannot end is left to the next start's {@link #sweep}.
         */
        void broke() {
            if (!ended) {
                try {
                    text(filter.finish());
                    save();
                } catch (RuntimeException e) {
                    log.warn("The partial text of chat turn {} could not be kept: {}", id, e.toString());
                }
                endRow();
            }
            sink.accept(new ChatError(ChatErrorReason.MODEL, "the turn broke off on the server"));
        }

        /**
         * Ends a turn the database failed under. The text already streamed is not written again —
         * the write that failed is the one that would do it — and the row is ended by {@link #endRow};
         * a row even that cannot end is left to the next start's {@link #sweep}. The reason
         * stays {@code MODEL}, the seam's catch-all, and the message says what actually failed.
         */
        void unstored() {
            if (!ended) {
                endRow();
            }
            sink.accept(new ChatError(ChatErrorReason.MODEL, "the answer could not be stored: the database failed"));
        }

        /**
         * The system prompt, the conversation so far and the question. Earlier answers go back
         * with their links turned into markers again ({@link CitationFilter#asMarkers}): shown a
         * resolved link, a model copies it, and the filter would then have to catch the copy.
         */
        private List<Message> messages() {
            List<Message> messages = new ArrayList<>();
            messages.add(new SystemMessage(systemPrompt));
            for (PastTurn past : conversations.history(conversationId, id)) {
                messages.add(new UserMessage(past.question()));
                messages.add(new AssistantMessage(CitationFilter.asMarkers(past.answer())));
            }
            messages.add(new UserMessage(question));
            return messages;
        }

        /**
         * The model's own options with the five tools added. Mutated rather than built fresh so the
         * timeout the client was made with survives — an options object without one pins every
         * request at the library's 60 s (backend/CLAUDE.md).
         */
        private ChatOptions options(ChatModel model) {
            ChatOptions.Builder<?> builder = model.getDefaultOptions().mutate();
            if (!(builder instanceof ToolCallingChatOptions.Builder<?> tooling)) {
                throw new IllegalStateException("the chat model's options take no tools: "
                        + builder.getClass().getName());
            }
            return tooling.toolCallbacks(toolList).build();
        }
    }

    /** A conversation's context as the tools read it: the offers, the views, the first window. */
    static PinnedContext pins(List<ChatContextItem> context, Long legacyPin) {
        List<Long> offers = new ArrayList<>();
        List<String> views = new ArrayList<>();
        ChatContextItem window = null;
        for (ChatContextItem item : context) {
            switch (item.kind()) {
                case OFFER -> offers.add(item.offerId());
                case SHORTLIST_VIEW -> views.add(item.query());
                case ANALYTICS_WINDOW -> window = window == null ? item : window;
            }
        }
        // A conversation pinned before chat_context existed carries its pin only in
        // pinned_offer_id; it is still read, so an old "Ask about this offer" keeps its offer.
        if (offers.isEmpty() && legacyPin != null) {
            offers.add(legacyPin);
        }
        return new PinnedContext(
                offers, views, window == null ? null : window.from(), window == null ? null : window.to());
    }

    /** The ids a tool result names: its offers, an application and the offer it belongs to. */
    static List<TurnLedger.Ref> returned(String result) {
        JsonNode tree;
        try {
            tree = JSON.readTree(result);
        } catch (JsonProcessingException e) {
            return List.of();
        }
        List<TurnLedger.Ref> refs = new ArrayList<>();
        if (tree == null || !tree.isObject()) {
            return refs;
        }
        for (JsonNode offer : tree.path("offers")) {
            if (offer.path("id").canConvertToLong()) {
                refs.add(new TurnLedger.Ref(
                        ChatSourceKind.OFFER, offer.path("id").asLong()));
            }
        }
        if (tree.path("applicationId").canConvertToLong()) {
            refs.add(new TurnLedger.Ref(
                    ChatSourceKind.APPLICATION, tree.path("applicationId").asLong()));
        }
        if (tree.path("offerId").canConvertToLong()) {
            refs.add(new TurnLedger.Ref(
                    ChatSourceKind.OFFER, tree.path("offerId").asLong()));
        }
        return refs;
    }

    /** What the step line says while and after a tool runs: the tool in words, and its query if it has one. */
    static String label(String tool, String arguments) {
        String words =
                switch (tool) {
                    case "search_offers" -> "Searched offers";
                    case "search_by_meaning" -> "Searched by meaning";
                    case "statistics" -> "Read the numbers";
                    case "application" -> "Read the application";
                    case "profile" -> "Read the profile";
                    case PinnedOfferLookup.NAME -> "Read the pinned offer";
                    default -> tool;
                };
        try {
            JsonNode args = JSON.readTree(arguments == null ? "{}" : arguments);
            for (String key : List.of("text", "phrase", "query")) {
                if (args.path(key).isTextual() && !args.path(key).asText().isBlank()) {
                    return words + " · " + args.path(key).asText().strip();
                }
            }
        } catch (JsonProcessingException e) {
            // A label is a courtesy; arguments the model mangled leave it at the tool's name.
        }
        return words;
    }

    private static String error(String message) {
        try {
            return JSON.writeValueAsString(Map.of("error", message));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String loadSystemPrompt() {
        try {
            return new ClassPathResource(SYSTEM_PROMPT).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("the chat's system prompt is missing: " + SYSTEM_PROMPT, e);
        }
    }
}
