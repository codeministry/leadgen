/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.filter;

import java.util.Locale;
import lombok.RequiredArgsConstructor;

/**
 * The stages, in the order they run. The order is not cosmetic: each one is cheaper and
 * more certain than the next, and the counts per stage only sum to the total because an
 * offer stops at the first one that rejects it.
 *
 * <p><b>Age is deliberately absent, and used to be here.</b> "Too old" is not a verdict
 * about an advert — an old advert is a perfectly good advert that is no longer worth
 * answering — and a verdict is what this enum reports to the funnel. The rule kept its
 * name and moved to {@code archive.ArchiveService}.
 *
 * <p>The rate rule is deliberately absent. It is configured as `apply_after: enrichment`
 * and the config loader refuses any other value, because the sources state a rate in
 * 0.0 % of offers — applied here it would discard everything or nothing.
 */
@RequiredArgsConstructor
public enum FilterStage {
    ABROAD("abroad"),
    REMOTE_SHARE("remote share below the minimum"),
    OUT_OF_REACH("beyond reach, not remote"),
    ROLE_OR_STACK("foreign stack or wrong role"),
    NO_CORE_SKILL("no core skill"),
    CONTRACT_FORM("contract form rejected");

    private final String description;

    public String description() {
        return description;
    }

    /**
     * The wire id every screen names this stage by: the enum name lower-case with {@code _} as
     * {@code -}. Computed here and nowhere else, so the funnel, the stage mix and the rules
     * screen join on the same string. {@link Locale#ROOT} because this is an identifier, not
     * text: under a Turkish default locale {@code NO_CORE_SKILL} would lower-case to a dotless
     * {@code ı}, and every join on it would miss without an error.
     */
    public String id() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
