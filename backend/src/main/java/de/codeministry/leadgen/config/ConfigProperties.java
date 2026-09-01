package de.codeministry.leadgen.config;

import jakarta.validation.constraints.NotBlank;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Where the tool's own configuration lives. These are Spring properties — the last
 * thing that is allowed to be wired into the process rather than into a YAML file,
 * because something has to say where the YAML files are.
 */
@Validated
@ConfigurationProperties(prefix = "leadgen")
public record ConfigProperties(@NotBlank String configDir, String packagesDir, String inboxDir) {

    public Path configDirectory() {
        return Path.of(configDir).toAbsolutePath().normalize();
    }
}
