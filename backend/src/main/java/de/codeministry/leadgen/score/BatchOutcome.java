/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.score;

import java.util.List;
import java.util.Map;

/**
 * What asking after a submitted batch produced.
 *
 * <p>Three states rather than a nullable map, because they are three different things to
 * do next and collapsing any two of them loses the difference: {@code PENDING} means ask
 * again later, {@code ENDED} means write these and release the offers, {@code FAILED}
 * means release the offers without scores so the next run judges them normally.
 *
 * <p><b>An offer missing from {@code reasons} is not an error.</b> A batch entry can come
 * back errored or expired for one request while the rest succeeded, and that offer simply
 * keeps its deterministic reasons — the same outcome a failed synchronous call produces,
 * and for the same reason: an offer that quietly stopped existing cannot be reviewed.
 */
public record BatchOutcome(Status status, Map<Long, List<ScoreReason>> reasons, String note) {

    public enum Status {
        PENDING,
        ENDED,
        FAILED
    }

    public static BatchOutcome pending() {
        return new BatchOutcome(Status.PENDING, Map.of(), null);
    }

    public static BatchOutcome ended(Map<Long, List<ScoreReason>> reasons) {
        return new BatchOutcome(Status.ENDED, Map.copyOf(reasons), null);
    }

    /** The note is written to `score_batch.note`, so it has to read as a sentence later. */
    public static BatchOutcome failed(String note) {
        return new BatchOutcome(Status.FAILED, Map.of(), note);
    }
}
