package de.codeministry.leadgen.config.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/** `matching-rules.yaml`: the deterministic hard filter plus the scoring weights. */
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
                    String confidence) {}
        }

        public record Location(
                List<String> countryAllowlist,
                List<String> rejectKeywords,
                String onsiteHomeBase,
                @Min(0) int onsiteMaxKm,
                List<@Valid OnsiteWaiver> onsiteExceptions) {

            /** A city outside the radius that is acceptable anyway, with the reason. */
            public record OnsiteWaiver(@NotBlank String city, String reason) {}
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
                String rejectBelowAs) {}

        public record Contract(List<String> allowed, List<String> rejected) {}

        public record Language(String preferred, List<String> accepted, int englishOnlyPenalty) {}

        public record Freshness(@Min(1) int maxAgeDays) {}
    }

    public record Scoring(
            @NotNull java.util.Map<String, Integer> weights,
            java.util.Map<String, Integer> penalties,
            @Valid @NotNull Thresholds thresholds) {

        public record Thresholds(
                @Min(0) @Max(100) int autoShortlist, @Min(0) @Max(100) int review, @Min(0) int discard) {}
    }

    public record Deduplication(
            List<String> fingerprintFields,
            List<@Valid Strategy> strategies,
            String mergePolicy,
            @Min(1) int ttlDays) {

        /** {@code threshold} applies to the embedding strategies only. */
        public record Strategy(@NotBlank String type, Double threshold, @NotBlank String action) {}
    }

    public record FollowUp(@Min(1) int afterDays, @Min(0) int maxReminders, @Min(1) int autoExpireDays) {}
}
