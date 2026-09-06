/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.score;

import de.codeministry.leadgen.config.model.MatchingRules;
import de.codeministry.leadgen.config.model.SkillProfile;
import de.codeministry.leadgen.filter.TextFold;

import java.math.BigDecimal;
import java.util.*;
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
 *
 * <p><b>A factor with nothing to say writes no reason.</b> That is what keeps it out of the
 * denominator in {@link Score#of}, and it is the difference between "this offer pays badly"
 * and "this offer does not mention pay". The sources state a rate in 0.0 % of offers, so
 * the second is the normal case and scoring it as the first capped every score in the table
 * at 53.
 */
public class RuleScorer {

    /**
     * The factors this scorer can decide. The rest belong to a {@link Judge}.
     */
    public static final Set<String> DETERMINISTIC =
            Set.of("core_skill_overlap", "rate_fit", "seniority_fit", "project_setup", "industry_fit");

    /**
     * A seniority the profile is written for. {@code architekt} carries its compounds
     * explicitly because the word boundary in front is load-bearing everywhere else —
     * without them "Softwarearchitekt", which is how German ads write it, names no
     * seniority at all.
     */
    private static final Pattern SENIOR = Pattern.compile("(?<![a-z0-9])(senior|lead|principal|staff|expert|architect"
            + "|(?:software|system|solution|losungs|it|enterprise|cloud|domain)?architekt)(?![a-z0-9])");

    /**
     * A seniority the profile is <em>not</em> written for. Scored, not ignored.
     */
    private static final Pattern JUNIOR = Pattern.compile(
            "(?<![a-z0-9])(junior|werkstudent|praktikant|praktikum|trainee|berufseinsteiger|einsteiger|azubi|ausbildung)(?![a-z0-9])");

    /**
     * How many skills a label names before it stops being readable.
     */
    private static final int LABEL_LIMIT = 8;

    /**
     * A secondary skill counts in full; something off the target profile counts half.
     */
    private static final double PERIPHERAL_SHARE = 0.5;

    private final Map<String, Integer> weights;
    private final SkillProfile profile;
    private final BigDecimal rateFloor;
    private final Integer saturationCoreCount;

    public RuleScorer(MatchingRules rules, SkillProfile profile) {
        this.weights = rules.scoring() == null || rules.scoring().weights() == null
                ? Map.of()
                : rules.scoring().weights();
        this.saturationCoreCount =
                rules.scoring() == null ? null : rules.scoring().saturationCoreCount();
        this.profile = profile;
        this.rateFloor = rules.hardFilters().rate() == null
                ? null
                : BigDecimal.valueOf(rules.hardFilters().rate().minHourlyEur());
    }

    public List<ScoreReason> score(ScoreCandidate offer) {
        String haystack = haystack(offer);

        List<ScoreReason> reasons = new ArrayList<>();
        skillFit(haystack, reasons);
        rate(offer, reasons);
        seniority(haystack, reasons);
        projectSetup(offer, reasons);
        industries(haystack, reasons);
        return reasons;
    }

    /**
     * Everything the offer says, folded once. The tags are in it for every factor and not
     * only for the skills: a portal that files an ad under "Senior" and never writes the
     * word in the prose was answering the seniority question all the same.
     */
    private static String haystack(ScoreCandidate offer) {
        String text = TextFold.fold(offer.title() + " " + offer.description() + " " + offer.fullText());
        String tags = offer.tags() == null
                ? ""
                : String.join(" ", offer.tags().stream().map(TextFold::fold).toList());
        return text + " " + tags;
    }

    /**
     * How much of the profile the ad is asking for, weighted and saturating.
     *
     * <p>It used to be {@code matched.size() / core.size()} — a plain count over the eight
     * core skills, ignoring the per-skill weights and reading neither `strong:` nor
     * `peripheral:` despite the weight table's own comment saying otherwise. Two things
     * were wrong with it and both capped the scale rather than ranking anything. A backend
     * ad cannot name Angular or TypeScript and was charged for it, so no offer in the
     * corpus ever exceeded five of eight; and an ad asking for Kafka, PostgreSQL, Keycloak
     * and CI/CD — four things the profile is strong in — scored exactly nothing for them.
     *
     * <p>What replaces it: the matched weights are added up, secondary skills in full and
     * peripheral ones at half, and the sum is measured against the {@code N} heaviest core
     * skills rather than against all of them. {@code N} is `scoring.saturation_core_count`,
     * and it is the honest statement of what a full match looks like — no ad names a whole
     * profile, so requiring one is requiring something that never happens.
     */
    private void skillFit(String haystack, List<ScoreReason> reasons) {
        Integer weight = weights.get("core_skill_overlap");
        if (weight == null || profile == null || isEmpty(profile.core())) {
            return;
        }
        double saturation = saturation();
        if (saturation <= 0) {
            return;
        }

        Set<String> matched = new LinkedHashSet<>();
        double raw = 0;
        raw += sum(profile.core(), haystack, matched, 1.0);
        raw += sum(profile.strong(), haystack, matched, 1.0);
        raw += sum(profile.peripheral(), haystack, matched, PERIPHERAL_SHARE);

        int points = (int) Math.round(weight * Math.min(1.0, raw / saturation));
        reasons.add(new ScoreReason("core_skill_overlap", label(matched, raw, saturation), points, weight));
    }

    private double sum(List<SkillProfile.Skill> skills, String haystack, Set<String> matched, double share) {
        if (isEmpty(skills)) {
            return 0;
        }
        double sum = 0;
        for (SkillProfile.Skill skill : skills) {
            if (matched.contains(skill.skill()) || !namesSkill(haystack, skill)) {
                continue;
            }
            matched.add(skill.skill());
            sum += skill.weight() * share;
        }
        return sum;
    }

    /**
     * The weight of the {@code N} heaviest core skills — what an ad squarely in the middle
     * of the profile would ask for. Reaching it is a full match; asking for more than it
     * cannot be worth more, which is what the cap in {@link #skillFit} says.
     *
     * <p>Unset means every core skill, which is the old shape: a full match then requires
     * an ad that names the entire profile.
     */
    private double saturation() {
        List<SkillProfile.Skill> core = profile.core();
        int count = saturationCoreCount == null ? core.size() : Math.min(saturationCoreCount, core.size());
        return core.stream()
                .map(SkillProfile.Skill::weight)
                .sorted(Comparator.reverseOrder())
                .limit(count)
                .mapToInt(Integer::intValue)
                .sum();
    }

    private static String label(Set<String> matched, double raw, double saturation) {
        if (matched.isEmpty()) {
            return "names no skill from the profile";
        }
        List<String> shown = matched.stream().limit(LABEL_LIMIT).toList();
        String names = String.join(", ", shown);
        if (matched.size() > shown.size()) {
            names += " and %d more".formatted(matched.size() - shown.size());
        }
        // Never "59 of 55": the sum is allowed to exceed what a full match needs, and a
        // fraction that reads as more-than-whole looks like a bug rather than a good offer.
        return "%s (skill weight %d, a full match is %d)".formatted(names, Math.round(raw), Math.round(saturation));
    }

    private static boolean namesSkill(String haystack, SkillProfile.Skill skill) {
        for (String candidate : spellings(skill.skill())) {
            if (matches(haystack, candidate)) {
                return true;
            }
        }
        return skill.aliases() != null && skill.aliases().stream().anyMatch(a -> matches(haystack, a));
    }

    /**
     * A composite skill name, and each half of it.
     *
     * <p>Folding keeps a name whole, so "REST / API-Design" is the phrase {@code rest api
     * design} and matches only an ad that writes it out in exactly that order. No ad does.
     * The two halves are what an ad actually says, so both are offered to the matcher — the
     * same reason aliases exist, applied to the name the profile already carries.
     */
    private static List<String> spellings(String name) {
        if (name == null || name.indexOf('/') < 0) {
            return List.of(name == null ? "" : name);
        }
        List<String> spellings = new ArrayList<>();
        spellings.add(name);
        for (String part : name.split("/")) {
            if (!part.isBlank()) {
                spellings.add(part.strip());
            }
        }
        return spellings;
    }

    private static boolean matches(String haystack, String keyword) {
        Pattern pattern = TextFold.keyword(keyword);
        return pattern != null && pattern.matcher(haystack).find();
    }

    /**
     * A rate the ad did not state writes no reason at all, so the factor is simply not part
     * of what this offer could have earned.
     *
     * <p>It used to write a 0-point row saying so, which read well and cost every offer in
     * the corpus the same tenth of the scale: the sources state a rate in 0.0 % of offers,
     * so all 101 scored ones carried it. A rate that <em>is</em> stated and is below the
     * floor is a different thing entirely — that is a judgement about the offer, and it
     * keeps its zero and its place in the denominator.
     */
    private void rate(ScoreCandidate offer, List<ScoreReason> reasons) {
        Integer weight = weights.get("rate_fit");
        if (weight == null || rateFloor == null || offer.rateEur() == null) {
            return;
        }
        if (offer.rateEur().compareTo(rateFloor) < 0) {
            reasons.add(new ScoreReason(
                    "rate_fit", "%s €/h, below the floor of %s".formatted(offer.rateEur(), rateFloor), 0, weight));
            return;
        }
        // A rate comfortably above the floor is worth the full weight; at the floor, half.
        boolean comfortable = offer.rateEur().compareTo(rateFloor.multiply(BigDecimal.valueOf(1.2))) >= 0;
        reasons.add(new ScoreReason(
                "rate_fit",
                "%s €/h, %s the floor of %s"
                        .formatted(offer.rateEur(), comfortable ? "well above" : "above", rateFloor),
                comfortable ? weight : weight / 2,
                weight));
    }

    /**
     * Senior scores, junior scores zero, and an ad that names neither writes nothing.
     *
     * <p>The middle case is the one that matters: silence is not a junior posting, and
     * charging it as one used to cost the factor on 63 of 101 offers.
     */
    private void seniority(String haystack, List<ScoreReason> reasons) {
        Integer weight = weights.get("seniority_fit");
        if (weight == null) {
            return;
        }
        if (SENIOR.matcher(haystack).find()) {
            reasons.add(new ScoreReason("seniority_fit", "asks for a senior, lead or architect", weight, weight));
            return;
        }
        if (JUNIOR.matcher(haystack).find()) {
            reasons.add(new ScoreReason("seniority_fit", "asks for a junior or a student", 0, weight));
        }
    }

    /**
     * How much of the shape of the engagement is stated. <b>A bonus, not a share.</b>
     *
     * <p>This is the one factor that measures the advert rather than the fit, and inside the
     * denominator it punishes exactly the wrong thing: an ad naming only a start date scores
     * 3 of 10, while an ad naming nothing keeps the factor out of the denominator entirely
     * and comes out ahead. As an absolute addition it is monotone — nothing stated adds
     * nothing, all three add the full weight — and no offer is charged for a field its
     * portal does not carry.
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
        if (known.isEmpty()) {
            return;
        }
        int points = (int) Math.round(weight * known.size() / 3.0);
        reasons.add(ScoreReason.bonus("project_setup", "states " + String.join(", ", known), points));
    }

    /**
     * The industry the offer is in, when the profile has been there.
     *
     * <p>Matched through `match:` rather than through the name. The profile names an
     * industry in the repository's language and the ads are German, so `Insurance` was
     * compared against text that says <em>Versicherung</em> — measured: the factor fired on
     * 0 of 101 scored offers, a tenth of the scale that could never be earned. The name is
     * still tried, so a profile with no keyword list behaves as before.
     */
    private void industries(String haystack, List<ScoreReason> reasons) {
        Integer weight = weights.get("industry_fit");
        if (weight == null || profile == null || isEmpty(profile.industries())) {
            return;
        }
        SkillProfile.Industry best = null;
        for (SkillProfile.Industry industry : profile.industries()) {
            if (namesIndustry(haystack, industry) && (best == null || industry.weight() > best.weight())) {
                best = industry;
            }
        }
        if (best == null) {
            return;
        }
        // The profile weights industries 1-10; the rules weight the factor. Both matter.
        int points = (int) Math.round(weight * best.weight() / 10.0);
        reasons.add(new ScoreReason("industry_fit", "industry: " + best.name(), points, weight));
    }

    private static boolean namesIndustry(String haystack, SkillProfile.Industry industry) {
        if (industry.match() != null && industry.match().stream().anyMatch(word -> matches(haystack, word))) {
            return true;
        }
        return spellings(industry.name()).stream().anyMatch(spelling -> matches(haystack, spelling));
    }

    private static boolean isEmpty(List<?> list) {
        return list == null || list.isEmpty();
    }
}
