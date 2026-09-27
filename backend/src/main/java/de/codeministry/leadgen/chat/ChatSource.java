/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat;

import java.time.LocalDate;

/**
 * One row an answer rests on.
 *
 * @param n        the number its citations carry in the text.
 * @param kind     offer or application.
 * @param id       the row's id; the link the screen draws goes to it.
 * @param title    the offer's title, or for an application the title of its offer.
 * @param source   the name of the source the offer came from; for an application, that of its offer.
 * @param date     when the offer came in, or when the application was created.
 * @param archived whether the offer is archived — an archived row is a valid source, and says so.
 */
public record ChatSource(
        int n, ChatSourceKind kind, long id, String title, String source, LocalDate date, boolean archived) {}
