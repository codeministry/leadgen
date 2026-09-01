package de.codeministry.leadgen.config;

import jakarta.validation.constraints.NotBlank;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Where the tool's own configuration lives. These are Spring properties — the last thing
 * that is allowed to be wired into the process rather than into a YAML file, because
 * something has to say where the YAML files are.
 */
@Validated
@ConfigurationProperties(prefix = "leadgen")
public record ConfigProperties(@NotBlank String configDir) {

    // `packages-dir` and `inbox-dir` used to sit here as well, read by nothing: the packages
    // directory is `packaging.output_dir` in pipeline.yaml and the inbox is a source's `path`
    // in sources.yaml, both of which the tool reads itself. Two Spring properties nobody
    // consumed, whose only effect was to make `PACKAGES_DIR` and `INBOX_DIR` look like they
    // meant something here too.

    /**
     * A relative path is searched for upwards from the working directory, because the
     * working directory is not one thing: Gradle's `bootRun` runs in `backend/`, an IDE
     * run configuration in the repository root, and a jar wherever it happens to sit. A
     * default that is correct in one of them is wrong in the others, and the symptom is a
     * missing file with a path nobody recognises.
     */
    public Path configDirectory() {
        return Directories.resolve(configDir);
    }
}
