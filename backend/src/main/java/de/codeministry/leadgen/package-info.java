/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */

/**
 * An acquisition tool for freelancers: it collects project offers from configured sources,
 * filters them against a profile, scores what survives and assembles an application package
 * for the matches.
 *
 * <p>Two rules shape every package below this one. <b>Rules before model</b>: anything a
 * deterministic rule can decide is decided deterministically and for free, so the tool still
 * runs without a language model, only weaker. <b>Nothing is wired in</b>: no source, portal,
 * provider or personal datum appears in a committed file, so a new offer source is a block of
 * YAML rather than a release.
 *
 * <p>And one anti-rule that outranks both: <b>nothing is ever sent</b>. Both outputs are
 * files, and the configuration models no transport, recipient or channel either, because a
 * schema with a place for one is an invitation to fill it. {@code NothingIsSentTest} enforces
 * it by reading the repository.
 *
 * <p>The two classes here are the entry point and the two startup banners, which print the
 * effective configuration and the database actually reached. They exist because the most
 * expensive class of failure in this tool is the silent one, and "which settings did it
 * actually use" is the first question after every one of them.
 */
package de.codeministry.leadgen;
