package de.codeministry.leadgen.offer;

import java.util.List;

/**
 * The hard filter as a shape: what came in, what each stage removed, what is left.
 *
 * <p>The stages are in the order they run, and that order is the meaning. An offer stops
 * at the first stage that rejects it, which is the only reason the per-stage counts sum to
 * the total.
 *
 * <p><b>The whole shape describes the working list, not the whole table.</b> Archived
 * offers are outside every number here, on both sides of the subtraction — counted on one
 * side only, they once made the rail claim -45 survivors. `archived` sits beside the shape
 * rather than inside it, because leaving the working list is not something the filter did.
 *
 * @param survived stated rather than derived, so a screen never has to reproduce the
 *     subtraction and get it subtly wrong. It equals the shortlist's own total, and that
 *     is the invariant worth checking when either number looks wrong.
 * @param archived primaries taken off the working list, by age or by hand. Not a stage.
 */
public record FunnelView(int total, List<Stage> stages, int survived, int archived) {

    public record Stage(String id, String label, int removed) {}
}
