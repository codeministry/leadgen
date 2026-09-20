/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.archive;

import java.util.List;

/**
 * A person took these offers off the working list.
 *
 * <p>Only the decision, never the age pass: that one reconciles and undoes itself, and what
 * listens for this deletes files.
 */
public record OffersArchived(List<Long> offerIds) {
}
