/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.web;

import de.codeministry.leadgen.chat.ChatCapability;
import de.codeministry.leadgen.chat.ChatCapabilityView;
import de.codeministry.leadgen.chat.ChatContextItem;
import de.codeministry.leadgen.chat.ChatContextKind;
import de.codeministry.leadgen.chat.ChatEvent;
import de.codeministry.leadgen.chat.ChatTurnService;
import de.codeministry.leadgen.chat.ConversationRepository;
import de.codeministry.leadgen.chat.ConversationSummary;
import de.codeministry.leadgen.chat.ConversationView;
import de.codeministry.leadgen.chat.NewContext;
import de.codeministry.leadgen.chat.NewConversation;
import de.codeministry.leadgen.chat.NewTurn;
import de.codeministry.leadgen.chat.RenameConversation;
import de.codeministry.leadgen.chat.suggest.FollowUp;
import de.codeministry.leadgen.chat.suggest.FollowUps;
import de.codeministry.leadgen.chat.suggest.Suggestion;
import de.codeministry.leadgen.chat.suggest.SuggestionScope;
import de.codeministry.leadgen.chat.suggest.SuggestionService;
import de.codeministry.leadgen.config.ConfigProperties;
import jakarta.annotation.PreDestroy;
import jakarta.validation.Valid;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The chat's HTTP surface: whether it exists, the conversations, and a turn as a stream of
 * {@link de.codeministry.leadgen.chat.ChatEvent}s.
 *
 * <p>A turn is a {@code POST} answered with {@code text/event-stream}, not an {@code EventSource}
 * GET: the question travels in the body, and a question in a query string ends up in every
 * access log between here and the browser.
 *
 * <p>Both streaming endpoints answer with {@code X-Accel-Buffering: no} and {@code Cache-Control:
 * no-cache}. nginx buffers a proxied response by default and would hold the whole answer until the
 * turn ends — a stream that arrives in one piece at the end is a spinner followed by a wall of
 * text — and a cache has no business keeping an event stream at all.
 *
 * <p><b>A silent model still sends bytes.</b> While a turn runs, every {@code leadgen.chat.heartbeat}
 * (15 s unless set) the stream carries an SSE comment, {@code :keep-alive}, which every reader
 * ignores and every proxy counts as traffic. A model that thinks for a minute over a long prompt
 * would otherwise be cut by a proxy's idle timeout (the chart's nginx keeps 60 s) before its first
 * word. The heartbeats share one small scheduler and stop when the turn ends.
 */
@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
class ChatController {

    /**
     * No container timeout on a turn's stream: the turn owns its end. Its deadline starts when a
     * pool thread picks it up, stops it with an {@code error}, and the turn completes the stream
     * once it is over, whatever way it ends. A container timeout counted from the request — the
     * deadline plus a grace — lost whatever the turn spent waiting in the queue out of that grace,
     * and a turn that waited long enough had its stream closed before its terminal event.
     */
    static final long NO_CONTAINER_TIMEOUT = -1L;

    /** How long a delete waits for the turns it stopped to end before it deletes under them. */
    static final Duration STOP_BEFORE_DELETE = Duration.ofSeconds(5);

    private final ConversationRepository conversations;
    private final ChatCapability capability;
    private final ChatTurnService turns;
    private final SuggestionService suggestions;
    private final FollowUps followUps;

    /**
     * {@code leadgen.chat.*}: the turn timeout {@link ChatTurnService} stops a turn at, read here
     * for the stream's own timeout, and how often a running turn's stream carries a comment.
     */
    private final ConfigProperties properties;

    /** One thread for every stream's heartbeat: a heartbeat is one small write, never a wait. */
    private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("chat-heartbeat").daemon().factory());

    @GetMapping("/capability")
    ChatCapabilityView capability() {
        return capability.view();
    }

    /**
     * Newest first. A blank {@code q} is no search, so a cleared search field asks for the whole
     * list; otherwise only the conversations holding every word of it, ignoring case and accents.
     */
    @GetMapping("/conversations")
    List<ConversationSummary> conversations(@RequestParam(required = false) String q) {
        return q == null || q.isBlank() ? conversations.list() : conversations.search(q);
    }

    /**
     * Renames a conversation: stored trimmed, a name over 120 characters is cut rather than refused,
     * and an empty one clears the name so the derived title shows again.
     */
    @PatchMapping("/conversations/{id}")
    ConversationView rename(@PathVariable long id, @RequestBody RenameConversation body) {
        if (!conversations.rename(id, body.title())) {
            throw notFound(id);
        }
        return conversations.find(id).orElseThrow(() -> notFound(id));
    }

    /**
     * Replaces the conversation's whole context list with the body's, in its order (ISC-451). An
     * empty list unpins everything; an item missing what its kind needs is a 400, an offer that does
     * not exist a 404 like the pin on create.
     */
    @PutMapping("/conversations/{id}/context")
    ConversationView context(@PathVariable long id, @RequestBody NewContext body) {
        boolean found;
        try {
            found = conversations.replaceContext(id, body.context());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "an offer of the context does not exist");
        }
        if (!found) {
            throw notFound(id);
        }
        return conversations.find(id).orElseThrow(() -> notFound(id));
    }

    /**
     * Up to four questions the data suggests, for a conversation or for a context not yet stored
     * (ISC-455, ISC-456), in the language {@code Accept-Language} ranks first of German and English.
     * A stored conversation is read for its pins; {@code ?context=} is the URL's {@code chatCtx}
     * form, of which the offer pins ({@code o:<id>}) are read and every other entry is skipped, as
     * the frontend does.
     */
    @GetMapping("/suggestions")
    List<Suggestion> suggestions(
            @RequestParam(required = false) Long conversation,
            @RequestParam(required = false) String context,
            @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String acceptLanguage) {
        SuggestionScope scope;
        if (conversation != null) {
            ConversationView view = conversations.find(conversation).orElseThrow(() -> notFound(conversation));
            scope = new SuggestionScope(view.pinnedOfferId(), view.context());
        } else {
            scope = scopeOf(context);
        }
        return suggestions.suggestions(scope, SuggestionService.language(acceptLanguage));
    }

    /**
     * Two or three follow-up questions under a finished turn, none under any other (ISC-457); 404 for
     * a turn the conversation does not hold.
     */
    @GetMapping("/conversations/{id}/turns/{turnId}/followups")
    List<FollowUp> followUps(
            @PathVariable long id,
            @PathVariable long turnId,
            @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String acceptLanguage) {
        return followUps
                .of(id, turnId, SuggestionService.language(acceptLanguage))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "no turn " + turnId + " in conversation " + id));
    }

    /** An offer pin of the {@code chatCtx} form. */
    private static final Pattern OFFER_PIN = Pattern.compile("o:(\\d{1,18})");

    /** The pins a {@code ?context=} names; the first offer is also the scope's pinned offer. */
    static SuggestionScope scopeOf(String context) {
        if (context == null || context.isBlank()) {
            return SuggestionScope.NONE;
        }
        List<ChatContextItem> items = new ArrayList<>();
        for (String entry : context.split(",")) {
            Matcher pin = OFFER_PIN.matcher(entry.strip());
            if (pin.matches()) {
                items.add(new ChatContextItem(ChatContextKind.OFFER, Long.parseLong(pin.group(1)), null, null, null));
            }
        }
        return new SuggestionScope(items.isEmpty() ? null : items.getFirst().offerId(), items);
    }

    /** 404 for an id that does not exist, which the drawer renders as an empty state. */
    @GetMapping("/conversations/{id}")
    ConversationView conversation(@PathVariable long id) {
        return conversations.find(id).orElseThrow(() -> notFound(id));
    }

    @PostMapping("/conversations")
    @ResponseStatus(HttpStatus.CREATED)
    ConversationView create(@RequestBody NewConversation body) {
        long id;
        try {
            id = conversations.create(body.pinnedOfferId(), body.context());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (DataIntegrityViolationException e) {
            // An offer's foreign key is the one constraint a well-formed body can break. Asking the
            // insert rather than checking first leaves no window for the offer to vanish in between.
            Long pin = body.pin();
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, pin != null ? "no offer " + pin : "an offer of the context does not exist");
        }
        return conversations.find(id).orElseThrow(() -> notFound(id));
    }

    /**
     * Takes its turns and their tool calls with it. A turn still streaming in it is stopped first,
     * the same stop as {@code …/stop}, and waited for, so its model connection is closed and its
     * last write done before the rows go. A turn that outlasts {@link #STOP_BEFORE_DELETE} is
     * deleted under anyway and ends as a turn whose conversation vanished.
     */
    @DeleteMapping("/conversations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable long id) {
        turns.stopAll(id, STOP_BEFORE_DELETE);
        if (!conversations.delete(id)) {
            throw notFound(id);
        }
    }

    @PostMapping(path = "/conversations/{id}/turns", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    ResponseEntity<SseEmitter> ask(@PathVariable long id, @Valid @RequestBody NewTurn body) {
        if (!conversations.exists(id)) {
            throw notFound(id);
        }
        if (capability.model().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no chat model is configured");
        }
        SseEmitter emitter = emitter();
        Runnable quiet = keepAlive(emitter);
        try {
            turns.start(id, body.question(), sink(emitter), ended(emitter, quiet));
        } catch (RejectedExecutionException e) {
            quiet.run();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "too many turns are running");
        }
        return unbuffered(emitter);
    }

    private SseEmitter emitter() {
        return new SseEmitter(NO_CONTAINER_TIMEOUT);
    }

    /**
     * Starts the stream's heartbeat and returns what stops it. It stops itself too once a write
     * fails — the reader is gone — and whenever the emitter completes, times out or errs.
     */
    private Runnable keepAlive(SseEmitter emitter) {
        AtomicReference<ScheduledFuture<?>> beat = new AtomicReference<>();
        Runnable quiet = () -> {
            ScheduledFuture<?> running = beat.get();
            if (running != null) {
                running.cancel(false);
            }
        };
        beat.set(heartbeats.scheduleAtFixedRate(
                () -> {
                    try {
                        emitter.send(SseEmitter.event().comment("keep-alive"));
                    } catch (IOException | IllegalStateException e) {
                        quiet.run();
                    }
                },
                properties.chat().heartbeat().toMillis(),
                properties.chat().heartbeat().toMillis(),
                TimeUnit.MILLISECONDS));
        emitter.onCompletion(quiet);
        emitter.onTimeout(quiet);
        emitter.onError(error -> quiet.run());
        return quiet;
    }

    /** What the turn runs when it is over: the heartbeat stops before the stream completes. */
    private static Runnable ended(SseEmitter emitter, Runnable quiet) {
        return () -> {
            quiet.run();
            emitter.complete();
        };
    }

    @PreDestroy
    void shutdown() {
        heartbeats.shutdownNow();
    }

    /** The stream with the two headers that keep every hop between here and the browser from holding it. */
    private static ResponseEntity<SseEmitter> unbuffered(SseEmitter emitter) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                .header("X-Accel-Buffering", "no")
                .body(emitter);
    }

    /**
     * Sends each event as one server-sent event named after it. A reader that went away stops the
     * sending and not the turn: the turn still runs to its end and is stored, so reopening the
     * conversation shows the answer the closed tab missed.
     */
    private static Consumer<ChatEvent> sink(SseEmitter emitter) {
        AtomicBoolean gone = new AtomicBoolean();
        return event -> {
            if (gone.get()) {
                return;
            }
            try {
                emitter.send(SseEmitter.event().name(event.event()).data(event, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                gone.set(true);
            }
        };
    }

    /** Cancels the model call; the stream then ends with {@code done} in state {@code STOPPED}. */
    @PostMapping("/conversations/{id}/turns/{turnId}/stop")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void stop(@PathVariable long id, @PathVariable long turnId) {
        if (!turns.stop(id, turnId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no running turn " + turnId + " in " + id);
        }
    }

    /** The same question again, as a new turn that records the one it replaces. */
    @PostMapping(path = "/conversations/{id}/turns/{turnId}/regenerate", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    ResponseEntity<SseEmitter> regenerate(@PathVariable long id, @PathVariable long turnId) {
        if (capability.model().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no chat model is configured");
        }
        SseEmitter emitter = emitter();
        Runnable quiet = keepAlive(emitter);
        try {
            if (!turns.regenerate(id, turnId, sink(emitter), ended(emitter, quiet))) {
                quiet.run();
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no turn " + turnId + " in " + id);
            }
        } catch (RejectedExecutionException e) {
            quiet.run();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "too many turns are running");
        }
        return unbuffered(emitter);
    }

    private static ResponseStatusException notFound(long id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "no conversation " + id);
    }
}
