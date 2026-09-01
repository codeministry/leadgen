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
 * <p><b>`provider` is a kind, never a default.</b> It names a wire format and nothing
 * else: two are implemented, and the base URL still decides who answers. No committed file
 * names a vendor's address, and neither does this class — a provider it does not know is
 * refused loudly rather than approximated, because a request in the wrong shape does not
 * fail cleanly. It is answered with a 400, or worse, parsed out of the wrong field into an
 * offer that looks judged and is not.
 */
@Slf4j
@Component
public class Judges {

    private static final String OPENAI_COMPATIBLE = "openai-compatible";
    private static final String OLLAMA = "ollama";
    private static final String ANTHROPIC = "anthropic";

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
        if (llm == null || blank(llm.provider())) {
            return Optional.empty();
        }
        // A key is what a hosted provider needs and a local one does not. Requiring it
        // everywhere made `provider: ollama` unusable: a local server wants no key, so
        // there was nothing to write in `.env`, and the judge was silently never built.
        if (blank(llm.apiKey()) && !OLLAMA.equals(llm.provider())) {
            return Optional.empty();
        }
        String model = llm.models() == null ? null : llm.models().scoring();
        if (blank(model)) {
            log.warn("llm.models.scoring is not set; nothing can be scored");
            return Optional.empty();
        }
        if (blank(llm.baseUrl())) {
            // Required even for a hosted provider whose address never changes: a URL in
            // the code is a vendor in the code, and this repository has none.
            log.warn("llm.base_url is not set; there is nowhere to send a scoring request");
            return Optional.empty();
        }
        return switch (llm.provider()) {
            // Ollama serves the same chat-completions shape under /v1, so it is the same
            // judge with a different address. It is listed separately because it is the
            // one provider that needs no key, and that is a rule about the value.
            case OPENAI_COMPATIBLE, OLLAMA ->
                Optional.of(new OpenAiCompatibleJudge(llm.baseUrl(), key(llm), model, json));
            case ANTHROPIC -> Optional.of(new AnthropicJudge(llm.baseUrl(), key(llm), model, json));
            default -> {
                log.warn("llm.provider is '{}'; implemented are '{}', '{}' and '{}'",
                        llm.provider(), OPENAI_COMPATIBLE, OLLAMA, ANTHROPIC);
                yield Optional.empty();
            }
        };
    }

    /** Empty rather than null, so a local server gets a harmless header instead of "null". */
    private static String key(PipelineConfig.Llm llm) {
        return llm.apiKey() == null ? "" : llm.apiKey();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
