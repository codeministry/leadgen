/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
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
        log.info(
                "Configuration loaded: {} sources, {} of them enabled; {} overridden externally",
                snapshot().sources().sources().size(),
                snapshot().sources().sources().stream().filter(s -> s.enabled()).count(),
                overridden.isEmpty() ? "nothing" : String.join(", ", overridden));
    }

    public ConfigSnapshot snapshot() {
        return current.get();
    }

    /**
     * Re-reads all three files. Returns true when the new snapshot took effect.
     *
     * <p><b>One key is refused rather than applied:</b> {@code security.auth} decides the
     * filter chain, and a filter chain is assembled once at startup. Swapping the snapshot
     * underneath it would change what every screen reports about authentication while
     * changing nothing about who may actually call the API — a reload that appears to work
     * and does not. Refusing it here says so out loud and costs a restart, which is the
     * honest price of the setting.
     */
    public boolean reload() {
        try {
            ConfigSnapshot next = loader.load();
            String was = current.get().application().security().auth();
            String now = next.application().security().auth();
            if (!java.util.Objects.equals(was, now)) {
                log.error(
                        "Configuration reload rejected: security.auth would change from '{}' to '{}',"
                                + " and the filter chain is built at startup. Restart to apply it."
                                + " Everything else in this change was not applied either",
                        was,
                        now);
                return false;
            }
            current.set(next);
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
