package de.codeministry.leadgen.offer;

import java.util.List;

/**
 * The hard filter as a shape: what came in, what each stage removed, what is left.
 *
 * <p>The stages are in the order they run, and that order is the meaning. An offer stops
 * at the first stage that rejects it, which is the only reason the per-stage counts sum to
 * the total.
 *
 * @param survived stated rather than derived, so a screen never has to reproduce the
 *     subtraction and get it subtly wrong.
 */
public record FunnelView(int total, List<Stage> stages, int survived) {

    public record Stage(String id, String label, int removed) {}
}
