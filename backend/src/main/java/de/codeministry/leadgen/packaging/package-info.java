/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */

/**
 * The application package: one folder per offer above the shortlist threshold.
 *
 * <p>This is where a send button would arrive one convenient afternoon. The folder is
 * finished and the contact is right there in {@code meta.json}, so the invariant is enforced
 * by a test that reads the repository rather than by remembering it.
 *
 * <p><b>No CV is tailored.</b> The language of the advert picks a fixed PDF and that is the
 * whole rule. A missing file is recorded rather than failing the package: without the CV it is
 * still most of the work.
 *
 * <p>The language is decided by the advert and only falls back to the profile when there is
 * nothing to go on. The order matters: falling back for an advert that simply has no German in
 * it would send a German letter to an English posting.
 *
 * <p>{@code meta.json} carries the decision and not just the offer — the score, every reason
 * behind it, the matched skills, the reference projects chosen, and every portal in the
 * duplicate cluster, so one project advertised three times is one package that says so.
 */
package de.codeministry.leadgen.packaging;
