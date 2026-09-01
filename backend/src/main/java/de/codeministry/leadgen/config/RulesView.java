package de.codeministry.leadgen.config;

import de.codeministry.leadgen.config.model.MatchingRules;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The rule set as the screen shows it.
 *
 * <p>`weights` and `penalties` are open maps in `matching-rules.yaml`, so this hands over
 * whatever keys are there and names none of them. A screen that knew the keys would stop
 * showing a weight the moment somebody added one.
 */
public record RulesView(
        String version,
        List<RuleWeight> weights,
        List<RuleWeight> penalties,
        Thresholds thresholds,
        List<KnockoutRule> knockouts,
        List<String> antiSkills) {

    public record RuleWeight(String key, int points) {}

    public record Thresholds(int autoShortlist, int review, int discard) {}

    /** One hard filter, as a sentence rather than as a nested object. */
    public record KnockoutRule(String key, String label, String value) {}

    public static RulesView of(MatchingRules rules) {
        var filters = rules.hardFilters();
        var knockouts = new ArrayList<KnockoutRule>();
        knockouts.add(new KnockoutRule(
                "location.country_allowlist",
                "Countries",
                join(filters.location().countryAllowlist(), "everywhere")));
        knockouts.add(new KnockoutRule(
                "location.onsite_cities",
                "Reachable for on-site days",
                // An empty list is worth stating rather than hiding: it means only remote
                // offers can pass, which otherwise looks exactly like a quiet market.
                join(filters.location().onsiteCities(), "nowhere — only remote offers pass")));
        knockouts.add(new KnockoutRule(
                "remote.min_remote_percent",
                "Minimum remote share",
                filters.remote().minRemotePercent() + " %"
                        + (filters.remote().acceptUnknown() ? ", unstated accepted" : ", unstated rejected")));
        knockouts.add(new KnockoutRule(
                "role.rejected_title_keywords",
                "Rejected on the title alone",
                join(filters.role() == null ? null : filters.role().rejectedTitleKeywords(), "nothing")));
        knockouts.add(new KnockoutRule(
                "contract.rejected",
                "Rejected contract forms",
                join(filters.contract() == null ? null : filters.contract().rejected(), "nothing")));
        knockouts.add(new KnockoutRule(
                "freshness.max_age_days",
                "Maximum age",
                filters.freshness() == null ? "any" : filters.freshness().maxAgeDays() + " days"));
        // The rate rule is listed with the reason it does not run here: it is configured
        // `apply_after: enrichment` and the loader refuses any other value.
        knockouts.add(new KnockoutRule(
                "rate.min_hourly_eur",
                "Minimum rate",
                filters.rate().minHourlyEur() + " " + filters.rate().currency() + ", applied after "
                        + filters.rate().applyAfter()));

        return new RulesView(
                String.valueOf(rules.version()),
                weights(rules.scoring().weights()),
                weights(rules.scoring().penalties()),
                new Thresholds(
                        rules.scoring().thresholds().autoShortlist(),
                        rules.scoring().thresholds().review(),
                        rules.scoring().thresholds().discard()),
                knockouts,
                rules.antiSkills() == null ? List.of() : rules.antiSkills());
    }

    /** Largest first, because the screen is read to find out what actually decides. */
    private static List<RuleWeight> weights(Map<String, Integer> configured) {
        if (configured == null) {
            return List.of();
        }
        return configured.entrySet().stream()
                .map(entry -> new RuleWeight(entry.getKey(), entry.getValue()))
                .sorted((a, b) -> Integer.compare(Math.abs(b.points()), Math.abs(a.points())))
                .toList();
    }

    private static String join(List<String> values, String whenEmpty) {
        return values == null || values.isEmpty() ? whenEmpty : String.join(", ", values);
    }
}
