/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat;

import de.codeministry.leadgen.chat.catalog.ChatPrices;
import de.codeministry.leadgen.config.model.PipelineConfig;
import de.codeministry.leadgen.llm.Connections;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * What a turn's tokens cost, as the configuration said when the turn started (spec 026, ISC-519).
 * Read once per turn and held by it, so a price changed or added while the turn runs, or after it
 * ended, never reaches its stored cost.
 *
 * <p>A class rather than a record on purpose: it is a turn's own state and never reaches Jackson,
 * so it needs no reflection hint, which every chat record carries.
 */
final class TurnPrice {

    /**
     * Whether the turn's connection costs money, as {@link Connections#billed} resolves it; a
     * connection that is not configured counts as billed, so its cost stays unknown.
     */
    private final boolean billed;

    /** The price {@code chat.prices} names for the turn's connection and model, or null. */
    private final PipelineConfig.ChatPrice price;

    private TurnPrice(boolean billed, PipelineConfig.ChatPrice price) {
        this.billed = billed;
        this.price = price;
    }

    /** The scale {@code chat_turn.cost_usd} stores, {@code NUMERIC(14,8)}. */
    static final int SCALE = 8;

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    /** The price in force for {@code model} on {@code connection} in {@code pipeline}. */
    static TurnPrice of(PipelineConfig pipeline, Connections.Name connection, String model) {
        if (connection == null || model == null) {
            return new TurnPrice(true, null);
        }
        return Connections.of(pipeline, connection)
                .map(resolved -> new TurnPrice(
                        resolved.billed(),
                        ChatPrices.of(pipeline, connection.name(), resolved.kind(), model)
                                .orElse(null)))
                .orElseGet(() -> new TurnPrice(true, null));
    }

    /** Whether the turn's connection costs money; see {@link #billed}. */
    boolean billed() {
        return billed;
    }

    /**
     * What {@code usage} costs in USD: null without a reported token on any connection — no tokens
     * and no cost is "no data", and a 0 beside it would claim a figure nobody reported — 0 on an
     * unbilled connection, null on a billed one without a price, else input × input price ÷ 1M +
     * output × output price ÷ 1M at {@link #SCALE}, rounded half up once on the exact sum.
     */
    BigDecimal cost(TurnUsage usage) {
        if (!usage.reported()) {
            return null;
        }
        if (!billed) {
            return BigDecimal.ZERO;
        }
        if (price == null) {
            return null;
        }
        BigDecimal input = tokens(usage.inputTokens()).multiply(price.input());
        BigDecimal output = tokens(usage.outputTokens()).multiply(price.output());
        return input.add(output).divide(MILLION, SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal tokens(Integer count) {
        return count == null ? BigDecimal.ZERO : BigDecimal.valueOf(count.longValue());
    }
}
