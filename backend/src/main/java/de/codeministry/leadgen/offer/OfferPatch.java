package de.codeministry.leadgen.offer;

import jakarta.validation.constraints.NotNull;

/**
 * What a person changes about an offer by hand.
 *
 * <p>Everything else on an offer is written by the pipeline and rewritten on the next run;
 * this is the one field a person owns. It is a record with a single component rather than a
 * pair of endpoints because archiving and restoring are one decision with two values, and
 * two endpoints would be two places to keep the meaning of `RESTORED` in step.
 *
 * <p><b>{@code Boolean}, not {@code boolean}.</b> Jackson refuses to map an absent value
 * into a primitive, so a PATCH omitting the field would come back 400 rather than saying
 * what is missing — which is exactly what happened to `clearFollowUp` on the application
 * endpoint. Here the field is required, so validation says so instead.
 */
public record OfferPatch(@NotNull Boolean archived) {}
