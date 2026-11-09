/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A record of the chat that stays inside the process: it is handed from one bean to another and
 * never reaches Jackson, so no runtime hint is kept for it (ISC-542).
 *
 * <p>Every other record under {@code chat} is read or written as JSON by reflection, and
 * {@code LeadGenRuntimeHintsTest} fails for one without a hint. This marker is the one way out of
 * that rule, and it says so where the type is declared rather than in a list inside the test: a
 * record that later goes into a response or a stored column must lose the marker and gain a hint.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface InProcess {}
