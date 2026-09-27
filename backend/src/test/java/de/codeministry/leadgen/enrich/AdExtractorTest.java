/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.enrich;

import static org.assertj.core.api.Assertions.assertThat;

import de.codeministry.leadgen.config.model.PipelineConfig.Enrichment.Extract;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * What the {@code contact} field is allowed to hold: a person's name, or nothing.
 *
 * <p>ISC-325. The configured rules read an advert's contact by position — the text in front
 * of a "Tel" label, or whatever a portal's contact element contains — and position captures
 * whatever stands there: the tail of the previous sentence, a role label with nobody behind
 * it, a phone line. Measured on the local corpus, 0 of 165 enriched offers held a person and
 * every one of them held a fragment. The extractor now keeps a captured value only when it
 * reads as a name; the rules themselves stay in YAML, because a portal's markup is
 * configuration and a name is not.
 */
class AdExtractorTest {

    /** The shipped default: the text in front of a phone label, greedy, on the collapsed page. */
    private static final String IN_FRONT_OF_A_PHONE_LABEL = "([A-Za-zÄÖÜäöüß.\\- ]+)\\s*(?:\\||,)?\\s*(?:Tel|Telefon)";

    private static final Extract BY_PATTERN =
            new Extract("patterns", Map.of("contact", new Extract.Field(null, IN_FRONT_OF_A_PHONE_LABEL, null, null)));

    private static final Extract BY_SELECTOR =
            new Extract("patterns", Map.of("contact", new Extract.Field(".contact", null, null, null)));

    private static String contact(Extract extract, String body) {
        return new AdExtractor(extract)
                .extract("<html><body><article>" + body + "</article></body></html>", "https://portal-a.example/")
                .contact();
    }

    @Nested
    class InFrontOfAPhoneLabel {

        @Test
        void keepsTheNamedPersonAndDropsTheSentenceInFront() {
            // The greedy class runs back to the last digit, so the capture starts with the
            // full stop of the sentence before and carries the role label along.
            String ad = "Laufzeit 12 Monate, Start ab 01.10.2026. Ansprechpartnerin Frau Meier | Telefon 0221 1234567";

            assertThat(contact(BY_PATTERN, ad)).isEqualTo("Frau Meier");
        }

        @Test
        void keepsAnHonorificWithTitleAndBothNames() {
            String ad = "Remote 80 %. Ihr Kontakt Herr Dr. Max Mustermann, Tel. 0221 1234567";

            assertThat(contact(BY_PATTERN, ad)).isEqualTo("Herr Dr. Max Mustermann");
        }

        @Test
        void storesNothingWhenTheTextInFrontIsASentence() {
            String ad = "Laufzeit 12 Monate. Wir freuen uns auf Ihre Bewerbung per Telefon oder Mail.";

            assertThat(contact(BY_PATTERN, ad)).isNull();
        }

        @Test
        void storesNothingForARoleLabelWithNobodyBehindIt() {
            String ad = "Start ab 01.10.2026. Ansprechpartner | Telefon 0221 1234567";

            assertThat(contact(BY_PATTERN, ad)).isNull();
        }

        @Test
        void storesNothingForACompanyOrTeamName() {
            // Capitalised words in front of the label are not a person: "Recruiting Team" is
            // where "Frau Meier" would stand, and a greeting to a team is addressed to nobody.
            String ad = "Start ab 01.10.2026. Kontakt Recruiting Team | Telefon 0221 1234567";

            assertThat(contact(BY_PATTERN, ad)).isNull();
        }
    }

    @Nested
    class FromAContactElement {

        @Test
        void keepsANameWithoutAnHonorific() {
            assertThat(contact(BY_SELECTOR, "<p class=\"contact\">Anna Meier</p>"))
                    .isEqualTo("Anna Meier");
        }

        @Test
        void dropsTheRoleLabelInFrontOfTheName() {
            assertThat(contact(BY_SELECTOR, "<p class=\"contact\">Kontakt: Anna Meier</p>"))
                    .isEqualTo("Anna Meier");
        }

        @Test
        void stopsAtThePhoneLabelOnTheSameLine() {
            assertThat(contact(BY_SELECTOR, "<p class=\"contact\">Frau Anna Meier, Tel. 0221 1234567</p>"))
                    .isEqualTo("Frau Anna Meier");
        }

        @Test
        void stopsAtTheJobTitleAfterTheName() {
            assertThat(contact(BY_SELECTOR, "<p class=\"contact\">Frau Anna Meier Senior Recruiterin</p>"))
                    .isEqualTo("Frau Anna Meier");
        }

        @Test
        void dropsTheHeadingRemnantOfAConvertedElement() {
            // A contact line that came through a Markdown conversion once arrived as "#### Name".
            assertThat(contact(BY_SELECTOR, "<div class=\"contact\">#### Anna Meier</div>"))
                    .isEqualTo("Anna Meier");
        }

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "Tel. 0221 1234567",
                    "anna.meier@portal-a.example",
                    "https://portal-a.example/kontakt",
                    "Wir freuen uns auf Ihre Bewerbung",
                    "Ansprechpartner",
                    "Kontakt:",
                    "Java Spring Boot Kubernetes Angular",
                    "Anna",
                    "   "
                })
        void storesNothingForAFragmentThatIsNotAName(String text) {
            assertThat(contact(BY_SELECTOR, "<p class=\"contact\">" + text + "</p>"))
                    .isNull();
        }

        @ParameterizedTest
        @CsvSource({
            "'Herr Müller-Lüdenscheidt', 'Herr Müller-Lüdenscheidt'",
            "'Frau Meier freut sich auf Ihre Bewerbung', 'Frau Meier'",
            "'Ihre Ansprechpartnerin: Frau Dr. Anna Meier', 'Frau Dr. Anna Meier'",
            "'Mr. John Smith', 'Mr. John Smith'",
            "'Anna van der Berg', 'Anna van der Berg'"
        })
        void readsTheNameTheAdWrites(String text, String expected) {
            assertThat(contact(BY_SELECTOR, "<p class=\"contact\">" + text + "</p>"))
                    .isEqualTo(expected);
        }
    }

    @Test
    void storesNothingWhenNoRuleIsConfigured() {
        Extract none = new Extract("patterns", Map.of());

        assertThat(contact(none, "Ansprechpartnerin Frau Meier | Telefon 0221 1234567"))
                .isNull();
    }
}
