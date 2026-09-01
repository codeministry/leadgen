package de.codeministry.leadgen.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one endpoint the skeleton owes: it lets the frontend prove that the proxy, the API
 * and the JSON contract line up. Everything real arrives with the pipeline stages.
 */
@RestController
@RequestMapping("/api")
public class StatusController {

    private final String application;
    private final String version;

    // Not `@RequiredArgsConstructor`: the parameters carry `@Value`, and Lombok would
    // generate a constructor without them.
    StatusController(
            @Value("${spring.application.name}") String application,
            @Value("${leadgen.version:0.1.0}") String version) {
        this.application = application;
        this.version = version;
    }

    @GetMapping("/status")
    AppStatus status() {
        return new AppStatus(application, version);
    }
}
