/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.config.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * `matching-rules.yaml`: the deterministic hard filter plus the scoring weights.
 */
public record MatchingRules(
        @Min(1) int version,
        @Valid @NotNull HardFilters hardFilters,
        @Valid @NotNull Scoring scoring,
        List<String> antiSkills,
        @Valid @NotNull Deduplication deduplication,
        @Valid FollowUp followUp) {

    public record HardFilters(
            @Valid @NotNull Remote remote,
            @Valid @NotNull Location location,
            @Valid @NotNull Rate rate,
            @Valid Role role,
            @Valid Contract contract,
            @Valid Language language,
            @Valid Freshness freshness) {

        public record Remote(
                @Min(0) @Max(100) int minRemotePercent,
                boolean acceptUnknown,
                List<String> rejectKeywordsDe,
                List<@Valid Derivation> deriveFrom) {

            /**
             * Derives a remote share the source did not state. {@code set} is either a
             * number or a regex backreference such as {@code $1}, so it stays a string
             * until the rule engine evaluates it.
             */
            public record Derivation(
                    @NotBlank String field,
                    List<String> containsAny,
                    String regex,
                    @NotBlank String set,
                    String confidence) {
            }
        }

        /**
         * @param onsiteCities the places reachable for on-site days, as a list rather
         *                     than a radius. Nothing here geocodes: an offer states its location as free
         *                     text — "Remote und Nürnberg", "DE 7XXXX" — so a kilometre figure would need
         *                     a dataset, a parser and a network call the filter must not need. The list
         *                     is drawn once from a map, and it is what actually runs. A `onsite_max_km`
         *                     key used to sit here; nothing read it, and a number nothing reads is the
         *                     kind of decoration that outlives the intent it was written for.
         */
        public record Location(
                List<String> countryAllowlist,
                List<String> rejectKeywords,
                String onsiteHomeBase,
                List<String> onsiteCities,
                List<@Valid OnsiteWaiver> onsiteExceptions) {

            /**
             * A city outside the usual range that is acceptable anyway, with the reason.
             */
            public record OnsiteWaiver(@NotBlank String city, String reason) {
            }
        }

        /**
         * @param rejectedTitleKeywords roles and stacks that end the assessment on the
         *                              title alone. Deliberately not {@code anti_skills}: that list is documented
         *                              as a scoring penalty worth -30, and reading it as a knockout as well would
         *                              mean anyone tuning the score silently changes what reaches the shortlist.
         *                              The lists also differ — this one rejects roles, not only stacks.
         */
        public record Role(List<String> rejectedTitleKeywords) {
        }

        /**
         * {@code applyAfter} exists because the newsletter carries a rate in 0.0 % of
         * offers. Applied before the enrichment stage this rule filters either
         * everything or nothing — which is why {@link MatchingRules} rejects any other
         * value than {@code enrichment} at load time.
         */
        public record Rate(
                @Min(0) int minHourlyEur,
                @NotBlank String currency,
                boolean acceptUnknown,
                @NotBlank String applyAfter,
                String rejectBelowAs) {
        }

        public record Contract(List<String> allowed, List<String> rejected) {
        }

        public record Language(String preferred, List<String> accepted, int englishOnlyPenalty) {
        }

        public record Freshness(@Min(1) int maxAgeDays) {
        }
    }

    /**
     * @param saturationCoreCount how many of the heaviest core skills an advert has to ask
     *                            for to count as a full match. No advert names a whole profile, so measuring the
     *                            overlap against all of them makes full marks unreachable: over the measured
     *                            corpus the skill factor never once exceeded five of eight core skills, and the
     *                            highest score in the table was 53 out of 100. Unset means all of them, which is
     *                            that old shape.
     */
    public record Scoring(
            @NotNull java.util.Map<String, Integer> weights,
            java.util.Map<String, Integer> penalties,
            @Min(1) Integer saturationCoreCount,
            @Valid @NotNull Thresholds thresholds) {

        /**
         * @param autoShortlist at or above this, a package is built.
         * @param review        at or above this and below {@code autoShortlist}, the digest lists
         *                      it and a person decides. The loader refuses a {@code review} above
         *                      {@code autoShortlist}: {@link de.codeministry.leadgen.score.Score#band} tests
         *                      the shortlist bound first, so the inverted pair does not fail — it silently
         *                      deletes the REVIEW band and builds a package for everything above the lower
         *                      of the two.
         */
        public record Thresholds(
                @Min(0) @Max(100) int autoShortlist, @Min(0) @Max(100) int review, @Min(0) int discard) {
        }
    }

    public record Deduplication(
            List<String> fingerprintFields, List<@Valid Strategy> strategies, String mergePolicy, @Min(1) int ttlDays) {

        /**
         * {@code threshold} applies to the embedding strategies only.
         */
        public record Strategy(@NotBlank String type, Double threshold, @NotBlank String action) {
        }
    }

    public record FollowUp(@Min(1) int afterDays, @Min(0) int maxReminders, @Min(1) int autoExpireDays) {
    }
}
