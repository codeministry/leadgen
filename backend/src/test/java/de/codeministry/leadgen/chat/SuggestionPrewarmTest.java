/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import de.codeministry.leadgen.Databases;
import de.codeministry.leadgen.analytics.PipelineRunRecorder;
import de.codeministry.leadgen.application.ApplicationService;
import de.codeministry.leadgen.application.ApplicationStatus;
import de.codeministry.leadgen.application.ApplicationUpdate;
import de.codeministry.leadgen.chat.suggest.Suggestion;
import de.codeministry.leadgen.chat.suggest.SuggestionScope;
import de.codeministry.leadgen.chat.suggest.SuggestionService;
import de.codeministry.leadgen.chat.suggest.SuggestionWarmup;
import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.event.DataChanged;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Spec 027: opening the chat never waits on a model. The suggestions answer from a stored
 * phrasing or the catalog at once, whatever the model takes (ISC-527), and a miss is phrased in
 * the background for the next open, once per key however many opens miss it (ISC-528). A run's
 * end and a status change prepare the next open in the last open's language, and neither write
 * waits on the model or depends on it (ISC-529). Changes during a slow call cost one more call,
 * for the newest snapshot, and a trigger for what is stored costs none (ISC-530).
 */
@SpringBootTest
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class SuggestionPrewarmTest {

    private static final ModelStub MODEL = ModelStub.start();

    /** Created once, never inside the property supplier — see {@code LlmBudgetTest}. */
    private static final Path STUBBED = MODEL.configuration().resolve("pipeline.yaml");

    private static final String ORIGINAL = read(STUBBED);

    /** Two chunks of five seconds each: a model that takes ten seconds to answer. */
    private static final Duration SLOW = Duration.ofSeconds(5);

    /** The retry window, shortened from its default of minutes so a case can wait it out (ISC-535). */
    private static final Duration RETRY_AFTER = Duration.ofSeconds(2);

    /**
     * The quiet window, shortened from its default of 30 s so a burst can be timed (ISC-537). A
     * second rather than less: a status move is a transaction, and on a loaded runner one took longer
     * than a 300 ms window, which split a burst the test meant as one.
     */
    private static final Duration QUIET = Duration.ofSeconds(1);

    /** What "at once" means here: an open, a snapshot query and a map lookup, far below this. */
    private static final Duration AT_ONCE = Duration.ofSeconds(1);

    private static final List<String> CATALOG_EN = List.of(
            "0 offers are on the shortlist. Which fits my profile best?",
            "0 applications are open. Where does each one stand?");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = Databases.postgres();

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add("leadgen.config-dir", () -> STUBBED.getParent().toString());
        registry.add("leadgen.chat.suggestion-retry-after", RETRY_AFTER::toString);
        registry.add("leadgen.chat.suggestion-quiet-window", QUIET::toString);
    }

    @Autowired
    private SuggestionService suggestions;

    @Autowired
    private SuggestionWarmup warmup;

    @Autowired
    private PipelineRunRecorder recorder;

    @Autowired
    private ApplicationService applications;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private ConfigRegistry config;

    @Autowired
    private JdbcTemplate jdbc;

    /** Boot's scheduler, one thread, which the ingest pass, the score poll and the config watch run on. */
    @Autowired
    private TaskScheduler scheduler;

    @AfterAll
    static void stop() {
        MODEL.close();
    }

    @BeforeEach
    void reset() throws IOException {
        awaitIdle();
        MODEL.reset();
        // No RESTART IDENTITY: every test starts at a run id no earlier test saw, so a phrasing
        // stored by one test is never the next one's snapshot.
        jdbc.execute("TRUNCATE application_event, application, pipeline_run, offer, source CASCADE");
        jdbc.update("DELETE FROM chat_call_budget");
        jdbc.update("DELETE FROM llm_call_budget");
        run("COMPLETE");
        configure(ORIGINAL);
    }

    @Test
    void anUnpinnedMissAnswersTheCatalogAtOnceWhileTheModelTakesTenSeconds() {
        MODEL.enqueue(ModelStub.text(SLOW, phrased("0")));

        long started = System.nanoTime();
        List<Suggestion> shown = suggestions.suggestions(SuggestionScope.NONE, "en");
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(took).isLessThan(AT_ONCE);
        assertThat(shown)
                .extracting(Suggestion::trigger)
                .containsExactly(SuggestionService.SHORTLIST, SuggestionService.OPEN_APPLICATIONS);
        assertThat(shown).extracting(Suggestion::text).containsExactlyElementsOf(CATALOG_EN);
        assertThat(shown).extracting(Suggestion::count).containsExactly(0, 0);
    }

    @Test
    void aPinnedMissAnswersTheCatalogAtOnceWhileTheModelTakesTenSeconds() {
        MODEL.enqueue(ModelStub.text(SLOW, phrased("0")));

        long started = System.nanoTime();
        List<Suggestion> shown = suggestions.suggestions(new SuggestionScope(42L, List.of()), "en");
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(took).isLessThan(AT_ONCE);
        assertThat(shown)
                .extracting(Suggestion::trigger)
                .containsExactly(
                        SuggestionService.PINNED, SuggestionService.SHORTLIST, SuggestionService.OPEN_APPLICATIONS);
        assertThat(shown).extracting(Suggestion::text).endsWith(CATALOG_EN.toArray(String[]::new));
    }

    @Test
    void aStoredPhrasingAnswersAtOnceWithoutAskingTheModelAgain() {
        MODEL.enqueue(ModelStub.text(Duration.ZERO, phrased("0")));
        suggestions.suggestions(SuggestionScope.NONE, "en");
        awaitIdle();
        MODEL.enqueue(ModelStub.text(SLOW, phrased("0")));

        long started = System.nanoTime();
        List<Suggestion> shown = suggestions.suggestions(SuggestionScope.NONE, "en");
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(took).isLessThan(AT_ONCE);
        assertThat(shown)
                .extracting(Suggestion::text)
                .containsExactly("Which of the 0 shortlisted offers fits?", "How are the 0 open applications doing?");
        assertThat(shown).extracting(Suggestion::count).containsExactly(0, 0);
        awaitIdle();
        assertThat(MODEL.bodies()).hasSize(1);
    }

    @Test
    void aPinnedMissIsPhrasedInTheBackgroundForTheNextOpen() {
        SuggestionScope pinned = new SuggestionScope(42L, List.of());
        MODEL.enqueue(ModelStub.text(Duration.ofMillis(200), phrased("0")));

        List<Suggestion> first = suggestions.suggestions(pinned, "en");
        awaitIdle();
        List<Suggestion> second = suggestions.suggestions(pinned, "en");

        assertThat(first).extracting(Suggestion::text).endsWith(CATALOG_EN.toArray(String[]::new));
        assertThat(second)
                .extracting(Suggestion::text)
                .endsWith("Which of the 0 shortlisted offers fits?", "How are the 0 open applications doing?");
        assertThat(MODEL.bodies()).hasSize(1);
        assertThat(chatCalls()).isEqualTo(1);
    }

    @Test
    void fiveConcurrentMissesOnOneKeyCostOneModelCall() throws InterruptedException {
        SuggestionScope pinned = new SuggestionScope(43L, List.of());
        MODEL.enqueue(ModelStub.text(Duration.ofMillis(500), phrased("0")));
        MODEL.enqueue(ModelStub.text(Duration.ZERO, phrased("0")));

        List<Thread> opens = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            opens.add(Thread.ofVirtual().start(() -> suggestions.suggestions(pinned, "en")));
        }
        for (Thread open : opens) {
            open.join();
        }
        awaitIdle();

        assertThat(MODEL.bodies()).hasSize(1);
    }

    @Test
    void aRunEndWarmsTheNextOpenInTheLastUsedLanguageWithoutDelayingTheRun() {
        MODEL.enqueue(ModelStub.text(Duration.ZERO, phrased("0")));
        suggestions.suggestions(SuggestionScope.NONE, "de");
        awaitIdle();
        // A run that started after the last open and waits for its batch: a new snapshot.
        run("AWAITING_BATCH");
        MODEL.enqueue(ModelStub.text(SLOW, phrased("0")));

        long started = System.nanoTime();
        recorder.complete(0, 0, false);
        Duration took = Duration.ofNanos(System.nanoTime() - started);
        awaitIdle();
        List<String> after = MODEL.bodies();
        List<Suggestion> shown = suggestions.suggestions(SuggestionScope.NONE, "de");
        awaitIdle();

        assertThat(took).isLessThan(AT_ONCE);
        assertThat(after).hasSize(2);
        assertThat(after.getLast()).contains("German");
        assertThat(shown)
                .extracting(Suggestion::text)
                .containsExactly("Which of the 0 shortlisted offers fits?", "How are the 0 open applications doing?");
        assertThat(MODEL.bodies()).hasSize(2);
    }

    @Test
    void aStatusChangeWarmsTheNextOpenWithoutDelayingTheWrite() {
        long application = applications.open(offer(), ApplicationStatus.NEW);
        awaitIdle();
        MODEL.enqueue(ModelStub.text(Duration.ZERO, phrased("0")));
        suggestions.suggestions(SuggestionScope.NONE, "de");
        awaitIdle();
        int before = MODEL.bodies().size();
        MODEL.enqueue(ModelStub.text(SLOW, phrased("0")));

        long started = System.nanoTime();
        applications.update(
                application, new ApplicationUpdate(ApplicationStatus.SHORTLISTED, null, null, null, null, null));
        Duration took = Duration.ofNanos(System.nanoTime() - started);
        awaitIdle();
        int afterChange = MODEL.bodies().size();
        suggestions.suggestions(SuggestionScope.NONE, "de");
        awaitIdle();

        assertThat(took).isLessThan(AT_ONCE);
        assertThat(afterChange).isEqualTo(before + 1);
        assertThat(MODEL.bodies().getLast()).contains("German");
        assertThat(MODEL.bodies()).hasSize(afterChange);
    }

    @Test
    void aFailingWarmUpLeavesTheRunAndTheStatusWriteAsTheyWere() {
        // An open first: before one since start, a data change prepares nothing (ISC-536).
        suggestions.suggestions(SuggestionScope.NONE, "en");
        long application = applications.open(offer(), ApplicationStatus.NEW);
        awaitIdle();
        // A run that started after the last open and waits for its batch: a new snapshot.
        run("AWAITING_BATCH");
        // Nothing enqueued: every warm-up the two writes start meets a 500.

        recorder.complete(0, 0, false);
        applications.update(
                application, new ApplicationUpdate(ApplicationStatus.SHORTLISTED, null, null, null, null, null));
        awaitIdle();

        assertThat(jdbc.queryForObject("SELECT status FROM pipeline_run ORDER BY id DESC LIMIT 1", String.class))
                .isEqualTo("COMPLETE");
        assertThat(jdbc.queryForObject("SELECT status FROM application WHERE id = ?", String.class, application))
                .isEqualTo("SHORTLISTED");
        assertThat(MODEL.bodies()).isNotEmpty();
    }

    @Test
    void changesDuringASlowWarmUpCollapseIntoOneMoreForTheNewestSnapshot() {
        // An open first: before one since start, a data change prepares nothing (ISC-536).
        suggestions.suggestions(SuggestionScope.NONE, "en");
        long application = applications.open(offer(), ApplicationStatus.NEW);
        awaitIdle();
        MODEL.reset();
        MODEL.enqueue(ModelStub.text(SLOW, phrased("0")));
        MODEL.enqueue(ModelStub.text(Duration.ZERO, phrased("0")));
        MODEL.enqueue(ModelStub.text(Duration.ZERO, phrased("0")));

        move(application, ApplicationStatus.SHORTLISTED);
        awaitFirstRequest();
        for (ApplicationStatus next : List.of(
                ApplicationStatus.NEW,
                ApplicationStatus.SHORTLISTED,
                ApplicationStatus.NEW,
                ApplicationStatus.SHORTLISTED,
                ApplicationStatus.NEW)) {
            move(application, next);
        }
        awaitIdle();
        int afterSix = MODEL.bodies().size();
        events.publishEvent(new DataChanged("test, nothing moved"));
        awaitIdle();
        int afterStored = MODEL.bodies().size();
        suggestions.suggestions(SuggestionScope.NONE, lastPhrasedLanguage());
        awaitIdle();

        assertThat(afterSix).isEqualTo(2);
        assertThat(afterStored).isEqualTo(2);
        assertThat(MODEL.bodies()).hasSize(2);
    }

    @Test
    void aSpentChatDayStoresNothingSendsNothingAndShowsTheCatalog() throws IOException {
        configure(ORIGINAL.replace("${CHAT_MAX_CALLS_PER_DAY:200}", "0"));
        MODEL.enqueue(ModelStub.text(Duration.ZERO, phrased("0")));

        List<Suggestion> first = suggestions.suggestions(SuggestionScope.NONE, "en");
        awaitIdle();
        List<Suggestion> second = suggestions.suggestions(SuggestionScope.NONE, "en");
        awaitIdle();

        assertThat(first).extracting(Suggestion::text).containsExactlyElementsOf(CATALOG_EN);
        assertThat(second).extracting(Suggestion::text).containsExactlyElementsOf(CATALOG_EN);
        assertThat(MODEL.bodies()).isEmpty();
        assertThat(storedRows()).isZero();
        assertThat(llmCalls()).isZero();
    }

    @Test
    void withoutAChatModelNothingIsAskedOrStored() throws IOException {
        configure(ORIGINAL.replaceAll("(?m)^(\\s*)(chat|scoring): \\S.*$", "$1$2: \"\""));

        List<Suggestion> shown = suggestions.suggestions(SuggestionScope.NONE, "en");
        awaitIdle();

        assertThat(shown).extracting(Suggestion::text).containsExactlyElementsOf(CATALOG_EN);
        assertThat(MODEL.bodies()).isEmpty();
        assertThat(storedRows()).isZero();
    }

    @Test
    void aFailingModelIsAskedOncePerKeyNotOncePerOpen() {
        // Nothing enqueued: the stub answers 500 to every request.
        List<Suggestion> first = suggestions.suggestions(SuggestionScope.NONE, "en");
        awaitIdle();
        List<Suggestion> second = suggestions.suggestions(SuggestionScope.NONE, "en");
        awaitIdle();
        List<Suggestion> third = suggestions.suggestions(SuggestionScope.NONE, "en");
        awaitIdle();

        assertThat(List.of(first, second, third))
                .allSatisfy(
                        shown -> assertThat(shown).extracting(Suggestion::text).containsExactlyElementsOf(CATALOG_EN));
        assertThat(MODEL.bodies()).hasSize(1);
        assertThat(storedRows()).isZero();
        assertThat(chatCalls()).isEqualTo(1);
        assertThat(llmCalls()).isZero();
    }

    @Test
    void aPhrasingThatChangesANumberIsStoredWithTheCatalogSentenceInItsPlace() {
        MODEL.enqueue(ModelStub.text(Duration.ZERO, phrased("7")));

        suggestions.suggestions(SuggestionScope.NONE, "en");
        awaitIdle();
        List<Suggestion> shown = suggestions.suggestions(SuggestionScope.NONE, "en");
        awaitIdle();

        assertThat(shown).extracting(Suggestion::text).containsExactlyElementsOf(CATALOG_EN);
        assertThat(storedRows()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT phrased ->> 'shortlist' FROM chat_suggestion_phrasing ORDER BY created_at DESC LIMIT 1",
                        String.class))
                .isEqualTo(CATALOG_EN.getFirst());
        assertThat(MODEL.bodies()).hasSize(1);
        assertThat(llmCalls()).isZero();
    }

    @Test
    void aFailedKeyIsAskedOnceMoreAfterItsRetryWindowAndNotBefore() throws InterruptedException {
        // Nothing enqueued: the stub answers 500 to every request.
        suggestions.suggestions(SuggestionScope.NONE, "en");
        awaitIdle();
        suggestions.suggestions(SuggestionScope.NONE, "en");
        awaitIdle();
        int insideTheWindow = MODEL.bodies().size();

        Thread.sleep(RETRY_AFTER.plusMillis(300).toMillis());
        List<Suggestion> after = suggestions.suggestions(SuggestionScope.NONE, "en");
        awaitIdle();
        int afterTheWindow = MODEL.bodies().size();
        suggestions.suggestions(SuggestionScope.NONE, "en");
        awaitIdle();

        assertThat(insideTheWindow).isEqualTo(1);
        assertThat(afterTheWindow).isEqualTo(2);
        assertThat(MODEL.bodies()).hasSize(2);
        assertThat(after).extracting(Suggestion::text).containsExactlyElementsOf(CATALOG_EN);
        assertThat(storedRows()).isZero();
    }

    @Test
    void anUnbuildableChatModelIsTriedOncePerRetryWindowNotOncePerOpen(CapturedOutput output)
            throws IOException, InterruptedException {
        // Named, but with nowhere to send a request: the model cannot be built.
        configure(ORIGINAL.replaceAll("(?m)^  base_url: .*$", "  base_url: \"\""));

        for (int open = 0; open < 3; open++) {
            assertThat(suggestions.suggestions(SuggestionScope.NONE, "en"))
                    .extracting(Suggestion::text)
                    .containsExactlyElementsOf(CATALOG_EN);
            awaitIdle();
        }
        long insideTheWindow = unbuildable(output);
        Thread.sleep(RETRY_AFTER.plusMillis(300).toMillis());
        suggestions.suggestions(SuggestionScope.NONE, "en");
        awaitIdle();

        assertThat(insideTheWindow).isEqualTo(1);
        assertThat(unbuildable(output)).isEqualTo(2);
        assertThat(MODEL.bodies()).isEmpty();
        assertThat(chatCalls()).isZero();
    }

    @Test
    void aBurstOfStatusChangesInsideTheQuietWindowCostsOneCallForTheNewestSnapshot() throws InterruptedException {
        suggestions.suggestions(SuggestionScope.NONE, "en");
        long application = applications.open(offer(), ApplicationStatus.NEW);
        awaitIdle();
        MODEL.reset();
        MODEL.enqueue(ModelStub.text(Duration.ZERO, phrased("1")));
        MODEL.enqueue(ModelStub.text(Duration.ZERO, phrased("1")));
        MODEL.enqueue(ModelStub.text(Duration.ZERO, phrased("1")));

        burst(application, 6, () -> {});
        awaitIdle();
        int afterTheBurst = MODEL.bodies().size();
        List<Suggestion> shown = suggestions.suggestions(SuggestionScope.NONE, "en");
        awaitIdle();
        int afterTheOpen = MODEL.bodies().size();

        move(application, ApplicationStatus.SHORTLISTED);
        awaitIdle();
        move(application, ApplicationStatus.NEW);
        awaitIdle();

        assertThat(afterTheBurst).isEqualTo(1);
        assertThat(afterTheOpen)
                .as("the burst phrased the newest snapshot, so the open is a hit")
                .isEqualTo(1);
        assertThat(shown).extracting(Suggestion::text).contains("How are the 1 open applications doing?");
        assertThat(MODEL.bodies())
                .as("two changes a quiet window apart cost two calls")
                .hasSize(3);
    }

    @Test
    void reopeningTheChatAfterEveryMoveCostsOneCallPerPauseNotOnePerMove() throws InterruptedException {
        suggestions.suggestions(SuggestionScope.NONE, "en");
        long application = applications.open(offer(), ApplicationStatus.NEW);
        awaitIdle();
        MODEL.reset();
        MODEL.enqueue(ModelStub.text(Duration.ZERO, phrased("1")));
        MODEL.enqueue(ModelStub.text(Duration.ZERO, phrased("1")));

        // The docked panel asks again after each move: every open is a new snapshot, and a miss.
        burst(application, 5, () -> suggestions.suggestions(SuggestionScope.NONE, "en"));
        awaitIdle();

        assertThat(MODEL.bodies())
                .as("five moves and five missed opens inside one quiet window")
                .hasSize(1);
    }

    @Test
    void aChangeIsPreparedWhileTheSchedulerIsBusyWithAPass() throws InterruptedException {
        suggestions.suggestions(SuggestionScope.NONE, "en");
        long application = applications.open(offer(), ApplicationStatus.NEW);
        awaitIdle();
        MODEL.reset();
        MODEL.enqueue(ModelStub.text(Duration.ZERO, phrased("1")));

        // A scheduled ingest pass holds Boot's one scheduler thread for as long as it runs.
        CountDownLatch passRunning = new CountDownLatch(1);
        CountDownLatch passDone = new CountDownLatch(1);
        scheduler.schedule(
                () -> {
                    passRunning.countDown();
                    try {
                        passDone.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                },
                Instant.now());
        try {
            assertThat(passRunning.await(10, TimeUnit.SECONDS)).isTrue();
            move(application, ApplicationStatus.SHORTLISTED);
            // Polled rather than slept: a loaded runner may take longer than any fixed pause, and
            // only the pass's end may not be what lets the window pass.
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (MODEL.bodies().isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }

            assertThat(passDone.getCount())
                    .as("the pass still holds the scheduler")
                    .isOne();
            assertThat(MODEL.bodies())
                    .as("the quiet window passed while the pass still held the scheduler")
                    .hasSize(1);
        } finally {
            passDone.countDown();
        }
        awaitIdle();
    }

    @Test
    void aMissIsPhrasedInItsOwnLanguageWhateverTheNextOpenAsks() {
        // The worker is held on a pinned miss while a German and then an English open miss.
        MODEL.enqueue(ModelStub.text(Duration.ofMillis(700), phrased("0")));
        MODEL.enqueue(ModelStub.text(Duration.ZERO, phrased("0")));
        MODEL.enqueue(ModelStub.text(Duration.ZERO, phrased("0")));
        suggestions.suggestions(new SuggestionScope(42L, List.of()), "en");
        awaitFirstRequest();

        suggestions.suggestions(SuggestionScope.NONE, "de");
        suggestions.suggestions(SuggestionScope.NONE, "en");
        awaitIdle();

        assertThat(MODEL.bodies()).hasSize(3);
        assertThat(MODEL.bodies().get(1))
                .as("the German miss, though the last open asked in English")
                .contains("German");
        assertThat(MODEL.bodies().get(2)).doesNotContain("German");
    }

    private static long unbuildable(CapturedOutput output) {
        return output.getOut()
                .lines()
                .filter(line -> line.contains("is named but could not be built"))
                .count();
    }

    private int storedRows() {
        // Only this test's snapshot: earlier tests of the class stored theirs under older run ids.
        return jdbc.queryForObject(
                "SELECT count(*) FROM chat_suggestion_phrasing WHERE snapshot LIKE 'suggestions ' || ? || ':%'",
                Integer.class, jdbc.queryForObject("SELECT max(id)::text FROM pipeline_run", String.class));
    }

    private int llmCalls() {
        return jdbc.queryForObject("SELECT coalesce(sum(calls), 0) FROM llm_call_budget", Integer.class);
    }

    private void move(long application, ApplicationStatus to) {
        applications.update(application, new ApplicationUpdate(to, null, null, null, null, null));
    }

    /**
     * {@code changes} status moves a sixth of the quiet window apart, so together they span about one
     * window and only a window restarted by each change holds them together; {@code afterEach} runs
     * after every move. A runner so loaded that two committed moves came most of a window apart
     * cannot show a burst at all, so the case is skipped then rather than failed.
     */
    private void burst(long application, int changes, Runnable afterEach) throws InterruptedException {
        ApplicationStatus status = ApplicationStatus.NEW;
        long previous = 0;
        long widest = 0;
        for (int change = 0; change < changes; change++) {
            status = status == ApplicationStatus.NEW ? ApplicationStatus.SHORTLISTED : ApplicationStatus.NEW;
            move(application, status);
            long committed = System.nanoTime();
            if (change > 0) {
                widest = Math.max(widest, committed - previous);
            }
            previous = committed;
            afterEach.run();
            Thread.sleep(QUIET.toMillis() / 6);
        }
        assumeThat(Duration.ofNanos(widest))
                .as("the runner kept two moves of the burst well inside one quiet window")
                .isLessThan(QUIET.multipliedBy(4).dividedBy(5));
    }

    /** The language the warm-up phrased in: whatever the last open of an earlier test asked. */
    private String lastPhrasedLanguage() {
        return MODEL.bodies().getLast().contains("German") ? "de" : "en";
    }

    private void awaitFirstRequest() {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (MODEL.bodies().isEmpty()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("no warm-up reached the model within 10 s");
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    private long offer() {
        long source =
                jdbc.queryForObject("INSERT INTO source (name, kind) VALUES ('test', 'file') RETURNING id", Long.class);
        return jdbc.queryForObject("""
                INSERT INTO offer (source_id, external_id, title, url, fingerprint, status, score_band,
                                   score_value, tags, ingested_at)
                VALUES (?, 'warm', 'Offer warm', 'https://example.invalid/warm', 'warm', 'PASSED', 'SHORTLISTED',
                        95, '{}'::text[], now())
                RETURNING id
                """, Long.class, source);
    }

    /** Waits for the background phrasing to finish, so no call leaks into the next test. */
    private void awaitIdle() {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!warmup.idle()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("the suggestion warm-up did not finish within 30 s");
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    private int chatCalls() {
        return jdbc.queryForObject("SELECT coalesce(sum(calls), 0) FROM chat_call_budget", Integer.class);
    }

    /** A model reply phrasing the two evergreen candidates, both naming {@code number}. */
    private static String phrased(String number) {
        return "{\"shortlist\":\"Which of the " + number + " shortlisted offers fits?\","
                + "\"openApplications\":\"How are the " + number + " open applications doing?\"}";
    }

    private void run(String status) {
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO pipeline_run (
                    started_at, finished_at, ruleset_version, score_model, status,
                    documents, extracted, written, merged,
                    filter_considered, filter_passed,
                    enrich_considered, enriched, incomplete, from_cache, requests,
                    score_considered, scored, unscored, shortlisted, review, submitted,
                    packaged, digest_written)
                VALUES (?, ?, '1', 'm', ?, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, true)
                """, Timestamp.from(now.minusSeconds(60)), Timestamp.from(now), status);
    }

    private void configure(String pipeline) throws IOException {
        Files.writeString(STUBBED, pipeline, StandardCharsets.UTF_8);
        config.reload();
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
