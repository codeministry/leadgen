package de.codeministry.leadgen.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Notices changes to the three configuration files and asks the registry to reload.
 *
 * <p>Polling rather than {@link java.nio.file.WatchService}: for three files the
 * efficiency argument is worth nothing, and the watch service is native only on Linux.
 * On macOS the JDK falls back to a polling implementation whose default latency is ten
 * seconds — the same mechanism as here, but with platform-dependent timing nobody can
 * reason about, and an API that stops seeing a file the moment an editor replaces it by
 * renaming a temp file over it.
 *
 * <p>A change is acted on one cycle after it is first seen, and only if the file has
 * stopped changing by then. That is the whole protection against reading a file an
 * editor is still writing: a half-saved file would be rejected as invalid, which is
 * harmless but noisy, and the reload would then have to be triggered again by hand.
 */
@Component
public class ConfigWatcher {

    private static final Logger log = LoggerFactory.getLogger(ConfigWatcher.class);

    private final ConfigRegistry registry;
    private final ConfigLoader loader;
    private final Map<Path, Stamp> applied = new HashMap<>();
    private final Map<Path, Stamp> pending = new HashMap<>();

    /** Size as well as timestamp: a file saved twice within one filesystem tick differs only in size. */
    private record Stamp(long lastModified, long size) {}

    ConfigWatcher(ConfigRegistry registry, ConfigLoader loader) {
        this.registry = registry;
        this.loader = loader;
        loader.watchedFiles().forEach(file -> applied.put(file, stamp(file)));
    }

    @Scheduled(fixedDelayString = "${leadgen.config-poll-interval:PT2S}")
    public void pollForChanges() {
        Map<Path, Stamp> now = new HashMap<>();
        loader.watchedFiles().forEach(file -> now.put(file, stamp(file)));

        boolean changed = !now.equals(applied);
        boolean settled = now.equals(pending);

        pending.clear();
        pending.putAll(now);

        if (!changed) {
            return;
        }
        if (!settled) {
            // First sighting. Wait one cycle so a save in progress finishes first.
            return;
        }

        applied.clear();
        applied.putAll(now);

        if (!registry.snapshot().application().rules().hotReload()) {
            log.info("Configuration changed on disk, but rules.hot_reload is off — restart to apply it");
            return;
        }
        registry.reload();
    }

    /**
     * A missing file gets a stamp of its own rather than an exception: deleting and
     * rewriting a file is a change like any other, and the reload after it reports the
     * missing file properly.
     */
    private static Stamp stamp(Path file) {
        try {
            return new Stamp(Files.getLastModifiedTime(file).toMillis(), Files.size(file));
        } catch (IOException e) {
            return new Stamp(-1, -1);
        }
    }
}
