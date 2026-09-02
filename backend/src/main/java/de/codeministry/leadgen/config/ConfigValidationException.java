/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.config;

import java.util.List;

/** Every configuration problem, with the file it came from, in one message. */
public class ConfigValidationException extends RuntimeException {

    private final transient List<String> problems;

    public ConfigValidationException(String file, List<String> problems) {
        super("%s is invalid:%s"
                .formatted(
                        file, problems.stream().collect(java.util.stream.Collectors.joining("\n  - ", "\n  - ", ""))));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
