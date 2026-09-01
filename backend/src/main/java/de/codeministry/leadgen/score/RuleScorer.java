package de.codeministry.leadgen.score;

import de.codeministry.leadgen.config.model.MatchingRules;
import de.codeministry.leadgen.config.model.SkillProfile;
import de.codeministry.leadgen.filter.TextFold;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The half of the score that needs no model.
 *
 * <p>Rules before model, the same principle the hard filter runs on: whatever can be
 * decided from the profile and the offer's own fields is decided here, for free, and the
 * model is asked only about the factors that genuinely need judgement — role fit and the
 * three penalties.
 *
 * <p><b>No weight is written here.</b> The keys come from `scoring.weights`, and a key the
 * configuration does not carry contributes nothing rather than a default: a weight table
 * someone tuned should be the whole story, not a suggestion the code partly ignores.
 */
public class RuleScorer {

    /** The factors this scorer can decide. The rest belong to a {@link Judge}. */
    public static final Set<String> DETERMINISTIC =
            Set.of("core_skill_overlap", "rate_fit", "seniority_fit", "project_setup", "industry_fit");

    private static final Pattern SENIOR =
            Pattern.compile("(?<![a-z0-9])(senior|lead|principal|architekt|architect)(?![a-z0-9])");

    private final Map<String, Integer> weights;
    private final SkillProfile profile;
    private final BigDecimal rateFloor;

    public RuleScorer(MatchingRules rules, SkillProfile profile) {
        this.weights = rules.scoring() == null || rules.scoring().weights() == null
                ? Map.of()
                : rules.scoring().weights();
        this.profile = profile;
        this.rateFloor = rules.hardFilters().rate() == null
                ? null
                : BigDecimal.valueOf(rules.hardFilters().rate().minHourlyEur());
    }

    public List<ScoreReason> score(ScoreCandidate offer) {
        String text = TextFold.fold(offer.title() + " " + offer.description() + " " + offer.fullText());
        String tags = offer.tags() == null
                ? ""
                : String.join(" ", offer.tags().stream().map(TextFold::fold).toList());
        String haystack = text + " " + tags;

        List<ScoreReason> reasons = new ArrayList<>();
        coreSkills(haystack, reasons);
        rate(offer, reasons);
        seniority(text, reasons);
        projectSetup(offer, reasons);
        industries(haystack, reasons);
        return reasons;
    }

    /**
     * The share of core skills the ad actually names, times the weight. Aliases count, so
     * an ad asking for "Springboot" or "k8s" is naming a core skill — the same reason the
     * filter reads them.
     */
    private void coreSkills(String haystack, List<ScoreReason> reasons) {
        Integer weight = weights.get("core_skill_overlap");
        if (weight == null || profile == null || profile.core() == null || profile.core().isEmpty()) {
            return;
        }
        Set<String> matched = new LinkedHashSet<>();
        for (SkillProfile.Skill skill : profile.core()) {
            if (namesSkill(haystack, skill)) {
                matched.add(skill.skill());
            }
        }
        if (matched.isEmpty()) {
            return;
        }
        int points = (int) Math.round(weight * (double) matched.size() / profile.core().size());
        reasons.add(new ScoreReason(
                "core_skill_overlap",
                "%d of %d core skills named: %s"
                        .formatted(matched.size(), profile.core().size(), String.join(", ", matched)),
                points));
    }

    private static boolean namesSkill(String haystack, SkillProfile.Skill skill) {
        if (matches(haystack, skill.skill())) {
            return true;
        }
        return skill.aliases() != null && skill.aliases().stream().anyMatch(a -> matches(haystack, a));
    }

    private static boolean matches(String haystack, String keyword) {
        Pattern pattern = TextFold.keyword(keyword);
        return pattern != null && pattern.matcher(haystack).find();
    }

    /**
     * A rate the ad did not state scores nothing and says so. It cannot score badly: the
     * hard filter deliberately never reads the rate, because the sources state one in
     * 0.0 % of offers, and enrichment supplies it or does not.
     */
    private void rate(ScoreCandidate offer, List<ScoreReason> reasons) {
        Integer weight = weights.get("rate_fit");
        if (weight == null || rateFloor == null) {
            return;
        }
        if (offer.rateEur() == null) {
            reasons.add(new ScoreReason("rate_fit", "no rate stated, not even in the original ad", 0));
            return;
        }
        int comparison = offer.rateEur().compareTo(rateFloor);
        if (comparison < 0) {
            reasons.add(new ScoreReason(
                    "rate_fit", "%s €/h, below the floor of %s".formatted(offer.rateEur(), rateFloor), 0));
            return;
        }
        // A rate comfortably above the floor is worth the full weight; at the floor, half.
        boolean comfortable = offer.rateEur().compareTo(rateFloor.multiply(BigDecimal.valueOf(1.2))) >= 0;
        reasons.add(new ScoreReason(
                "rate_fit",
                "%s €/h, %s the floor of %s".formatted(offer.rateEur(), comfortable ? "well above" : "above", rateFloor),
                comfortable ? weight : weight / 2));
    }

    private void seniority(String text, List<ScoreReason> reasons) {
        Integer weight = weights.get("seniority_fit");
        if (weight == null || !SENIOR.matcher(text).find()) {
            return;
        }
        reasons.add(new ScoreReason("seniority_fit", "asks for a senior or lead", weight));
    }

    /**
     * How much of the shape of the engagement is known. Not a judgement about the shape —
     * an ad that says nothing about duration, workload or start is simply harder to
     * assess, and that is what this factor measures.
     */
    private void projectSetup(ScoreCandidate offer, List<ScoreReason> reasons) {
        Integer weight = weights.get("project_setup");
        if (weight == null) {
            return;
        }
        List<String> known = new ArrayList<>();
        if (offer.duration() != null) {
            known.add("duration");
        }
        if (offer.workload() != null) {
            known.add("workload");
        }
        if (offer.startsOn() != null) {
            known.add("start");
        }
        int points = (int) Math.round(weight * known.size() / 3.0);
        reasons.add(new ScoreReason(
                "project_setup",
                known.isEmpty() ? "neither duration, workload nor start stated" : "states " + String.join(", ", known),
                points));
    }

    private void industries(String haystack, List<ScoreReason> reasons) {
        Integer weight = weights.get("industry_fit");
        if (weight == null || profile == null || profile.industries() == null) {
            return;
        }
        SkillProfile.Industry best = null;
        for (SkillProfile.Industry industry : profile.industries()) {
            if (matches(haystack, industry.name()) && (best == null || industry.weight() > best.weight())) {
                best = industry;
            }
        }
        if (best == null) {
            return;
        }
        // The profile weights industries 1-10; the rules weight the factor. Both matter.
        int points = (int) Math.round(weight * best.weight() / 10.0);
        reasons.add(new ScoreReason("industry_fit", "industry: " + best.name(), points));
    }
}
