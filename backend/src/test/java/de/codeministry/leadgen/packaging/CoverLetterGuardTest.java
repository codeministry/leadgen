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

import de.codeministry.leadgen.config.model.SkillProfile;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What a drafted letter may claim, decided on literal drafts with no model and no Spring context.
 *
 * <p>Every case builds the draft by hand, so what is under test is the guard's rule rather than
 * whatever a model happened to write that day.
 */
class CoverLetterGuardTest {

    private static final SkillProfile.ReferenceProject BANK =
            project("bank", "Kernbanken-Migration", "Core banking migration");
    private static final SkillProfile.ReferenceProject SHOP = project("shop", "Shop-Relaunch", "Shop relaunch");
    private static final SkillProfile.ReferenceProject FLEET = project("fleet", "Flottenportal", "Fleet portal");

    /** One skill per tier, so "any tier" is something the test proves rather than assumes. */
    private static final SkillProfile PROFILE = new SkillProfile(
            1,
            "de",
            null,
            List.of(new SkillProfile.Skill("Spring Boot", 10, 2015, List.of("Springboot"))),
            List.of(new SkillProfile.Skill("Angular", 8, 2017, List.of())),
            List.of(new SkillProfile.Skill("Kubernetes", 5, 2020, List.of("k8s"))),
            null,
            List.of(BANK, SHOP, FLEET),
            null,
            null,
            null,
            null);

    /** The ranking chose the bank and the shop; the fleet portal is the project it left out. */
    private static final List<ProjectView> CHOSEN = List.of(ProjectView.of(BANK, "de"), ProjectView.of(SHOP, "de"));

    private static final String AD = AdText.of(
            "Java-Entwickler (m/w/d)",
            "Wir suchen Verstärkung für unser Team.",
            null,
            "Erfahrung mit Springboot und Angular ist Pflicht, Docker ein Plus.");

    private static final CoverLetterGuard GUARD = new CoverLetterGuard(List.of("mit großer Begeisterung"), 60);

    // ISC-252 — skills

    @Test
    void acceptsADraftNamingOnlyProfileSkillsTheAdNames() {
        // "Spring Boot" is the profile's name and the ad writes the alias; "Angular" is a strong
        // skill, not a core one. Both are what the ad asked for, so both may be named.
        var verdict = GUARD.check(
                draft(
                        "Wir haben Spring Boot und Angular eingesetzt.",
                        List.of("Spring Boot", "Angular"),
                        List.of("Kernbanken-Migration")),
                PROFILE,
                AD,
                CHOSEN);

        assertThat(verdict.accepted()).as(verdict.reason()).isTrue();
    }

    @Test
    void rejectsASkillTheProfileDoesNotList() {
        var verdict =
                GUARD.check(draft("Docker setze ich täglich ein.", List.of("Docker"), List.of()), PROFILE, AD, CHOSEN);

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.reason()).contains("Docker").contains("profile");
    }

    @Test
    void rejectsAProfileSkillTheAdDoesNotName() {
        // Kubernetes is in the profile — a peripheral one, under an alias — but the ad never
        // asks for it, so the letter may not claim it as an answer to the ad.
        var verdict =
                GUARD.check(draft("Betrieb auf k8s gehört dazu.", List.of("k8s"), List.of()), PROFILE, AD, CHOSEN);

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.reason()).contains("k8s").contains("ad");
    }

    @Test
    void rejectsAProfileSkillInTheBodyTheAdDoesNotName() {
        // The declared list is what the model says it named; the body is what it actually
        // wrote. A profile spelling in the body is decidable, so it is checked too.
        var verdict = GUARD.check(
                draft("Spring Boot und Kubernetes sind mein Alltag.", List.of("Spring Boot"), List.of()),
                PROFILE,
                AD,
                CHOSEN);

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.reason()).contains("Kubernetes");
    }

    @Test
    void acceptsAProfileSkillFromTheStackOfAChosenProjectTheAdDoesNotName() {
        // The ranking chose this project for what it was built with, so its stack is a true
        // and relevant claim even where the ad is silent on it.
        var platform = new SkillProfile.ReferenceProject(
                "platform",
                "Plattformbetrieb",
                "Platform operations",
                null,
                null,
                null,
                List.of("Kubernetes"),
                null,
                null);
        var profile = new SkillProfile(
                1,
                "de",
                null,
                PROFILE.core(),
                PROFILE.strong(),
                PROFILE.peripheral(),
                null,
                List.of(platform),
                null,
                null,
                null,
                null);

        var verdict = GUARD.check(
                draft(
                        "Den Plattformbetrieb auf Kubernetes habe ich verantwortet.",
                        List.of("Kubernetes"),
                        List.of("Plattformbetrieb")),
                profile,
                AD,
                List.of(ProjectView.of(platform, "de")));

        assertThat(verdict.accepted()).as(verdict.reason()).isTrue();
    }

    // ISC-253 — projects

    @Test
    void rejectsAProjectTheRankingDidNotChoose() {
        var verdict = GUARD.check(
                draft("Zuletzt habe ich Angular eingesetzt.", List.of("Angular"), List.of("Flottenportal")),
                PROFILE,
                AD,
                CHOSEN);

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.reason()).contains("Flottenportal");
    }

    @Test
    void rejectsAnUnchosenProjectNamedOnlyInTheBody() {
        var verdict = GUARD.check(
                draft("Im Projekt Fleet portal lag der Fokus auf Angular.", List.of("Angular"), List.of()),
                PROFILE,
                AD,
                CHOSEN);

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.reason()).contains("Fleet portal");
    }

    @Test
    void rejectsAChosenProjectUnderTheOtherLanguagesTitle() {
        // The letter is German, so the bank project is "Kernbanken-Migration" in it; the English
        // title is not what the ranking handed the letter.
        var verdict = GUARD.check(
                draft("Angular kenne ich gut.", List.of("Angular"), List.of("Core banking migration")),
                PROFILE,
                AD,
                CHOSEN);

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.reason()).contains("Core banking migration");
    }

    // ISC-254 — style

    @Test
    void rejectsABannedPhraseWhateverItsCase() {
        var verdict = GUARD.check(
                draft("MIT GROSSER BEGEISTERUNG habe ich Ihre Anzeige gelesen.", List.of(), List.of()),
                PROFILE,
                AD,
                CHOSEN);

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.reason()).contains("mit großer Begeisterung");
    }

    @Test
    void rejectsABannedPhraseInTheSalutation() {
        var draft = new CoverLetterGuard.Draft(
                "Mit großer Begeisterung, Frau Muster,", "Kurz und sachlich.", List.of(), List.of());

        assertThat(GUARD.check(draft, PROFILE, AD, CHOSEN).accepted()).isFalse();
    }

    @Test
    void rejectsABodyOverTheWordLimit() {
        String body = "Wort ".repeat(61).strip();

        var verdict = GUARD.check(draft(body, List.of(), List.of()), PROFILE, AD, CHOSEN);

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.reason()).contains("61").contains("60");
    }

    @Test
    void aBannedStemCatchesItsInflectionsButNotTheInsideOfAnotherWord() {
        var guard = new CoverLetterGuard(List.of("leidenschaftlich"), 60);

        assertThat(guard.check(
                                draft("Ich arbeite leidenschaftlicher als die meisten.", List.of(), List.of()),
                                PROFILE,
                                AD,
                                CHOSEN)
                        .accepted())
                .isFalse();
        assertThat(guard.check(draft("Unleidenschaftlich bleibt niemand.", List.of(), List.of()), PROFILE, AD, CHOSEN)
                        .accepted())
                .isTrue();
    }

    @Test
    void countsWordsJoinedByNonBreakingSpaces() {
        String body = "Wort\u00A0".repeat(61).strip();

        assertThat(GUARD.check(draft(body, List.of(), List.of()), PROFILE, AD, CHOSEN)
                        .accepted())
                .isFalse();
    }

    @Test
    void aNullSkillIsDroppedRatherThanThrown() {
        var skills = new java.util.ArrayList<String>();
        skills.add(null);

        assertThat(new CoverLetterGuard.Draft("Guten Tag,", "Kurz.", skills, null).skills())
                .isEmpty();
    }

    @Test
    void acceptsABodyExactlyAtTheWordLimit() {
        String body = "Wort ".repeat(60).strip();

        assertThat(GUARD.check(draft(body, List.of(), List.of()), PROFILE, AD, CHOSEN)
                        .accepted())
                .isTrue();
    }

    private static CoverLetterGuard.Draft draft(String body, List<String> skills, List<String> projects) {
        return new CoverLetterGuard.Draft("Guten Tag,", body, skills, projects);
    }

    private static SkillProfile.ReferenceProject project(String id, String titleDe, String titleEn) {
        return new SkillProfile.ReferenceProject(id, titleDe, titleEn, null, null, null, List.of(), null, null);
    }
}
