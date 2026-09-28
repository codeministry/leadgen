/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * {@code POST /api/v1/chat/conversations/bulk-delete} (spec 022, ISC-477): the conversations to
 * delete, named one by one. There is no form without ids, so a stale client cannot empty a history
 * it did not show.
 *
 * @param ids the conversations, at least one and at most 500.
 */
public record BulkDelete(@NotEmpty @Size(max = 500) List<@NotNull Long> ids) {}
