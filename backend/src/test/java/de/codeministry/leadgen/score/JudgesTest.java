/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.score;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.config.ConfigSnapshot;
import de.codeministry.leadgen.config.model.PipelineConfig;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** Which judge a configuration asks for, and when the answer is none. */
class JudgesTest {

    private static final PipelineConfig.Llm.Models SCORING =
            new PipelineConfig.Llm.Models(null, "some-model", null, null, null);

    @Test
    void picksTheWireFormatTheProviderNames() {
        // The wire format is the provider SDK's business now, so what is left to assert is
        // the one difference that still lives here: only the Messages API has a batch
        // endpoint, so only that provider may come back as a BatchJudge. Getting this
        // backwards would mean `llm.batch` silently doing nothing, or a batch submitted to
        // an endpoint that has none.
        assertThat(judgeFor("openai-compatible", "https://gateway.invalid/v1", "key"))
                .containsInstanceOf(ChatClientJudge.class)
                .get()
                .isNotInstanceOf(BatchJudge.class);
        assertThat(judgeFor("anthropic", "https://gateway.invalid", "key")).containsInstanceOf(AnthropicJudge.class);
    }

    @Test
    void carriesTheModelTheConfigurationNamesOntoEveryScoreItWrites() {
        // `score_model` is one of the three things that make a score stale, and the value
        // written comes from here. A judge built for the wrong name would re-judge the whole
        // standing shortlist on every run and nothing would say why.
        assertThat(judgeFor("openai-compatible", "https://gateway.invalid/v1", "key"))
                .get()
                .extracting(Judge::model)
                .isEqualTo("some-model");
    }

    @Test
    void servesOllamaWithNoKeyAtAll() {
        // A local server wants no key, so there is nothing to write in `.env`. Requiring
        // one made `provider: ollama` unusable and the judge was silently never built.
        assertThat(judgeFor("ollama", "http://localhost:11434/v1", null)).containsInstanceOf(ChatClientJudge.class);
    }

    @Test
    void refusesAHostedProviderWithNoKey() {
        assertThat(judgeFor("anthropic", "https://gateway.invalid", null)).isEmpty();
    }

    @Test
    void refusesAProviderWhoseFormatIsNotImplemented() {
        // A request in the wrong shape does not fail cleanly: it is answered with a 400,
        // or parsed out of the wrong field into an offer that looks judged and is not.
        assertThat(judgeFor("cohere", "https://gateway.invalid", "key")).isEmpty();
    }

    @Test
    void refusesAProviderWithNowhereToSendTheRequest() {
        // Required even for a hosted provider whose address never changes: a URL in the
        // code is a vendor in the code, and this repository has none.
        assertThat(judgeFor("anthropic", null, "key")).isEmpty();
    }

    @Test
    void refusesWhenNoScoringModelIsNamed() {
        assertThat(judge(new PipelineConfig.Llm(
                        "anthropic",
                        "https://gateway.invalid",
                        "key",
                        false,
                        new PipelineConfig.Llm.Models(null, null, null, null, null),
                        null)))
                .isEmpty();
    }

    private java.util.Optional<Judge> judgeFor(String provider, String baseUrl, String apiKey) {
        return judge(new PipelineConfig.Llm(provider, baseUrl, apiKey, false, SCORING, null));
    }

    private java.util.Optional<Judge> judge(PipelineConfig.Llm llm) {
        var registry = Mockito.mock(ConfigRegistry.class);
        var snapshot = Mockito.mock(ConfigSnapshot.class);
        var pipeline = Mockito.mock(PipelineConfig.class);
        given(registry.snapshot()).willReturn(snapshot);
        given(snapshot.application()).willReturn(pipeline);
        given(pipeline.llm()).willReturn(llm);
        return new Judges(registry).current();
    }
}
