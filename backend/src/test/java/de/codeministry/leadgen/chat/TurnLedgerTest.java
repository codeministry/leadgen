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
import java.util.List;
import org.junit.jupiter.api.Test;

/** What one turn's tools returned, and the order its answer cited it in. */
class TurnLedgerTest {

    @Test
    void numbersEachCallFromOneInTheOrderItWasRecorded() {
        var ledger = new TurnLedger();

        ledger.record("search_offers", "Search: java", "{\"query\":\"java\"}", List.of(new Ref(OFFER, 7)), 12);
        ledger.record("list_applications", "Applications", "{}", List.of(new Ref(APPLICATION, 3)), 4);

        assertThat(ledger.calls())
                .extracting(TurnLedger.Call::ordinal, TurnLedger.Call::tool)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, "search_offers"),
                        org.assertj.core.groups.Tuple.tuple(2, "list_applications"));
        assertThat(ledger.calls().getFirst().durationMs()).isEqualTo(12);
        assertThat(ledger.calls().getFirst().arguments()).isEqualTo("{\"query\":\"java\"}");
    }

    @Test
    void holdsWhatAnyCallReturnedAndNothingElse() {
        var ledger = new TurnLedger();
        ledger.record("search_offers", "Search", "{}", List.of(new Ref(OFFER, 7), new Ref(OFFER, 9)), 1);
        ledger.record("list_applications", "Applications", "{}", List.of(new Ref(APPLICATION, 7)), 1);

        assertThat(ledger.contains(OFFER, 7)).isTrue();
        assertThat(ledger.contains(OFFER, 9)).isTrue();
        assertThat(ledger.contains(APPLICATION, 7)).isTrue();
        // Same number, other kind: an application id is not an offer id.
        assertThat(ledger.contains(APPLICATION, 9)).isFalse();
        assertThat(ledger.contains(OFFER, 8)).isFalse();
    }

    @Test
    void numbersSourcesInFirstCitationOrderAndReusesTheNumber() {
        var ledger = new TurnLedger();
        ledger.record("search_offers", "Search", "{}", List.of(new Ref(OFFER, 7), new Ref(OFFER, 9)), 1);

        assertThat(ledger.cite(OFFER, 9)).isEqualTo(1);
        assertThat(ledger.cite(OFFER, 7)).isEqualTo(2);
        assertThat(ledger.cite(OFFER, 9)).isEqualTo(1);

        assertThat(ledger.citations()).containsExactly(new Citation(1, OFFER, 9), new Citation(2, OFFER, 7));
    }

    @Test
    void aTurnThatCitedNothingHasNoSources() {
        var ledger = new TurnLedger();
        ledger.record("search_offers", "Search", "{}", List.of(new Ref(OFFER, 7)), 1);

        assertThat(ledger.citations()).isEmpty();
    }
}
