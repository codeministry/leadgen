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
            new PipelineConfig.Llm.Models(null, "some-model", null, null);

    @Test
    void picksTheWireFormatTheProviderNames() {
        assertThat(judgeFor("openai-compatible", "https://gateway.invalid/v1", "key"))
                .containsInstanceOf(OpenAiCompatibleJudge.class);
        assertThat(judgeFor("anthropic", "https://gateway.invalid", "key"))
                .containsInstanceOf(AnthropicJudge.class);
    }

    @Test
    void servesOllamaWithNoKeyAtAll() {
        // A local server wants no key, so there is nothing to write in `.env`. Requiring
        // one made `provider: ollama` unusable and the judge was silently never built.
        assertThat(judgeFor("ollama", "http://localhost:11434/v1", null))
                .containsInstanceOf(OpenAiCompatibleJudge.class);
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
                        new PipelineConfig.Llm.Models(null, null, null, null),
                        null)))
                .isEmpty();
    }

    private java.util.Optional<Judge> judgeFor(String provider, String baseUrl, String apiKey) {
        return judge(new PipelineConfig.Llm(provider, baseUrl, apiKey, SCORING, null));
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
