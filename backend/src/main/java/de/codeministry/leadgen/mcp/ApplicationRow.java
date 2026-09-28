/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.mcp;

import de.codeministry.leadgen.application.ApplicationView;

/**
 * One application as {@code leadgen_list_applications} lists it. Whether a package exists is a flag,
 * never its path: the package is a download from leadgen's own screen and not something a client reaches.
 */
record ApplicationRow(
        long id,
        long offerId,
        String status,
        String title,
        String agency,
        String portal,
        Integer score,
        boolean hasPackage,
        String url) {

    static ApplicationRow of(ApplicationView view) {
        return new ApplicationRow(
                view.id(),
                view.offerId(),
                view.status().name(),
                view.title(),
                view.agency(),
                view.portal(),
                view.scoreValue(),
                view.packageDir() != null && !view.packageDir().isBlank(),
                view.url());
    }
}
