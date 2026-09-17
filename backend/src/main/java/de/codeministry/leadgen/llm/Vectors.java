/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.llm;

import java.util.Arrays;

/**
 * What a vector has to be before it reaches a pgvector column, in one place.
 *
 * <p><b>One implementation because two would disagree exactly once.</b> These three started as
 * package-private statics on {@code OfferEmbedder}; a second embedding stage copying them is
 * how a vector ends up written at the wrong width into a column that accepts it, and nothing
 * downstream would say so — a shortened vector is still a plausible vector, and cosine distance
 * answers for it just as readily.
 */
public final class Vectors {

    /**
     * The width both vector columns state, and the widest vector pgvector will build an HNSW
     * index on. A model that returns fewer is refused at the seam with both numbers in the
     * sentence rather than in Postgres with only one; a model that returns more is truncated.
     *
     * <p><b>Truncating is sound for the models worth configuring and is not a trick.</b> A model
     * trained with Matryoshka representation learning puts the separation in its leading
     * dimensions, and cosine distance is invariant to a vector's length, so nothing downstream
     * has to be told. Measured twice: on the teaser text, {@code qwen3-embedding:8b} cut from
     * 4096 to 2000 paired 3470 of 2222 adverts above 0.85 against 3397 at full width; on the
     * advert text, 2026-09-17, every acting band came back identical and no percentile moved by
     * more than 0.006. A model trained without it degrades instead, which is why the truncation
     * is announced rather than silent.
     */
    public static final int DIMENSIONS = 2000;

    private Vectors() {}

    /**
     * The leading {@link #DIMENSIONS} of a vector, or the vector itself when it is already that
     * wide. Not renormalised: {@code <=>} is cosine distance and divides by both lengths, so a
     * shortened vector is compared on its direction exactly as a full one is.
     */
    public static float[] narrowed(float[] vector) {
        return vector.length == DIMENSIONS ? vector : Arrays.copyOf(vector, DIMENSIONS);
    }

    /**
     * pgvector's own text form, which is what lets a stage write a vector over plain JDBC with
     * no driver extension: the column takes {@code '[1,2,3]'::vector}.
     */
    public static String literal(float[] vector) {
        StringBuilder out = new StringBuilder(vector.length * 8).append('[');
        for (int index = 0; index < vector.length; index++) {
            if (index > 0) {
                out.append(',');
            }
            out.append(vector[index]);
        }
        return out.append(']').toString();
    }
}
