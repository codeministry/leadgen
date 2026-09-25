/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.llm;

import static org.assertj.core.api.Assertions.assertThat;

import de.codeministry.leadgen.config.model.PipelineConfig.Llm.Models;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Which model the content classifier and the field extractor ask, and which key decided it.
 *
 * <p>Each has a key of its own now, read the way {@code extraction} is: the key when it names
 * a model, the first scoring choice when it is empty — so one line to fill in is still enough —
 * and nothing on a fresh clone.
 */
class ModelChoiceTest {

    static Stream<Arguments> stages() {
        Function<Models, Optional<String>> content = ModelChoice::content;
        Function<Models, Optional<String>> fields = ModelChoice::fields;
        Function<String, Models> contentSetTo = ModelChoiceTest::contentSetTo;
        Function<String, Models> fieldsSetTo = ModelChoiceTest::fieldsSetTo;
        return Stream.of(
                Arguments.of("llm.models.content", content, contentSetTo),
                Arguments.of("llm.models.fields", fields, fieldsSetTo));
    }

    private static Models contentSetTo(String model) {
        return new Models(null, "a-judge", null, null, "another-judge", model, null);
    }

    private static Models fieldsSetTo(String model) {
        return new Models(null, "a-judge", null, null, "another-judge", null, model);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stages")
    void readsTheKeyThatNamesThisStage(
            String key, Function<Models, Optional<String>> choose, Function<String, Models> models) {
        assertThat(choose.apply(models.apply("a-small-model"))).contains("a-small-model");
        assertThat(choose.apply(models.apply(" a-small-model "))).contains("a-small-model");
    }

    @org.junit.jupiter.api.Test
    void extractionTrimsLikeTheOtherTwo() {
        // A padded raw value is never what a stage asks or what a `*_model` column stores.
        assertThat(ModelChoice.extraction(new Models(" a-reader ", "a-judge", null, null, null, null, null)))
                .contains("a-reader");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stages")
    void fallsBackToTheFirstScoringChoiceWhenTheKeyIsEmpty(
            String key, Function<Models, Optional<String>> choose, Function<String, Models> models) {
        // The configured default and not an alternative from `scoring_options`: those exist so
        // two judges can be compared, and a label or a start date is nothing to compare about.
        assertThat(choose.apply(models.apply(null))).contains("a-judge");
        assertThat(choose.apply(models.apply("   "))).contains("a-judge");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stages")
    void namesNothingOnAFreshClone(
            String key, Function<Models, Optional<String>> choose, Function<String, Models> models) {
        // No model at all is a working state: the rules still label, the patterns still fill in.
        assertThat(choose.apply(new Models(null, null, null, null, null, null, null)))
                .isEmpty();
        assertThat(choose.apply(null)).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stages")
    void namesTheKeyThatDecided(
            String key, Function<Models, Optional<String>> choose, Function<String, Models> models) {
        // What the startup log prints beside the model: the stage's own key when it is set,
        // `llm.models.scoring` when the fallback answered.
        assertThat(ModelChoice.decidedBy(key, "a-small-model")).isEqualTo(key);
        assertThat(ModelChoice.decidedBy(key, "  ")).isEqualTo("llm.models.scoring");
        assertThat(ModelChoice.decidedBy(key, null)).isEqualTo("llm.models.scoring");
    }
}
