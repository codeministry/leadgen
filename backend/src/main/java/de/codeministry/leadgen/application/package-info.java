/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */

/**
 * Manual status capture: the half of the loop the system cannot observe.
 *
 * <p>The tool finds, filters, scores and packages. A person sends the mail, so a person
 * records what came back. Every value in here is entered by hand about events the system
 * never saw, which is why <b>no transition is refused</b>: a project can be lost before it
 * was ever answered, and a mistyped status has to be correctable without an argument. The
 * eleven states describe the usual path; they are not a rule enforced against the person who
 * was actually there. A board that argues is a board nobody updates, and this is the only
 * place the state exists.
 *
 * <p>What <em>is</em> checked is that the values make sense together: a sent state with no
 * date gets today, and a closing status drops the follow-up.
 *
 * <p>Every change is an event row. A single mutable row cannot answer "when did I send this"
 * after the second correction.
 */
package de.codeministry.leadgen.application;
