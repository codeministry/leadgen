/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.offer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * A cursor's number comes back from the client. Bound as an int it is checked, not narrowed: a
 * long past the int range would wrap and page from an unrelated position without a word.
 */
class ShortlistSortKeyTest {

    @Test
    void bindsANumberInRangeAsTheIntItIs() {
        assertThat(ShortlistSort.Key.NUMBER.bind(87)).isEqualTo(87);
        assertThat(ShortlistSort.Key.NUMBER.bind(Integer.MIN_VALUE)).isEqualTo(Integer.MIN_VALUE);
    }

    @Test
    void refusesANumberPastTheIntRangeAsABadRequest() {
        assertThatThrownBy(() -> ShortlistSort.Key.NUMBER.bind(Integer.MAX_VALUE + 1L))
                .isInstanceOf(BadShortlistRequest.class);
        assertThatThrownBy(() -> ShortlistSort.Key.NUMBER.bind(Long.MIN_VALUE)).isInstanceOf(BadShortlistRequest.class);
    }
}
