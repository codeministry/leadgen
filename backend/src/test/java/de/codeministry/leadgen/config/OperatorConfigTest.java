/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.config;

import static org.assertj.core.api.Assertions.assertThatCode;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Binds the operator's own `config/` directory, when there is one.
 *
 * <p>This exists because of a bug it would have caught. The enrichment schema changed from
 * a list of field names to a map of field rules; the shipped defaults were rewritten and
 * every test stayed green, because every test reads the shipped defaults. The operator's
 * file kept the old shape and the service refused to start — correctly, loudly, and only
 * at the next restart.
 *
 * <p>The gap was structural: a schema change is verified against the files in the
 * repository, and the one file that is not in the repository is the one people actually
 * run. Skipped when `config/` is absent, like every other test that needs something
 * gitignored.
 */
class OperatorConfigTest {

    private static final Validator VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void theOperatorsOwnConfigurationStillBinds() {
        Path directory = ConfigFixtures.repositoryRoot().resolve("config");
        Assumptions.assumeTrue(
                Files.isRegularFile(directory.resolve("pipeline.yaml")),
                "config/ is absent — it is gitignored, so this check only runs on a machine that has one");

        // The fixture's own values and never the machine's: what is under test is that every
        // key in the file still binds to the model, not what this developer's .env says. A
        // placeholder only the operator's file names resolves to nothing, as it would on a
        // machine that never set it.
        assertThatCode(() -> ConfigFixtures.loaderFor(directory, VALIDATOR, ConfigFixtures.NEUTRAL_PLACEHOLDERS)
                        .load())
                .doesNotThrowAnyException();
    }
}
