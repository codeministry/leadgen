/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.archive;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * The offers a person is taking off the working list in one go.
 *
 * <p>It lives here rather than beside {@code OfferPatch}, which is what a person changes
 * <em>about an offer</em>. This is a request about the archive, and it names no direction:
 * there is no bulk restore, so a {@code boolean} here would have exactly one legal value —
 * the class of thing this repository already calls a lie, after {@code remote.accept_unknown},
 * the {@code onsite_max_km} key nothing read, and {@code security.auth: basic}. The two-valued
 * meaning stays in {@link ArchiveService}, where it is written once.
 *
 * <p><b>{@code @NotNull} goes on the type argument, never on the container.</b> Same shape and
 * same reason as the {@code @Valid} rule the configuration model follows: {@code @NotEmpty}
 * and {@code @Size} describe the list, {@code @NotNull} describes an element.
 *
 * @param ids the offers, deduplicated. Duplicates are collapsed at the door because
 *            {@code = ANY} updates a row once whatever the array says: without this,
 *            {@code [1,1,2]} would answer {@code requested 3, archived 2} and read as an
 *            offer that got away.
 */
public record ArchiveRequest(@NotEmpty @Size(max = ArchiveRequest.MAX_IDS) List<@NotNull Long> ids) {

    /**
     * A ceiling against a hand-written request, the way {@code ShortlistQuery.MAX_LIMIT} is —
     * and it is deliberately above what the screen can select in one sitting, so the browser
     * never has to restate it. A second copy of a bound in TypeScript is what the read side
     * has already had to remove twice.
     *
     * <p><b>A read that asks for too much is narrowed; a write that asks for too much is
     * refused.</b> {@code ShortlistQuery} clamps its limit in the compact constructor. This
     * must not: silently archiving the first 500 of 700 offers is a wrong write with no
     * symptom at all.
     */
    public static final int MAX_IDS = 500;

    /**
     * <b>The null guard is load-bearing.</b> {@code @NotEmpty} runs after construction, so a
     * body of <code>{}</code> reaches the canonical constructor with a null list, and
     * {@code new LinkedHashSet<>(null)} would throw into a 500 where a 400 belongs.
     */
    public ArchiveRequest {
        ids = ids == null ? null : List.copyOf(new LinkedHashSet<>(ids));
    }
}
