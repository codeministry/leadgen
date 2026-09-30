/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat;

import java.util.List;

/**
 * What a bulk delete took (ISC-477): the named conversations that existed. An id that named nothing
 * is not in it; it was skipped rather than failing the rest.
 *
 * @param deleted the ids deleted, in ascending order.
 */
public record BulkDeleted(List<Long> deleted) {}
