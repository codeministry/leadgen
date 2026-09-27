/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import de.codeministry.leadgen.config.ConfigProperties;
import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.config.ConfigSnapshot;
import de.codeministry.leadgen.config.DotEnv;
import de.codeministry.leadgen.config.model.PipelineConfig;
import de.codeministry.leadgen.config.model.PipelineConfig.Llm.Models;
import de.codeministry.leadgen.llm.ModelChoice;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.core.env.StandardEnvironment;

/**
 * ISC-421: the chat's model key falls back to the judge's like the other bounded keys, the
 * startup log says which one won, and with neither key set there is no chat model at all.
 *
 * <p>In the root package because the banner's {@code describe} is package-private there.
 */
class ChatModelChoiceTest {

    @Test
    void anEmptyChatKeyReadsTheJudge() {
        Models models = models("a-judge", "");

        assertThat(ModelChoice.chat(models)).contains("a-judge");
        assertThat(row(models)).contains("a-judge", "(from llm.models.scoring)");
    }

    @Test
    void aChatKeyOfItsOwnWins() {
        Models models = models("a-judge", " a-tool-caller ");

        assertThat(ModelChoice.chat(models)).contains("a-tool-caller");
        assertThat(row(models)).contains("a-tool-caller", "(from llm.models.chat)");
    }

    @Test
    void neitherKeyMeansNoChatModel() {
        Models models = models(null, null);

        assertThat(ModelChoice.chat(models)).isEmpty();
        assertThat(row(models)).contains("none");
    }

    private static Models models(String scoring, String chat) {
        return new Models(null, scoring, null, null, null, null, null, chat);
    }

    private static String row(Models models) {
        var registry = Mockito.mock(ConfigRegistry.class);
        var snapshot = Mockito.mock(ConfigSnapshot.class);
        var pipeline = Mockito.mock(PipelineConfig.class);
        given(registry.snapshot()).willReturn(snapshot);
        given(snapshot.application()).willReturn(pipeline);
        given(pipeline.llm()).willReturn(new PipelineConfig.Llm(null, null, null, null, false, models, null));
        String text = new ConfigurationBanner(new StandardEnvironment(), new ConfigProperties("config"), registry)
                .describe(new DotEnv(Optional.empty(), Map.of()));
        return text.lines()
                .filter(line -> line.contains("llm.models.chat → used"))
                .findFirst()
                .orElseThrow();
    }
}
