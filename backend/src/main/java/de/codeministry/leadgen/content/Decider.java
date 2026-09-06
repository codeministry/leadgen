/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.content;

/**
 * Who decided a block's kind.
 *
 * <p>Kept on the block rather than inferred, because the three answer different questions
 * when something looks wrong: a {@link #RULE} is a pattern somebody wrote, a {@link #CACHE}
 * hit is a decision made for an earlier offer, a {@link #MODEL} answer is fresh, and
 * {@link #DEFAULT} means nobody decided at all — which is the only one of the four that is
 * not evidence of anything.
 */
public enum Decider {
    RULE,
    CACHE,
    MODEL,
    DEFAULT
}
