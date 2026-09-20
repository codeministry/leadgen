/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen;

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Everything this application reaches by a name it computes at runtime.
 *
 * <h2>Why any of this is needed</h2>
 *
 * <p>A native image keeps a resource only if something at build time said it would be wanted.
 * Spring's own AOT processing finds most of them by following the code, and it cannot follow
 * these: {@code ConfigSource.fromClasspath} builds {@code "/leadgen/" + name} from a file name
 * that arrives out of {@code pipeline.yaml}, and {@code PackagingService} renders
 * {@code templates/cover-letter.{lang}.ftl} with the language decided per offer. There is no
 * literal to follow in either case.
 *
 * <h2>Why it is worth a class rather than a shrug</h2>
 *
 * <p>The failure is not a build error and usually not a startup error. A missing resource comes
 * back as an empty {@code Optional}, which is the same answer as "the operator did not override
 * this file" — so the application starts, reports healthy, falls back to nothing, and the first
 * sign of it is a stage that quietly did no work. That is the one defect class this file exists
 * against, and it is why {@code LeadGenRuntimeHintsTest} asserts against the directory's actual
 * contents rather than against a list repeated here.
 *
 * <h2>What is deliberately not here</h2>
 *
 * <p>FreeMarker's own {@code version.properties} and {@code unsafeMethods.properties}, jsoup,
 * flyway, postgresql, snakeyaml and angus-mail all come from the GraalVM reachability-metadata
 * repository, whose version travels with the {@code native-build-tools} plugin. Copying them
 * here would be a second opinion that goes stale on its own schedule.
 * {@code docs/decisions/native-image.md} carries that reasoning and the rest of the native
 * story.
 */
public class LeadGenRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        // Patterns and not seven names, because the set is "whatever ships under leadgen/" and
        // a list here would be a second inventory to keep in step with the directory.
        hints.resources().registerPattern("leadgen/*.yaml");
        hints.resources().registerPattern("leadgen/templates/*.ftl");
    }
}
