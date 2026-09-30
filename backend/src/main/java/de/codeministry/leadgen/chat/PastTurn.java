/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat;

/**
 * An earlier question and the answer it got, as the model is shown the conversation so far.
 *
 * <p>Only the question and the answer text: the tool results of an earlier turn are not replayed,
 * because the ids they returned belong to that turn's ledger, and a citation is only a link when
 * a tool returned the id in the turn that cites it.
 */
public record PastTurn(String question, String answer) {}
