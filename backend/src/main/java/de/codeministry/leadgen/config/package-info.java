/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */

/**
 * Loading, validating and hot-reloading the four YAML files. Everything else in the
 * application reads {@code ConfigRegistry.snapshot()} and nothing reads a file itself.
 *
 * <p><b>Two layers, the same as Spring's own.</b> Working defaults ship on the classpath under
 * {@code /leadgen/} and are part of the jar; the directory in {@code leadgen.config-dir}
 * overrides them file by file. The tool therefore runs on a fresh clone with no configuration
 * at all, and nothing individual is ever baked into the artifact.
 *
 * <p>The three rule files are <b>one snapshot</b>, read together and swapped atomically:
 * reloading one without the others would hand the pipeline a picture that never existed on
 * disk. Binding is strict, because a misspelled key would otherwise disable a hard filter in
 * silence and the only visible effect is a longer shortlist. Invalid at startup is fatal and
 * invalid at reload is not: running with a filter nobody wrote is worse than not running, but
 * a half-saved file must not take a running tool down.
 *
 * <p>A path in a configuration file names a <b>file</b>, never a location. Anything more
 * forgiving was measured and removed: resolving upwards from the working directory made a run
 * read a file from outside the directory it was pointed at, and look entirely normal doing it.
 */
package de.codeministry.leadgen.config;
