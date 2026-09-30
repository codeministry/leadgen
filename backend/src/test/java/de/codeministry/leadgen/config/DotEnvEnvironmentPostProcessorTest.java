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
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.ssl.JksSslBundleProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * A map bound from {@code .env} (operator, 2026-09-30): {@code SPRING_SSL_BUNDLE_JKS_OIDC_*} was in
 * the file and no {@code oidc} bundle existed, because Boot maps environment-variable names only for
 * a source whose name says it is one.
 */
class DotEnvEnvironmentPostProcessorTest {

    private static final Map<String, Object> BUNDLE = Map.of(
            "SPRING_SSL_BUNDLE_JKS_OIDC_TRUSTSTORE_LOCATION", "file:/tmp/truststore.p12",
            "SPRING_SSL_BUNDLE_JKS_OIDC_TRUSTSTORE_TYPE", "PKCS12");

    private static Map<String, JksSslBundleProperties> bundlesFrom(String sourceName) {
        var environment = new StandardEnvironment();
        environment.getPropertySources().addLast(new SystemEnvironmentPropertySource(sourceName, BUNDLE));
        return Binder.get(environment)
                .bind("spring.ssl.bundle.jks", Bindable.mapOf(String.class, JksSslBundleProperties.class))
                .orElse(Map.of());
    }

    @Test
    void bindsAMapOfEnvironmentStyleNamesUnderTheSourcesName() {
        Map<String, JksSslBundleProperties> bundles = bundlesFrom(DotEnvEnvironmentPostProcessor.SOURCE_NAME);

        Assertions.assertThat(bundles).containsOnlyKeys("oidc");
        Assertions.assertThat(bundles.get("oidc").getTruststore().getLocation()).isEqualTo("file:/tmp/truststore.p12");
        Assertions.assertThat(bundles.get("oidc").getTruststore().getType()).isEqualTo("PKCS12");
    }

    @Test
    void bindsNothingUnderTheFilesBareName() {
        // The trap, kept visible: the same keys under the name `.env` bind no map at all.
        Assertions.assertThat(bundlesFrom(DotEnv.FILE_NAME)).isEmpty();
    }
}
