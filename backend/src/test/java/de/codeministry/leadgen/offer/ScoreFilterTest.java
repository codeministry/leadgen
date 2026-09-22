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
 * One filter, three spellings, and only one of them per request.
 *
 * <p>No container and no context: the rule is in the constructor, which is the point of
 * putting it there. Every combination below returns rows when it is intersected instead of
 * refused, and each one of them looks exactly like a quiet market from the screen.
 */
class ScoreFilterTest {

    @Test
    void refusesABandAndARangeTogether() {
        assertThatThrownBy(() -> new ScoreFilter("shortlist", 60, null, ScoreState.ANY))
                .isInstanceOf(BadShortlistRequest.class)
                .hasMessageContaining("two spellings");
    }

    @Test
    void refusesABandAndAScoreStateTogether() {
        // Always empty rather than wrong, which is worse: an empty list is read as a quiet
        // day on the market and not as a request that cannot be answered.
        assertThatThrownBy(() -> new ScoreFilter("shortlist", null, null, ScoreState.UNSCORED))
                .isInstanceOf(BadShortlistRequest.class)
                .hasMessageContaining("scoreState=unscored");
    }

    @Test
    void refusesARangeAndAScoreStateTogether() {
        assertThatThrownBy(() -> new ScoreFilter(null, 40, 80, ScoreState.SCORED))
                .isInstanceOf(BadShortlistRequest.class)
                .hasMessageContaining("scoreState=scored");
    }

    @Test
    void refusesARangeThatCannotMatchAnything() {
        assertThatThrownBy(() -> new ScoreFilter(null, 80, 40, ScoreState.ANY))
                .isInstanceOf(BadShortlistRequest.class)
                .hasMessageContaining("minScore=80");
    }

    @Test
    void treatsABlankBandAndANullStateAsNoFilterAtAll() {
        // What an absent parameter arrives as. A blank band that stayed a blank band would be
        // "not null" to every check above and would refuse a request asking for nothing.
        var empty = new ScoreFilter("  ", null, null, null);

        assertThat(empty.band()).isNull();
        assertThat(empty.state()).isEqualTo(ScoreState.ANY);
        assertThat(empty.ranged()).isFalse();
        assertThat(empty).isEqualTo(ScoreFilter.ANY);
    }

    @Test
    void takesAHalfOpenRange() {
        assertThat(new ScoreFilter(null, 60, null, null).ranged()).isTrue();
        assertThat(new ScoreFilter(null, null, 60, null).ranged()).isTrue();
    }
}
