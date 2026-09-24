/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.packaging;

import de.codeministry.leadgen.config.model.SkillProfile;
import de.codeministry.leadgen.filter.TextFold;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * What a drafted cover letter may claim, decided without a model.
 *
 * <p>A model writes a better letter than the template and a worse one than the operator, and
 * the ways it is worse are the ways a client notices first: a skill the operator does not have,
 * a skill he has but the advert never asked for, a project he did not do, and the phrases that
 * mark a text as generated. Each of those is decidable from the profile, the advert and the
 * ranking's choice, so each is refused here and a refused draft falls back to the template.
 * Rejecting too much costs a plainer letter; accepting too much costs the client's trust.
 *
 * <h2>What "names" means</h2>
 *
 * <p>The draft declares the skills and projects it cites in {@link Draft#skills()} and
 * {@link Draft#projects()}, and every declared entry is checked. The body is scanned as well,
 * but only for what is decidable without guessing: a spelling the profile knows, of a skill the
 * advert does not name, or the title of a reference project the ranking did not choose. A skill
 * the profile has never heard of and the body mentions undeclared is not something a word
 * matcher can tell apart from ordinary prose, so it is left to the declared list.
 *
 * <p>Plain and deterministic on purpose: no Spring, no model, and a test that constructs its
 * inputs by hand. The banned phrases and the word limit arrive as values, because where they are
 * read from is the caller's concern.
 */
final class CoverLetterGuard {

    private final List<String> bannedPhrases;
    private final int wordLimit;

    /**
     * @param bannedPhrases phrases the letter may not contain, matched folded and on word
     *                      boundaries in the salutation and the body.
     * @param wordLimit     the most words the body may have; positive.
     */
    CoverLetterGuard(List<String> bannedPhrases, int wordLimit) {
        if (wordLimit <= 0) {
            throw new IllegalArgumentException("word limit must be positive, was " + wordLimit);
        }
        this.bannedPhrases = bannedPhrases == null
                ? List.of()
                : bannedPhrases.stream().filter(p -> p != null && !p.isBlank()).toList();
        this.wordLimit = wordLimit;
    }

    /**
     * A letter as the writing model returns it.
     *
     * @param salutation the greeting line, checked for banned phrases only.
     * @param body       the letter's text below the greeting.
     * @param skills     every skill the body cites, as the model spelled it.
     * @param projects   every reference project the body cites, by title.
     */
    record Draft(String salutation, String body, List<String> skills, List<String> projects) {

        Draft {
            skills = present(skills);
            projects = present(projects);
        }

        // A model answering `skills: [null]` gets a rejection with a reason, not an NPE in the build.
        private static List<String> present(List<String> values) {
            return values == null
                    ? List.of()
                    : values.stream().filter(v -> v != null && !v.isBlank()).toList();
        }
    }

    /**
     * Accepted, or rejected with one English line naming what was wrong.
     */
    record Verdict(boolean accepted, String reason) {

        static Verdict accept() {
            return new Verdict(true, null);
        }

        static Verdict reject(String reason) {
            return new Verdict(false, reason);
        }
    }

    /**
     * @param draft   the letter to check.
     * @param profile the operator's profile, whose skills of every tier and whose reference
     *                projects bound what the letter may cite.
     * @param advert  the advert folded by {@link AdText#of}, the same text the build read.
     * @param chosen  the projects {@link ReferenceRanking} chose for this offer, already in the
     *                letter's language.
     */
    Verdict check(Draft draft, SkillProfile profile, String advert, List<ProjectView> chosen) {
        String body = draft.body() == null ? "" : draft.body();
        String foldedBody = TextFold.fold(body);
        String foldedAdvert = advert == null ? "" : advert;
        List<SkillProfile.Skill> skills = skillsOf(profile);
        // A skill is vouched for by the advert asking for it, or by the stack of a project the
        // ranking chose: the ranking chose that project because of what it was built with.
        String vouched = foldedAdvert + " " + chosenStack(profile, chosen);

        return Stream.<Optional<String>>of(
                        declaredSkills(draft, skills, vouched),
                        bodySkills(foldedBody, skills, vouched),
                        declaredProjects(draft, chosen),
                        bodyProjects(foldedBody, profile, chosen),
                        bannedPhrase(draft, foldedBody),
                        wordCount(body))
                .flatMap(Optional::stream)
                .findFirst()
                .map(Verdict::reject)
                .orElseGet(Verdict::accept);
    }

    private static Optional<String> declaredSkills(Draft draft, List<SkillProfile.Skill> skills, String advert) {
        for (String named : draft.skills()) {
            Optional<SkillProfile.Skill> known = skills.stream()
                    .filter(skill -> spellings(skill).anyMatch(s -> sameWord(s, named)))
                    .findFirst();
            if (known.isEmpty()) {
                return Optional.of("names a skill the profile does not list: " + named);
            }
            if (!namedBy(advert, known.get())) {
                return Optional.of("names a skill neither the ad nor a chosen project names: " + named);
            }
        }
        return Optional.empty();
    }

    /**
     * A profile spelling in the body is a claim whether or not the model declared it, and it is
     * decidable, so it answers to the advert the same way a declared one does.
     */
    private static Optional<String> bodySkills(String body, List<SkillProfile.Skill> skills, String advert) {
        return skills.stream()
                .filter(skill -> !namedBy(advert, skill))
                .flatMap(skill -> spellings(skill).filter(s -> AdText.names(body, s)))
                .findFirst()
                .map(s -> "names a skill neither the ad nor a chosen project names: " + s);
    }

    /**
     * By the title the letter would print, which is the title in its own language: the other
     * language's title is not what the ranking handed this letter.
     */
    private static Optional<String> declaredProjects(Draft draft, List<ProjectView> chosen) {
        return draft.projects().stream()
                .filter(named -> chosenTitles(chosen).noneMatch(title -> sameWord(title, named)))
                .findFirst()
                .map(named -> "names a project the ranking did not choose: " + named);
    }

    /**
     * Either title of a project the ranking left out, found in the body. A chosen project's
     * title may of course appear; that is what the letter is for.
     */
    private static Optional<String> bodyProjects(String body, SkillProfile profile, List<ProjectView> chosen) {
        if (profile == null || profile.referenceProjects() == null) {
            return Optional.empty();
        }
        List<String> allowed = chosenTitles(chosen).toList();
        return profile.referenceProjects().stream()
                .flatMap(project -> Stream.of(project.titleDe(), project.titleEn()))
                .filter(title -> title != null && !title.isBlank())
                .filter(title -> allowed.stream().noneMatch(a -> sameWord(a, title)))
                .filter(title -> AdText.names(body, title))
                .findFirst()
                .map(title -> "names a project the ranking did not choose: " + title);
    }

    private Optional<String> bannedPhrase(Draft draft, String foldedBody) {
        String letter = TextFold.fold(draft.salutation()) + " " + foldedBody;
        return bannedPhrases.stream()
                .filter(phrase -> startsAWord(letter, phrase))
                .findFirst()
                .map(phrase -> "contains a banned phrase: " + phrase);
    }

    private Optional<String> wordCount(String body) {
        String stripped = body.strip();
        int words = stripped.isEmpty() ? 0 : WHITESPACE.split(stripped).length;
        return words > wordLimit
                ? Optional.of("body has %d words, the limit is %d".formatted(words, wordLimit))
                : Optional.empty();
    }

    // Unicode-aware, so a body joined with non-breaking spaces is not one long word.
    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    /**
     * A banned phrase matches where a word starts and may run on: the list is written as
     * stems, and "hervorragend" has to catch "hervorragende Kenntnisse" as well. The
     * leading boundary keeps "leverage" from matching inside an unrelated longer word.
     */
    private static boolean startsAWord(String foldedLetter, String phrase) {
        String folded = TextFold.fold(phrase);
        return !folded.isEmpty()
                && Pattern.compile("(?<![a-z0-9])" + Pattern.quote(folded))
                        .matcher(foldedLetter)
                        .find();
    }

    /** Every tier: a requirement served by a {@code strong} skill is as real as a core one. */
    private static List<SkillProfile.Skill> skillsOf(SkillProfile profile) {
        if (profile == null) {
            return List.of();
        }
        List<SkillProfile.Skill> all = new ArrayList<>();
        Stream.of(profile.core(), profile.strong(), profile.peripheral())
                .filter(Objects::nonNull)
                .forEach(all::addAll);
        return all;
    }

    private static Stream<String> spellings(SkillProfile.Skill skill) {
        return Stream.concat(
                        Stream.of(skill.skill()), skill.aliases() == null ? Stream.empty() : skill.aliases().stream())
                .filter(s -> s != null && !TextFold.fold(s).isEmpty());
    }

    /** The advert names a skill when it names any of its spellings, as the build's matcher reads it. */
    private static boolean namedBy(String advert, SkillProfile.Skill skill) {
        return spellings(skill).anyMatch(s -> AdText.names(advert, s));
    }

    /** The folded stack of every reference project the ranking chose, matched by either title. */
    private static String chosenStack(SkillProfile profile, List<ProjectView> chosen) {
        if (profile == null || profile.referenceProjects() == null) {
            return "";
        }
        List<String> titles = chosenTitles(chosen).toList();
        return TextFold.fold(String.join(
                " ",
                profile.referenceProjects().stream()
                        .filter(project -> Stream.of(project.titleDe(), project.titleEn())
                                .anyMatch(t -> t != null && titles.stream().anyMatch(c -> sameWord(c, t))))
                        .flatMap(project -> project.stack() == null ? Stream.empty() : project.stack().stream())
                        .toList()));
    }

    private static Stream<String> chosenTitles(List<ProjectView> chosen) {
        return chosen == null
                ? Stream.empty()
                : chosen.stream().map(ProjectView::title).filter(Objects::nonNull);
    }

    private static boolean sameWord(String a, String b) {
        return TextFold.fold(a).equals(TextFold.fold(b));
    }
}
