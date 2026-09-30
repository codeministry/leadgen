/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat;

import static de.codeministry.leadgen.chat.ChatSourceKind.APPLICATION;
import static de.codeministry.leadgen.chat.ChatSourceKind.OFFER;
import static org.assertj.core.api.Assertions.assertThat;

import de.codeministry.leadgen.chat.TurnLedger.Citation;
import de.codeministry.leadgen.chat.TurnLedger.Ref;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The stream stage between the model and the screen: a marker becomes a numbered link only when
 * this turn's tools returned the id and the row is still inside the working set or the archive.
 *
 * <p>The predicate is stubbed here; T25 hands the filter the SQL one. The end-to-end half — a
 * real turn whose stored answer holds one link and two unverified ids — is {@code ChatGroundingTest}
 * (T27).
 */
class CitationFilterTest {

    /** Offer 13 was returned by a tool and has since been knocked out of the working set. */
    private static final long KNOCKED_OUT = 13;

    private TurnLedger ledger;
    private CitationFilter filter;
    private final List<String> emitted = new ArrayList<>();

    @BeforeEach
    void turn() {
        ledger = new TurnLedger();
        ledger.record(
                "search_offers",
                "Search",
                "{}",
                List.of(new Ref(OFFER, 7), new Ref(OFFER, 9), new Ref(OFFER, KNOCKED_OUT)),
                3);
        ledger.record("list_applications", "Applications", "{}", List.of(new Ref(APPLICATION, 4)), 2);
        Set<Long> reachableOffers = Set.of(7L, 9L, 99L);
        filter = new CitationFilter(ledger, (kind, id) -> kind == APPLICATION || reachableOffers.contains(id));
    }

    @Test
    void aReturnedIdBecomesANumberedLink() {
        assertThat(run("Look at [[offer:7]] first.")).isEqualTo("Look at [1](cite:offer/7) first.");
        assertThat(ledger.citations()).containsExactly(new Citation(1, OFFER, 7));
    }

    @Test
    void anApplicationBecomesAnApplicationLink() {
        assertThat(run("You applied [[application:4]].")).isEqualTo("You applied [1](cite:application/4).");
    }

    @Test
    void anIdNoToolReturnedIsUnverifiedTextAndNeverALink() {
        // 99 is inside the working set, but nothing this turn returned it: the model made it up
        // or remembered it, and neither is grounding.
        assertThat(run("See [[offer:99]].")).isEqualTo("See ⟨unverified:99⟩.");
        assertThat(ledger.citations()).isEmpty();
    }

    @Test
    void aKnockedOutIdIsUnverifiedEvenThoughAToolReturnedIt() {
        assertThat(run("See [[offer:13]].")).isEqualTo("See ⟨unverified:13⟩.");
        assertThat(ledger.citations()).isEmpty();
    }

    @Test
    void anApplicationIdIsNotAnOfferId() {
        // Application 4 was returned; offer 4 was not.
        assertThat(run("[[offer:4]]")).isEqualTo("⟨unverified:4⟩");
    }

    @Test
    void aMarkerSplitOneCharacterPerChunkIsNeverEmittedHalf() {
        String text = "Both [[offer:9]] and [[offer:7]] fit.";
        for (char c : text.toCharArray()) {
            String out = filter.accept(String.valueOf(c));
            assertThat(out).doesNotContain("[[").doesNotContain("]]");
            if (!out.isEmpty()) {
                emitted.add(out);
            }
        }
        emitted.add(filter.finish());

        assertThat(emitted).noneMatch(piece -> piece.contains("[[") || piece.startsWith("offer:"));
        assertThat(String.join("", emitted)).isEqualTo("Both [1](cite:offer/9) and [2](cite:offer/7) fit.");
    }

    @Test
    void twoCitationsOfTheSameIdShareOneNumber() {
        assertThat(run("[[offer:9]], [[offer:7]] and again [[offer:9]]"))
                .isEqualTo("[1](cite:offer/9), [2](cite:offer/7) and again [1](cite:offer/9)");
        assertThat(ledger.citations()).containsExactly(new Citation(1, OFFER, 9), new Citation(2, OFFER, 7));
    }

    @Test
    void textWithoutMarkersPassesThroughUnchanged() {
        String text = "No offers matched. Arrays like a[0] and [links](x) stay as they are.";
        assertThat(run(text)).isEqualTo(text);
    }

    @Test
    void plainTextIsNotHeldBack() {
        // Only a possible marker waits for its end; everything before it goes out at once.
        assertThat(filter.accept("Hello ")).isEqualTo("Hello ");
        assertThat(filter.accept("there [")).isEqualTo("there ");
        assertThat(filter.accept("x")).isEqualTo("[x");
    }

    @Test
    void anUnterminatedMarkerIsFlushedAsPlainTextAtTheEnd() {
        assertThat(filter.accept("Cut off [[offer:7")).isEqualTo("Cut off ");
        assertThat(filter.finish()).isEqualTo("[[offer:7");
        assertThat(ledger.citations()).isEmpty();
    }

    @Test
    void somethingInDoubleBracketsThatIsNoMarkerStaysText() {
        assertThat(run("A [[wiki link]] here.")).isEqualTo("A [[wiki link]] here.");
    }

    /**
     * Finding 2: only the filter mints a {@code cite:} link. One the model writes itself — for an id
     * no tool returned, for one a tool did, split across chunks, or dressed up another way — never
     * reaches the reader as a link and never takes a number.
     */
    @Test
    void aResolvedLinkTheModelWritesItselfArrivesUnverified() {
        assertThat(run("See [1](cite:offer/42).")).isEqualTo("See ⟨unverified:42⟩.");
        assertThat(filter.accept("And [2](ci") + filter.accept("te:offer/7).") + filter.finish())
                .isEqualTo("And ⟨unverified:7⟩.");
        assertThat(run("Also [here](cite:application/4), <cite:offer/9>, [3]: cite:offer/9, (cite&#58;offer/9)"))
                .doesNotContainIgnoringCase("cite:")
                .doesNotContain("cite&#58;");
        assertThat(ledger.citations()).isEmpty();
        // A marker still resolves right after a neutralised link.
        assertThat(run("[1](cite:offer/42) [[offer:7]]")).isEqualTo("⟨unverified:42⟩ [1](cite:offer/7)");
    }

    /** Finding 2: an earlier answer goes back to the model in the marker format only. */
    @Test
    void aStoredAnswerIsTurnedBackIntoMarkersForTheModel() {
        assertThat(CitationFilter.asMarkers(
                        "Take [1](cite:offer/7), not ⟨unverified:42⟩; see [2](cite:application/4)."))
                .isEqualTo("Take [[offer:7]], not 42; see [[application:4]].");
    }

    /**
     * Fix 4B-10: reachability is a database round-trip, asked once per row and turn. Eight markers
     * of one cited offer ask once, and eight of one knocked-out offer ask once as well.
     */
    @Test
    void eightRepeatsOfOneMarkerAskReachabilityOnce() {
        Map<Long, Integer> asked = new HashMap<>();
        filter = new CitationFilter(ledger, (kind, id) -> {
            asked.merge(id, 1, Integer::sum);
            return id != KNOCKED_OUT;
        });

        String answer = run("[[offer:7]] [[offer:13]] ".repeat(8));

        assertThat(asked).containsExactlyInAnyOrderEntriesOf(Map.of(7L, 1, KNOCKED_OUT, 1));
        assertThat(answer).isEqualTo("[1](cite:offer/7) ⟨unverified:13⟩ ".repeat(8));
    }

    /**
     * Fix 5B-1: a stray {@code [[} earlier in the text — a shell test, a wiki link half-typed — is
     * not the start of the next real marker. Paired with that marker's {@code ]]}, it used to send
     * the whole span out as text and the citation with it.
     */
    @Test
    void aStrayOpeningPairDoesNotSwallowTheMarkerAfterIt() {
        assertThat(run("Test it with [[ -f x. See [[offer:7]]"))
                .isEqualTo("Test it with [[ -f x. See [1](cite:offer/7)");
        assertThat(ledger.citations()).containsExactly(new Citation(1, OFFER, 7));
    }

    private String run(String text) {
        return filter.accept(text) + filter.finish();
    }
}
