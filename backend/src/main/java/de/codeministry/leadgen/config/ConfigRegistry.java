package de.codeministry.leadgen.config;

import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Holds the configuration the rest of the application reads. One snapshot at a time,
 * swapped atomically, so a pipeline run never sees half of a reload.
 *
 * <p>The failure policies differ by design. At startup an invalid configuration is
 * fatal: starting with a filter nobody wrote is worse than not starting. At reload it
 * is not — the last good snapshot stays in place and the problem is logged, because a
 * half-saved file from an editor must not take the running tool down.
 */
@Slf4j
@Component
public class ConfigRegistry {


    private final ConfigLoader loader;
    private final AtomicReference<ConfigSnapshot> current = new AtomicReference<>();

    ConfigRegistry(ConfigLoader loader) {
        this.loader = loader;
        this.current.set(loader.load());
        var overridden = loader.overriddenFiles();
        log.info("Configuration loaded: {} sources, {} of them enabled; {} overridden externally",
                snapshot().sources().sources().size(),
                snapshot().sources().sources().stream().filter(s -> s.enabled()).count(),
                overridden.isEmpty() ? "nothing" : String.join(", ", overridden));
    }

    public ConfigSnapshot snapshot() {
        return current.get();
    }

    /**
     * Re-reads all three files. Returns true when the new snapshot took effect.
     */
    public boolean reload() {
        try {
            current.set(loader.load());
            log.info("Configuration reloaded");
            return true;
        } catch (ConfigValidationException e) {
            log.error("Configuration reload rejected, keeping the last good one. {}", e.getMessage());
            return false;
        } catch (RuntimeException e) {
            log.error("Configuration reload failed, keeping the last good one", e);
            return false;
        }
    }
}
