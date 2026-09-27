/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen;

import de.codeministry.leadgen.config.ConfigProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point. Scheduling is enabled here because the pipeline runs inside this
 * process (concept § 10, phase 1) — there is no separate worker container.
 *
 * <p>Asynchronous execution is enabled for exactly one thing: {@code PackageWorker}, which
 * writes and removes package folders after the transaction that asked for it has committed.
 * The work is disk and templates rather than a request anybody is waiting on, and Boot's own
 * {@code applicationTaskExecutor} runs it — a second pool for one listener would be one more
 * thing to size and shut down.
 */
@SpringBootApplication
// Named rather than scanned: a `@WebMvcTest` slice filters scanned records out but keeps
// what the application class imports, so `StatusController` finds the record in the slice
// without carrying wiring of its own. There is exactly one record to name.
@EnableConfigurationProperties(ConfigProperties.class)
// The five YAML defaults and the three templates are reached by a name computed at
// runtime, so nothing at build time can see them. The class says what that costs.
@ImportRuntimeHints(LeadGenRuntimeHints.class)
@EnableScheduling
@EnableAsync
public class LeadGenerationApplication {

    // Public because native-image resolves the entry point reflectively and rejects a
    // package-private one, with an error about a method that does exist.
    public static void main(String[] args) {
        SpringApplication.run(LeadGenerationApplication.class, args);
    }
}
