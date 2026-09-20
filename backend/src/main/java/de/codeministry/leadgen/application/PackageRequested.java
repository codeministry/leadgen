/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.application;

/**
 * Somebody moved an application to {@link ApplicationStatus#PACKAGED}, which is the decision
 * to apply and therefore the request for a folder.
 *
 * <p>Declared here rather than beside the packaging code that listens for it, so that the
 * dependency keeps running one way: packaging already knows about applications, and
 * applications must not learn about packaging to announce something about itself.
 */
public record PackageRequested(long offerId) {
}
