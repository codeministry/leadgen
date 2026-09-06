/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.manual;

import java.util.List;

/**
 * The eight fields as the operator corrected them, written back into the file.
 *
 * <p>The same names as everywhere else, because what this produces is the frontmatter the
 * `manual-inbox` source reads. A ninth field here would be a field the pipeline ignores.
 */
public record ManualOfferFields(
        String title,
        String url,
        String description,
        String location,
        String portal,
        String agency,
        String published,
        List<String> tags) {}
