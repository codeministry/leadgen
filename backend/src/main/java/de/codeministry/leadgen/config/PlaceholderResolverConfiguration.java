/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Where a running application gets its placeholder values from: the process environment,
 * with {@code .env} behind it.
 *
 * <p>A bean and not a call inside {@link ConfigLoader}'s constructor, because the loader used
 * to build the resolver itself and that left no seam: every Spring test context read the
 * developer's environment and {@code .env} while resolving the shipped defaults, so
 * {@code AUTH_MODE=oidc} in a local file refused fifteen MockMvc contexts at startup. The
 * test tree registers its own primary resolver over this one, fed from a fixed set of neutral
 * values, and the loader cannot tell the difference. It also ends the two-constructor
 * arrangement on the loader that {@code backend/CLAUDE.md} lists as a trap.
 */
@Configuration(proxyBeanMethods = false)
class PlaceholderResolverConfiguration {

    @Bean
    PlaceholderResolver placeholderResolver() {
        return PlaceholderResolver.fromSystemEnvironment();
    }
}
