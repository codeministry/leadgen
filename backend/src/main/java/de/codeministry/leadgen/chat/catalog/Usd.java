/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat.catalog;

import java.math.BigDecimal;

/**
 * The one wire form of every amount the chat sends (spec 026, plan § Interfaces): a price, a
 * turn's cost, a spend. A plain decimal string without trailing zeros, {@code "0.012207"} or
 * {@code "0"}, never a JSON number and never an exponent — a number is a float in the browser, and
 * a float rounds a fraction of a cent away before anything is added up.
 */
public final class Usd {

    private Usd() {}

    /** {@code amount} in the wire form; null stays null. */
    public static String wire(BigDecimal amount) {
        return amount == null ? null : amount.stripTrailingZeros().toPlainString();
    }
}
