/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat;

import de.codeministry.leadgen.chat.catalog.Usd;
import java.util.List;

/**
 * What the chat spent, read from the {@code chat_spend} ledger every turn is added to once as it
 * ends (spec 026, ISC-521): today is the day the call ceilings count, the month the calendar month
 * in the same zone. Every amount is USD as a decimal string in {@link Usd#wire} form, so no float
 * rounds a cent away on its way to the browser.
 *
 * <p>A sum covers stored costs only. A turn stored without a cost is left out of it and counted
 * instead, under one of two causes kept apart: unpriced, a billed model nobody priced; unreported, a
 * model round that delivered part of its stream and reported no usage, whatever the price. A turn
 * from before V40, with no tokens, counts nowhere. Deleting a conversation leaves the ledger alone, so money spent stays spent.
 *
 * @param connections every configured connection, the primary always, the second where one is
 *     configured, each listed even at {@code "0"}; a connection no longer configured that still
 *     spent in the month is listed too, so the total never sums a row nobody can see.
 * @param today       the sum of every turn's cost today, over all connections.
 * @param month       the same for the calendar month.
 */
public record ChatSpend(List<Connection> connections, String today, String month) {

    /**
     * One connection's spend.
     *
     * @param name          {@code PRIMARY} or {@code SECOND}; a turn stored before V37 counts as the
     *     primary.
     * @param today         the sum of its turns' stored costs today.
     * @param month         the same for the calendar month.
     * @param unpricedToday   its turns today that stored tokens but no cost because their billed
     *     model has no price.
     * @param unpricedMonth   the same for the calendar month.
     * @param unreportedToday its turns today that stored no cost because a model round that delivered
     *     part of its stream reported no usage; their tokens, where any, are a lower bound.
     * @param unreportedMonth the same for the calendar month.
     */
    public record Connection(
            String name,
            String today,
            String month,
            int unpricedToday,
            int unpricedMonth,
            int unreportedToday,
            int unreportedMonth) {

        /** A connection that spent nothing in either window. */
        static Connection zero(String name) {
            return new Connection(name, "0", "0", 0, 0, 0, 0);
        }
    }
}
