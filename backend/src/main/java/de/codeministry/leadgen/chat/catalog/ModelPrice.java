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
 * What one model costs, in USD per million tokens, as {@link ModelView#price()} carries it.
 *
 * <p><b>Both halves are decimal strings</b>, {@code "3"} or {@code "15.5"}, never JSON numbers: a
 * number is a float in the browser, and a float rounds a fraction of a cent away before anything
 * is multiplied. The same rule holds for every amount the chat sends, in {@link Usd#wire} form
 * (spec 026, plan § Interfaces).
 *
 * @param input  USD per million input tokens, a plain decimal without trailing zeros.
 * @param output USD per million output tokens, the same.
 */
public record ModelPrice(String input, String output) {

    /** The two halves as configured, written as plain decimals: {@code 15.50} becomes {@code "15.5"}. */
    public static ModelPrice of(BigDecimal input, BigDecimal output) {
        return new ModelPrice(Usd.wire(input), Usd.wire(output));
    }
}
