/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.config;

import java.util.Map;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;

/**
 * What every Spring test context gets whether or not it asks: placeholders resolved from
 * {@link ConfigFixtures#NEUTRAL_PLACEHOLDERS}, and the shipped defaults as its configuration
 * directory unless it names another.
 *
 * <p><b>The seam is here and in no test.</b> Registered through the test tree's
 * {@code META-INF/spring.factories}, so it applies to every {@code SpringApplication} the
 * test loader builds — a {@code @SpringBootTest}, a slice, a nested context — without any of
 * them importing anything. A fix that lived in each test's fixture is the one that was tried
 * first: it closed {@code ${LLM_*}} in one test and left {@code ${AUTH_MODE}} and twenty-six
 * others reading the developer's environment.
 *
 * <p>The resolver is a second bean of the same type, marked primary, so the loader takes it
 * over the one {@code PlaceholderResolverConfiguration} declares and the production wiring
 * stays as it is. The property source sits directly above {@code systemEnvironment}, which
 * is also above the {@code .env} source registered behind it: a test's own
 * {@code @DynamicPropertySource} or {@code @TestPropertySource} still wins, and the machine
 * never does. Without this, a context that names no directory takes
 * {@code LEADGEN_CONFIG_DIR} from {@code .env} and reads the operator's own {@code config/}.
 */
public final class NeutralDefaultsInitializer implements ApplicationContextInitializer<GenericApplicationContext> {

    static final String PROPERTY_SOURCE = "leadgen-test-defaults";

    @Override
    public void initialize(GenericApplicationContext context) {
        context.registerBean(
                "neutralPlaceholderResolver",
                PlaceholderResolver.class,
                ConfigFixtures::neutralResolver,
                definition -> definition.setPrimary(true));

        MutablePropertySources sources = context.getEnvironment().getPropertySources();
        var defaults = new MapPropertySource(
                PROPERTY_SOURCE,
                Map.of("leadgen.config-dir", ConfigFixtures.shippedDefaults().toString()));
        if (sources.contains(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)) {
            sources.addBefore(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, defaults);
        } else {
            sources.addLast(defaults);
        }
    }
}
