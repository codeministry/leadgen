/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.packaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import de.codeministry.leadgen.config.model.CoverLetterStyle;
import de.codeministry.leadgen.config.model.SkillProfile;
import de.codeministry.leadgen.llm.ChatModels;
import de.codeministry.leadgen.llm.LlmBudget;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * ISC-251: the salutation names the contact person only when the advert's own text names that
 * person with an honorific, and is the neutral salutation of the letter's language otherwise —
 * decided here, not by the model, and never from the offer's {@code contact} column.
 *
 * <p>Plus the edges of ISC-250 that need no database: one budget call per draft, none without
 * a model or without the language's letter lines, and nothing drafted from an answer that
 * cannot be read.
 */
class CoverLetterWriterTest {

    private static final String AD_NAMING_NOBODY =
            "Senior Java Entwickler (m/w/d)\n\nWir suchen Erfahrung mit Spring Boot.";
    private static final String AD_NAMING_SCHMIDT =
            AD_NAMING_NOBODY + "\n\nFragen beantwortet Ihnen gerne Frau Schmidt aus unserem Recruiting.";

    private static final String NEUTRAL = "Sehr geehrte Damen und Herren,";

    private static final String DRAFT_NAMING_SCHMIDT = draftGreeting("Sehr geehrte Frau Schmidt,");

    private final ChatModels chatModels = mock(ChatModels.class);
    private final LlmBudget budget = mock(LlmBudget.class);
    private final CoverLetterWriter writer = new CoverLetterWriter(chatModels, budget);

    @BeforeEach
    void aBudgetWithCallsLeft() {
        given(budget.take()).willReturn(true);
    }

    @Test
    void keepsASalutationThatNamesThePersonTheAdNames() {
        model(StubChatModel.answering(DRAFT_NAMING_SCHMIDT));

        var draft = writer.draft(null, request("de", AD_NAMING_SCHMIDT)).orElseThrow();

        assertThat(draft.salutation()).isEqualTo("Sehr geehrte Frau Schmidt,");
        assertThat(draft.body()).startsWith("Ihre Anzeige sucht");
        assertThat(draft.skills()).containsExactly("Spring Boot");
        assertThat(draft.projects()).containsExactly("Beispielprojekt");
    }

    @Test
    void usesTheNeutralSalutationWhenTheAdNamesNobody() {
        // The model greeted somebody anyway. A letter to a person who does not exist is the
        // mistake a reader notices in the first line.
        model(StubChatModel.answering(DRAFT_NAMING_SCHMIDT));

        assertThat(writer.draft(null, request("de", AD_NAMING_NOBODY))
                        .orElseThrow()
                        .salutation())
                .isEqualTo(NEUTRAL);
        assertThat(writer.draft(null, request("de", AD_NAMING_NOBODY))
                        .orElseThrow()
                        .salutation())
                .isEqualTo(NEUTRAL);
    }

    @Test
    void replacesAGreetingToANameTheAdDoesNotContain() {
        // The ad names Frau Schmidt; the model greets Herr Meier. Neither kept nor mixed.
        model(StubChatModel.answering(draftGreeting("Sehr geehrter Herr Meier,")));

        assertThat(writer.draft(null, request("de", AD_NAMING_SCHMIDT))
                        .orElseThrow()
                        .salutation())
                .isEqualTo("Guten Tag Frau Schmidt,");
        assertThat(writer.draft(null, request("de", AD_NAMING_NOBODY))
                        .orElseThrow()
                        .salutation())
                .isEqualTo(NEUTRAL);
    }

    @Test
    void neverGreetsAnAdFragmentACompanyOrATechnology() {
        // What the old contact column fed the greeting: fragments of the ad, a technology, a
        // company name. The ads below mention each of them and name no person.
        String ad = AD_NAMING_NOBODY
                + "\n\nCD Ers Team. Schwerpunkt Artificial Intelligence."
                + "\nFerchau Engineering GmbH, Herr der Daten: das Team.";
        for (String greeting : List.of(
                "Sehr geehrte Damen und Herren von CD Ers,",
                "Hallo CD Ers,",
                "Sehr geehrter Herr Artificial In,",
                "Sehr geehrte Frau Ferchau Engineering,",
                "Sehr geehrtes Ferchau Engineering Team,")) {
            model(StubChatModel.answering(draftGreeting(greeting)));

            assertThat(writer.draft(null, request("de", ad)).orElseThrow().salutation())
                    .as(greeting)
                    .isEqualTo(NEUTRAL);
        }
    }

    @Test
    void namesThePersonTheAdNamesWhenTheModelLeftThemOut() {
        model(StubChatModel.answering(draftGreeting(NEUTRAL)));

        String ad = AD_NAMING_NOBODY + "\n\nIhr Ansprechpartner: Frau Anna Stader\nTelefon 0221 000000";
        assertThat(writer.draft(null, request("de", ad)).orElseThrow().salutation())
                .isEqualTo("Guten Tag Frau Stader,")
                .contains("Frau Stader");

        String english = "Senior Java Developer\n\nYour contact: Mr. John Miller";
        assertThat(writer.draft(null, request("en", english)).orElseThrow().salutation())
                .isEqualTo("Dear Mr. Miller,");
    }

    @Test
    void replacesAGreetingThatHidesAParagraph() {
        // One line, but a sentence past the greeting: the rest of the letter would start with
        // text nothing checked as a greeting.
        String[] greetings = {
            "Sehr geehrte Frau Schmidt, ich habe Ihre Anzeige gelesen und bringe genau das mit.",
            "Sehr geehrte Frau Schmidt,\n\nich habe Ihre Anzeige gelesen.",
            "Sehr geehrte Frau Schmidt: Spring Boot ist mein Alltag,",
        };
        for (String greeting : greetings) {
            model(StubChatModel.answering(draftGreeting(greeting)));

            assertThat(writer.draft(null, request("de", AD_NAMING_SCHMIDT))
                            .orElseThrow()
                            .salutation())
                    .as(greeting)
                    .isEqualTo("Guten Tag Frau Schmidt,");
        }
    }

    @Test
    void keepsAGreetingByTheFullNameOrWithTheAdsDoubleHonorific() {
        String ad = AD_NAMING_NOBODY + "\n\nKontakt: Frau Dr. Anna Stader";
        model(StubChatModel.answering(draftGreeting("Sehr geehrte Frau Dr. Stader,")));
        assertThat(writer.draft(null, request("de", ad)).orElseThrow().salutation())
                .isEqualTo("Sehr geehrte Frau Dr. Stader,");

        model(StubChatModel.answering(draftGreeting("Sehr geehrte Frau Dr. Anna Stader,")));
        assertThat(writer.draft(null, request("de", ad)).orElseThrow().salutation())
                .isEqualTo("Sehr geehrte Frau Dr. Anna Stader,");
    }

    @Test
    void aLabelOrAnAbbreviationAfterTheNameIsNotTheSurname() {
        assertThat(CoverLetterWriter.personsIn("Frau Anna Stader Tel. 0221 000000"))
                .extracting(CoverLetterWriter.Person::greeted)
                .containsExactly("Frau Stader");
        assertThat(CoverLetterWriter.personsIn("Frau Stader Personalberaterin"))
                .extracting(CoverLetterWriter.Person::greeted)
                .containsExactly("Frau Stader");
        // A sentence ending on the surname keeps it.
        assertThat(CoverLetterWriter.personsIn("Ihr Kontakt ist Frau Anna Stader."))
                .extracting(CoverLetterWriter.Person::greeted)
                .containsExactly("Frau Stader");
        assertThat(CoverLetterWriter.personsIn("Fragen beantwortet Ihnen Frau Sabine Ott."))
                .extracting(CoverLetterWriter.Person::greeted)
                .containsExactly("Frau Ott");
        // A job title on the same line cannot be told from a middle name, so nobody is named.
        assertThat(CoverLetterWriter.personsIn("Frau Anna Stader Senior Consultant"))
                .isEmpty();
    }

    @Test
    void aDoctorateAloneIsACompanyAndHerrnIsHerr() {
        assertThat(CoverLetterWriter.personsIn("Für unseren Kunden Dr. Oetker suchen wir"))
                .isEmpty();
        assertThat(CoverLetterWriter.personsIn("Kunde: Dr. Ing. h.c. F. Porsche AG"))
                .isEmpty();
        assertThat(CoverLetterWriter.personsIn("Frau Dr. Meier"))
                .extracting(CoverLetterWriter.Person::greeted)
                .containsExactly("Frau Dr. Meier");
        assertThat(CoverLetterWriter.personsIn("Bei Fragen wenden Sie sich an Herrn Müller."))
                .extracting(CoverLetterWriter.Person::greeted)
                .containsExactly("Herr Müller");
    }

    @Test
    void readsPersonsOnlyWhereTheAdPutsAnHonorificInFrontOfThem() {
        assertThat(CoverLetterWriter.personsIn("Ihr Ansprechpartner: Frau Anna Stader"))
                .extracting(CoverLetterWriter.Person::greeted)
                .containsExactly("Frau Stader");
        assertThat(CoverLetterWriter.personsIn("Kontakt: Herr Dr. Müller-Lüdenscheidt"))
                .extracting(CoverLetterWriter.Person::greeted)
                .containsExactly("Herr Dr. Müller-Lüdenscheidt");
        // Never across a line break: the signature's next line is not part of the name.
        assertThat(CoverLetterWriter.personsIn("Frau Stader\nPersonalberatung"))
                .extracting(CoverLetterWriter.Person::names)
                .containsExactly("Stader");
        assertThat(CoverLetterWriter.personsIn("Anna Schmidt, Ferchau Engineering, Artificial Intelligence"))
                .isEmpty();
        assertThat(CoverLetterWriter.personsIn("die frau schmidt")).isEmpty();
        assertThat(CoverLetterWriter.personsIn(null)).isEmpty();
    }

    @Test
    void handsTheModelTheAdsPersonTheSkillsTheProjectsAndTheExamplesAsToneOnly() {
        StubChatModel model = StubChatModel.answering(DRAFT_NAMING_SCHMIDT);
        model(model);

        writer.draft(null, request("de", AD_NAMING_SCHMIDT));

        assertThat(model.lastPrompt())
                .contains("Letter language: de")
                .contains("Neutral salutation: " + NEUTRAL)
                .contains("Contact person named in the advert: Frau Schmidt")
                .contains("Acme Consulting GmbH")
                .contains("Spring Boot")
                .contains("PostgreSQL")
                .contains("Beispielprojekt")
                .contains("Ein Satz über das Projekt.")
                .contains("01.10.2026")
                .contains("leidenschaftlich")
                .contains("220")
                .contains("Open with the requirement")
                .contains("TONE ONLY")
                .contains("Ein Beispielbrief.");
    }

    @Test
    void tellsTheModelToUseTheNeutralSalutationWhenTheAdNamesNobody() {
        StubChatModel model = StubChatModel.answering(DRAFT_NAMING_SCHMIDT);
        model(model);

        writer.draft(null, request("de", AD_NAMING_NOBODY));

        assertThat(model.lastPrompt())
                .contains("Letter language: de")
                .contains("Neutral salutation: " + NEUTRAL)
                .contains("Contact person named in the advert: none — use the neutral salutation");
    }

    @Test
    void neitherSpendsTheBudgetNorDraftsWithoutTheLanguagesLetterLines() {
        StubChatModel model = StubChatModel.answering(DRAFT_NAMING_SCHMIDT);
        model(model);
        var unconstrained = new CoverLetterWriter.Request(
                7L,
                "fr",
                AD_NAMING_NOBODY,
                null,
                profile(),
                List.of(),
                null,
                CoverLetterStyle.Rules.UNCONSTRAINED,
                List.of());

        assertThat(writer.draft(null, unconstrained)).isEmpty();
        verify(budget, never()).take();
        assertThat(model.calls()).isZero();
    }

    @Test
    void takesExactlyOneCallFromTheBudgetPerDraft() {
        model(StubChatModel.answering(DRAFT_NAMING_SCHMIDT));

        writer.draft(null, request("de", AD_NAMING_SCHMIDT));

        verify(budget, times(1)).take();
    }

    @Test
    void neitherSpendsTheBudgetNorDraftsWithoutAWritingModel() {
        given(chatModels.writing(any())).willReturn(Optional.empty());

        assertThat(writer.draft(null, request("de", AD_NAMING_NOBODY))).isEmpty();
        verify(budget, never()).take();
    }

    @Test
    void doesNotCallTheModelWhenTheBudgetIsSpent() {
        StubChatModel model = StubChatModel.answering(DRAFT_NAMING_SCHMIDT);
        model(model);
        given(budget.take()).willReturn(false);

        assertThat(writer.draft(null, request("de", AD_NAMING_NOBODY))).isEmpty();
        assertThat(model.calls()).isZero();
    }

    @Test
    void draftsNothingFromAFailedOrUnreadableAnswer() {
        model(StubChatModel.throwing());
        assertThat(writer.draft(null, request("de", AD_NAMING_NOBODY))).isEmpty();

        model(StubChatModel.answering("Here is your letter: Dear team, ..."));
        assertThat(writer.draft(null, request("de", AD_NAMING_NOBODY))).isEmpty();

        model(StubChatModel.answering("{\"salutation\": \"Hallo,\", \"body\": \"  \"}"));
        assertThat(writer.draft(null, request("de", AD_NAMING_NOBODY))).isEmpty();
    }

    private void model(StubChatModel model) {
        given(chatModels.writing(any())).willReturn(Optional.of(model));
    }

    private static String draftGreeting(String salutation) {
        return """
                {"salutation": %s,
                 "body": "Ihre Anzeige sucht Erfahrung mit Spring Boot. Genau daran arbeite ich seit Jahren.",
                 "skills": ["Spring Boot"],
                 "projects": ["Beispielprojekt"]}
                """.formatted(quoted(salutation));
    }

    private static String quoted(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private static CoverLetterWriter.Request request(String language, String advert) {
        return new CoverLetterWriter.Request(
                7L,
                language,
                advert,
                "Acme Consulting GmbH",
                profile(),
                List.of(new ProjectView("Beispielprojekt", "04/2023 – 11/2024", "Ein Satz über das Projekt.")),
                "01.10.2026",
                "en".equals(language) ? ENGLISH : GERMAN,
                List.of("Ein Beispielbrief."));
    }

    private static final CoverLetterStyle.Rules GERMAN = new CoverLetterStyle.Rules(
            List.of("leidenschaftlich"),
            220,
            List.of("Open with the requirement you match best."),
            NEUTRAL,
            "Guten Tag {name},",
            "Mit freundlichen Grüßen");

    private static final CoverLetterStyle.Rules ENGLISH = new CoverLetterStyle.Rules(
            List.of("passionate"),
            220,
            List.of("Open with the requirement you match best."),
            "Dear Sir or Madam,",
            "Dear {name},",
            "Kind regards");

    private static SkillProfile profile() {
        return new SkillProfile(
                1,
                "de",
                new SkillProfile.Identity("Jane Doe", null, null, null, null, List.of(), "senior"),
                List.of(new SkillProfile.Skill("Spring Boot", 10, null, List.of("Springboot"))),
                List.of(new SkillProfile.Skill("PostgreSQL", 7, null, List.of("Postgres"))),
                List.of(new SkillProfile.Skill("Groovy", 2, null, null)),
                List.of(),
                List.of(),
                List.of(),
                null,
                List.of(),
                List.of());
    }
}
