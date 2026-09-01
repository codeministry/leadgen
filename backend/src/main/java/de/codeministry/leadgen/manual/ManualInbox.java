package de.codeministry.leadgen.manual;

import de.codeministry.leadgen.config.ConfigProperties;
import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.config.Directories;
import de.codeministry.leadgen.config.model.SourcesConfig.Source;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Where an offer found by hand waits.
 *
 * <p>Two directories and no table. `pending/` is what an upload lands in and no source
 * globs; the source's own path is what the `manual-inbox` file source reads on the next
 * run. Confirming a review is a file move, so the file stays the record: it can be read
 * with `cat`, and a rejected upload is a file that was deleted rather than a row nobody
 * will ever look at again.
 *
 * <p>The location is not configured here. It is the `path` of the `manual-inbox` source,
 * read from the snapshot, because a second key naming the same directory is a key that
 * disagrees the first time one of them is changed.
 */
@Slf4j
@Component
public class ManualInbox {

    /** The source whose path this is. Not configurable: the endpoint has to find it. */
    public static final String SOURCE_ID = "manual-inbox";

    private static final String PENDING = "pending";

    private final ConfigRegistry config;
    private final Path configDirectory;

    ManualInbox(ConfigRegistry config, ConfigProperties properties) {
        this.config = config;
        this.configDirectory = properties.configDirectory();
    }

    /** Empty when no `manual-inbox` source is configured or it is switched off. */
    public Optional<Source> source() {
        return config.snapshot().sources().sources().stream()
                .filter(source -> SOURCE_ID.equals(source.id()) && source.enabled())
                .findFirst();
    }

    /** The directory the source reads. Created if it is not there yet. */
    public Optional<Path> inbox() {
        return source().map(source -> create(Directories.under(configDirectory, source.path())));
    }

    /** Where an upload waits for review. Deliberately not inside the directory above. */
    public Optional<Path> pending() {
        return inbox().map(inbox -> create(inbox.resolve(PENDING)));
    }

    /**
     * Created at startup rather than on first upload, so a run over an untouched
     * installation does not log a missing directory every time. The source being enabled
     * is what makes the directory the tool's business.
     */
    @EventListener(ApplicationReadyEvent.class)
    void ensure() {
        pending().ifPresent(path -> log.info("Manual inbox at {}, uploads awaiting review in {}",
                path.getParent(), path));
    }

    private static Path create(Path directory) {
        try {
            return Files.createDirectories(directory);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create " + directory, e);
        }
    }
}
