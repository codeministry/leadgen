package de.codeministry.leadgen.web;

import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.config.RulesView;
import de.codeministry.leadgen.config.SourceQueryService;
import de.codeministry.leadgen.config.SourceSummary;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The configuration as the screens read it. Read-only, and deliberately so: the four YAML
 * files are the source of truth and they are hot-reloaded, so editing them through an
 * endpoint would mean two ways to change the same thing disagreeing about which won.
 */
@RestController
@RequestMapping("/api")
class ConfigController {

    private final SourceQueryService sources;
    private final ConfigRegistry config;

    ConfigController(SourceQueryService sources, ConfigRegistry config) {
        this.sources = sources;
        this.config = config;
    }

    @GetMapping("/sources")
    List<SourceSummary> sources() {
        return sources.summaries();
    }

    @GetMapping("/rules")
    RulesView rules() {
        return RulesView.of(config.snapshot().rules());
    }
}
