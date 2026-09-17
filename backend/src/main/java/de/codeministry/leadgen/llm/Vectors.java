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
     * The inverse of {@link #literal}: pgvector's text form back into a vector.
     *
     * <p>Needed because a {@code vector} column comes back from the driver as text, the same way
     * it goes in. Null or blank is nothing rather than an empty vector — an offer that has not
     * been indexed has no direction, and a zero vector would have one that compares to
     * everything equally badly while looking like an answer.
     */
    public static float[] parse(String literal) {
        if (literal == null || literal.isBlank()) {
            return null;
        }
        String inner = literal.strip();
        if (inner.startsWith("[") && inner.endsWith("]")) {
            inner = inner.substring(1, inner.length() - 1);
        }
        if (inner.isBlank()) {
            return null;
        }
        String[] parts = inner.split(",");
        float[] vector = new float[parts.length];
        for (int index = 0; index < parts.length; index++) {
            vector[index] = Float.parseFloat(parts[index].strip());
        }
        return vector;
    }

    /**
     * Cosine similarity, which is what the comparisons outside SQL are stated in.
     *
     * <p><b>A similarity and not a distance, unlike pgvector's {@code <=>}.</b> The two are one
     * minus the other, and reading one as the other does not fail — it ranks everything
     * backwards while still returning plausible numbers. Named so the sign is on the method.
     */
    public static double similarity(float[] a, float[] b) {
        int width = Math.min(a.length, b.length);
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int index = 0; index < width; index++) {
            dot += (double) a[index] * b[index];
            na += (double) a[index] * a[index];
            nb += (double) b[index] * b[index];
        }
        double norm = Math.sqrt(na) * Math.sqrt(nb);
        return norm == 0 ? 0 : dot / norm;
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
