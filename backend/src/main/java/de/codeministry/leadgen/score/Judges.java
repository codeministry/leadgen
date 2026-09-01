package de.codeministry.leadgen.score;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.config.model.PipelineConfig;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Builds the judge the current configuration asks for, or none.
 *
 * <p>Per run rather than once at startup, because the configuration is hot-reloadable: a
 * key added to `.env` and a reload should start producing scores without a restart, and a
 * key removed should stop.
 *
 * <p><b>`provider` is a kind, never a default.</b> No committed file names a vendor, and
 * that includes this class: the only wire format implemented is the OpenAI-compatible chat
 * API, which is what Ollama, vLLM, LM Studio, OpenRouter and most hosted services speak.
 * A provider that speaks something else gets its own implementation the day it is
 * configured; until then it is refused loudly rather than approximated.
 */
@Slf4j
@Component
public class Judges {

    private static final String OPENAI_COMPATIBLE = "openai-compatible";

    /**
     * Its own mapper, not the web one. This reads a model's answer, which is plain JSON
     * with none of the conventions the HTTP layer is configured for, and depending on an
     * auto-configured bean would tie a scoring request to how the API happens to
     * serialise.
     */
    private final ObjectMapper json = new ObjectMapper();

    private final ConfigRegistry config;

    Judges(ConfigRegistry config) {
        this.config = config;
    }

    public Optional<Judge> current() {
        PipelineConfig.Llm llm = config.snapshot().application().llm();
        if (llm == null || blank(llm.apiKey()) || blank(llm.provider())) {
            return Optional.empty();
        }
        String model = llm.models() == null ? null : llm.models().scoring();
        if (blank(model)) {
            log.warn("llm.models.scoring is not set; nothing can be scored");
            return Optional.empty();
        }
        if (!OPENAI_COMPATIBLE.equals(llm.provider()) && !"ollama".equals(llm.provider())) {
            log.warn("llm.provider is '{}'; only the OpenAI-compatible wire format is implemented", llm.provider());
            return Optional.empty();
        }
        if (blank(llm.baseUrl())) {
            log.warn("llm.base_url is not set; there is nowhere to send a scoring request");
            return Optional.empty();
        }
        return Optional.of(new LlmJudge(llm.baseUrl(), llm.apiKey(), model, json));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
