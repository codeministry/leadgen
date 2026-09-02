/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.score;

/**
 * One factor and what it contributed.
 *
 * @param factor a key from `scoring.weights` or `scoring.penalties`. The keys are the
 *     contract with the configuration: a factor spelled differently is scored and then
 *     unexplainable, because nothing links it back to the weight that produced it.
 * @param label what to show a human. Written by whatever produced the points, so it can
 *     name the actual skills that overlapped rather than restating the factor.
 * @param points signed, and already weighted. They sum to the score.
 */
public record ScoreReason(String factor, String label, int points) {}
