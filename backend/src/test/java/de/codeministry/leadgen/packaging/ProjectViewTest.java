/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.packaging;

import de.codeministry.leadgen.config.model.SkillProfile;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a reference project looks like once a language has been chosen for it.
 *
 * <p>The defect this stands against was invisible for as long as there was one title field:
 * a German advert was answered with an English project title, and the suite could not see it
 * because the fixture profile is written in one language.
 */
class ProjectViewTest {

    @Test
    void anOpenEndIsTheOnlyPartOfAPeriodThatIsTranslated() {
        assertThat(ProjectView.period(YearMonth.of(2024, 1), null, "de")).isEqualTo("seit 01/2024");
        assertThat(ProjectView.period(YearMonth.of(2024, 1), null, "en")).isEqualTo("since 01/2024");
    }

    @Test
    void aClosedPeriodReadsTheSameInBothLanguages() {
        String expected = "09/2020 – 07/2023";
        assertThat(ProjectView.period(YearMonth.of(2020, 9), YearMonth.of(2023, 7), "de")).isEqualTo(expected);
        assertThat(ProjectView.period(YearMonth.of(2020, 9), YearMonth.of(2023, 7), "en")).isEqualTo(expected);
    }

    @Test
    void aPeriodWithNeitherEndIsAbsentRatherThanEmpty() {
        // The template guards with `period??`, so null is the value that removes the
        // parentheses. An empty string would render " ()".
        assertThat(ProjectView.period(null, null, "de")).isNull();
    }

    @Test
    void anEndWithoutABeginningIsStillAMonth() {
        assertThat(ProjectView.period(null, YearMonth.of(2023, 7), "de")).isEqualTo("07/2023");
    }

    @Test
    void theTitleFollowsTheLanguageOfTheLetter() {
        SkillProfile.ReferenceProject both = project("Plattform", "Platform", "Satz.", "Sentence.");
        assertThat(ProjectView.of(both, "de").title()).isEqualTo("Plattform");
        assertThat(ProjectView.of(both, "en").title()).isEqualTo("Platform");
    }

    @Test
    void aMissingTitleFallsBackToTheOtherLanguageInBothDirections() {
        // A profile written for one market only is the normal case for this tool, and an
        // empty line above a pitch is worse than the wrong language.
        assertThat(ProjectView.of(project("Plattform", null, "Satz.", null), "en").title())
                .isEqualTo("Plattform");
        assertThat(ProjectView.of(project(null, "Platform", null, "Sentence."), "de").title())
                .isEqualTo("Platform");
    }

    @Test
    void thePitchFallsBackInBothDirectionsToo() {
        // The English template used to fall back to the German pitch and the German one
        // never fell back at all, so an English-only profile wrote German letters with a
        // blank under every title.
        assertThat(ProjectView.of(project("Plattform", "Platform", null, "Sentence."), "de").pitch())
                .isEqualTo("Sentence.");
        assertThat(ProjectView.of(project("Plattform", "Platform", "Satz.", null), "en").pitch())
                .isEqualTo("Satz.");
    }

    @Test
    void aProjectWithNoTitleAtAllIsRejectedByValidationRatherThanRendered() {
        assertThat(project(null, null, "Satz.", null).isTitled()).isFalse();
        assertThat(project(" ", "", "Satz.", null).isTitled()).isFalse();
        assertThat(project(null, "Platform", null, null).isTitled()).isTrue();
    }

    private static SkillProfile.ReferenceProject project(
            String titleDe, String titleEn, String pitchDe, String pitchEn) {
        return new SkillProfile.ReferenceProject(
                "p", titleDe, titleEn, YearMonth.of(2024, 1), null, "Fullstack",
                List.of("Java"), pitchDe, pitchEn);
    }
}
