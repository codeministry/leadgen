/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */

/**
 * The six deterministic knockout stages. No model, no network, no cost.
 *
 * <p>It removes four offers in five for free, and only what survives costs a language-model
 * call. Not one keyword is written here: the lists come from {@code matching-rules.yaml} and
 * the core skills from {@code skill-profile.yaml}, for the same reason no CSS selector is
 * written in Java.
 *
 * <p><b>The order is the meaning</b>: abroad, remote share, out of reach, role or stack, no
 * core skill, contract form. An offer stops at the first rejection, which is the only reason
 * the per-stage counts sum to the total. The verdict is written on the offer, stage and reason
 * both, because a rejection without its reason is a number nobody trusts a week later.
 *
 * <p>{@code TextFold} is the one place text and patterns are normalised, and it exists because
 * the reference got this wrong three separate ways, each silent and each worth hundreds of
 * offers. <b>Fold, then match on word boundaries.</b>
 *
 * <p>Nothing here reads a date and nothing here reads a rate. Age belongs to the archive, and
 * a rate does not exist yet at this point in the pipeline.
 */
package de.codeministry.leadgen.filter;
