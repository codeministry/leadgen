/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.packaging;

import java.nio.file.Path;
import java.util.List;

/**
 * What one packaging pass did.
 *
 * @param folders the directories written. Directories, not messages: the tool has no
 *     send path at all, and what happens to a package is the operator's decision.
 */
public record PackageReport(int due, int built, int failed, List<Path> folders) {

    public static PackageReport nothing() {
        return new PackageReport(0, 0, 0, List.of());
    }
}
