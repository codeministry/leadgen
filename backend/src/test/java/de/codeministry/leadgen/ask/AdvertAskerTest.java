/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.ask;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.codeministry.leadgen.offer.BadShortlistRequest;
import org.junit.jupiter.api.Test;

/**
 * What survives the check, and what does not.
 *
 * <p>No container and no endpoint: the model's answer is a string, and every case here is about
 * what this does with one. The case that matters is the invented quote — a fluent, specific
 * sentence about the reader's advert that is not in it.
 */
class AdvertAskerTest {

    private static final String ADVERT = """
            Lead Java Backend Entwickler (m/w/d)

            Für ein langfristig angelegtes Projekt suchen wir einen erfahrenen Entwickler.
            Der Einsatz erfolgt zu 100 % remote, eine Anwesenheit vor Ort ist nicht vorgesehen.
            Die Vergütung liegt bei 95 EUR pro Stunde.
            """;

    private final AdvertAsker asker = new AdvertAsker(null, "test-model", new ObjectMapper());

    @Test
    void keepsAClaimTheAdvertActuallyCarries() {
        var answer = asker.parse("""
                {"stated": true, "answer": "95 EUR pro Stunde.", "quote": "Die Vergütung liegt bei 95 EUR pro Stunde."}
                """, ADVERT, AdvertQuestion.RATE);

        assertThat(answer.stated()).isTrue();
        assertThat(answer.answer()).isEqualTo("95 EUR pro Stunde.");
        assertThat(answer.quote()).contains("95 EUR");
        assertThat(answer.model()).isEqualTo("test-model");
    }

    @Test
    void dropsAClaimWhoseQuoteIsNotInTheAdvert() {
        // The whole reason the quote is required. This answer is fluent, specific, plausible
        // and about a rate the advert never names — and without the check it would be
        // indistinguishable on screen from the case above.
        var answer = asker.parse("""
                {"stated": true, "answer": "Der Satz liegt bei 110 EUR.",
                 "quote": "Wir zahlen 110 EUR pro Stunde bei voller Remote-Arbeit."}
                """, ADVERT, AdvertQuestion.RATE);

        assertThat(answer.stated()).isFalse();
        assertThat(answer.answer()).isNull();
        assertThat(answer.quote()).isNull();
    }

    @Test
    void acceptsAQuoteThatDiffersOnlyInPunctuationAndSpacing() {
        // Folding both sides is what keeps the check from rejecting a faithful quote: a model
        // that normalises a dash, a quotation mark or collapsed whitespace has not invented
        // anything, and a check that fails those would be a check nobody could satisfy.
        var answer = asker.parse("""
                {"stated": true, "answer": "Voll remote.",
                 "quote": "der einsatz erfolgt zu 100 %  remote"}
                """, ADVERT, AdvertQuestion.ONSITE);

        assertThat(answer.stated()).isTrue();
    }

    @Test
    void refusesAQuoteTooShortToMeanAnything() {
        // "remote" is in most adverts, so a two-word quote would let almost anything through.
        assertThat(AdvertAsker.contains(ADVERT, "remote")).isFalse();
        assertThat(AdvertAsker.contains(ADVERT, "100 % remote")).isTrue();
    }

    @Test
    void readsSilenceAsAnAnswerAndNotAsAFailure() {
        var answer = asker.parse("{\"stated\": false}", ADVERT, AdvertQuestion.EXTENSION);

        assertThat(answer.stated()).isFalse();
        assertThat(answer.question()).isEqualTo("extension");
        assertThat(answer.model()).isEqualTo("test-model");
    }

    @Test
    void dropsAClaimThatCameWithoutAQuoteAtAll() {
        var answer = asker.parse(
                "{\"stated\": true, \"answer\": \"Der Satz liegt bei 95 EUR.\"}", ADVERT, AdvertQuestion.RATE);

        assertThat(answer.stated()).isFalse();
    }

    @Test
    void survivesAnAnswerThatIsNotJsonAtAll() {
        // The shape every other reader of a model answer already tolerates: a model that
        // explains itself in prose gets read as silence rather than as an exception.
        assertThat(asker.parse("I am afraid I cannot help with that.", ADVERT, AdvertQuestion.CLIENT)
                        .stated())
                .isFalse();
    }

    @Test
    void readsTheAnswerOutOfProseAroundIt() {
        // `Answers.objectIn` takes the first brace to the last, because a model that wraps its
        // JSON in a sentence has still answered.
        var answer = asker.parse("""
                Sure! Here is the answer:
                {"stated": true, "answer": "95 EUR.", "quote": "Die Vergütung liegt bei 95 EUR pro Stunde."}
                Hope that helps.
                """, ADVERT, AdvertQuestion.RATE);

        assertThat(answer.stated()).isTrue();
    }

    @Test
    void putsTheQuestionBeforeTheAdvertSoTheInstructionIsReadAgainstIt() {
        String described = AdvertAsker.describe(ADVERT, AdvertQuestion.ONBOARDING);

        assertThat(described).startsWith("Question: ");
        assertThat(described.indexOf("Advert:")).isGreaterThan(described.indexOf("Question:"));
    }

    @Test
    void refusesAQuestionNobodyDefinedRatherThanAnsweringADifferentOne() {
        // A question the server silently replaced would answer about something the reader did
        // not ask, and the answer would look exactly as authoritative.
        assertThatThrownBy(() -> AdvertQuestion.of("salary"))
                .isInstanceOf(BadShortlistRequest.class)
                .hasMessageContaining("rate");
        assertThat(AdvertQuestion.of("RATE")).isEqualTo(AdvertQuestion.RATE);
    }
}
