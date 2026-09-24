/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.packaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import de.codeministry.leadgen.config.model.CoverLetterStyle;
import de.codeministry.leadgen.config.model.PipelineConfig;
import de.codeministry.leadgen.config.model.SkillProfile;
import de.codeministry.leadgen.llm.Answers;
import de.codeministry.leadgen.llm.ChatModels;
import de.codeministry.leadgen.llm.LlmBudget;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

/**
 * Asks the configured writing model for a cover letter drafted against one advert.
 *
 * <p>The template names the matched skills and pitches two projects in the same sentences
 * every time, which is exactly what reads as generated. A model can open with the requirement
 * the advert cares about and say why this operator fits it — and can also invent a skill, a
 * project or a tone nobody would send. So this class only drafts: {@link CoverLetterGuard}
 * decides whether the draft may be sent, and {@link PackagingService} writes the template
 * whenever it may not.
 *
 * <h2>What "no draft" means</h2>
 *
 * <p>{@link Optional#empty()} for every miss — no style lines for the letter's language, no
 * writing model, no budget left, a call that threw, an answer that is not the JSON asked for.
 * The build treats all five the same way. {@link #attempt} says which one it was only for the
 * spent budget, because a person who asked for a fresh draft is answered "not now" for that one
 * and gets the template for the other four.
 * <b>There is no second attempt</b>, against this model or any other: a letter written by the
 * scoring model would be written by a model nobody chose for prose, and a letter written by a
 * hosted fallback would be billed without anything in the folder to show it.
 *
 * <h2>The salutation is decided here, not by the model</h2>
 *
 * <p>The only source of a name is the advert's own text, and only where it puts an honorific in
 * front of it ("Frau Stader", "Mr Miller"). The offer's {@code contact} column is not read: it
 * is filled by enrichment, it held a person in 0 of 165 offers of the local corpus, and what it
 * held instead — ad fragments, company names — became a greeting addressed to nobody. The prompt names the person the advert names, and {@link #salutation} still overrules
 * the model deterministically: a greeting to anybody the advert does not name becomes the
 * neutral one, and a greeting that leaves the named person out, or hides more than a greeting,
 * becomes the configured one for that person.
 */
@Slf4j
@Component
class CoverLetterWriter {

    /**
     * English, like every instruction in this repository; the letter itself is in the advert's
     * language, which the user message names.
     *
     * <p>The examples are framed as tone only because a model copies what it is shown: an
     * example letter carries another client, another project and another year, and any of
     * them in this letter would be a claim nobody made.
     */
    private static final String INSTRUCTIONS = """
            You write one cover letter for a freelance software developer, answering one job
            advert. The letter is sent by a person to a person and must read like it.

            Facts come only from the sections headed ADVERT, PROFILE SKILLS and PROJECTS.
            Never claim a skill, a project, a client, a year or a number that is not there.
            Name a skill only when the profile lists it AND the advert asks for it.
            Name a project only if it is listed under PROJECTS, by its title as written there.

            Write in the letter language given below. Plain, specific sentences. No superlatives,
            no enthusiasm formulas, no summary of the advert back to its author. Open with the one
            requirement of the advert the developer matches best, not with the developer. Mention
            the start date if one is given. End with availability and a plain offer to talk.
            Do not write a closing line or a signature; they are added afterwards.

            Salutation: if a contact person named in the advert is given, greet that person with
            the honorific and surname exactly as given, in the letter language's usual formal way,
            on one line. If none is given, use exactly the neutral salutation given below. Never
            invent a name, and never greet a company, a team or a word taken from the advert.

            Follow every STYLE RULE. A phrase listed as banned must not appear in any form.

            Anything under EXAMPLE LETTERS is TONE ONLY: take the register, rhythm and length from
            it, and NEVER a fact — no project, client, skill, date or number from an example may
            appear in this letter.

            Answer with this JSON and nothing else:
            {"salutation": "the greeting line", "body": "the letter below the greeting, paragraphs separated by a blank line",
             "skills": ["every skill the body names, spelled as in PROFILE SKILLS"],
             "projects": ["every project the body names, by its title under PROJECTS"]}
            """;

    /** One capitalised name word, a double-barrelled one included: "Stader", "Müller-Lüdenscheidt". */
    private static final String NAME = "\\p{Lu}\\p{Ll}+(?:-\\p{Lu}\\p{Ll}+)*";

    /**
     * A person as an advert names one: one or two honorifics, then one to three name words.
     *
     * <p>Case-sensitive, so "frau" in running text is not an honorific, and never across a line
     * break, so a signature block's next line cannot join the name. The honorific is required
     * on purpose: two capitalised words are a person as often as they are "Artificial
     * Intelligence" or "Ferchau Engineering", and a letter to either is the mistake a reader
     * notices in the first line.
     */
    private static final Pattern PERSON =
            Pattern.compile("\\b((?:Herrn|Herr|Frau|Mrs|Mr|Ms)\\.?[ \\t]+(?:Dr\\.?[ \\t]+)?)(" + NAME + "(?:[ \\t]+"
                    + NAME + "){0,2})(?!\\p{L})");

    /** A greeting says who it greets and stops; anything past that is a paragraph in disguise. */
    private static final int GREETING_WORDS = 8;

    /** Words that follow a name on a contact line without being part of it. */
    private static final Set<String> LABELS = Set.of(
            "tel",
            "telefon",
            "fax",
            "mobil",
            "email",
            "mail",
            "ansprechpartner",
            "ansprechpartnerin",
            "personalberater",
            "personalberaterin",
            "recruiter",
            "recruiterin",
            "phone",
            "mobile");

    private static final Pattern SENTENCE_MARK = Pattern.compile("[.!?;:]");

    private final ChatModels chatModels;
    private final LlmBudget budget;
    private final JsonMapper json = JsonMapper.builder().build();

    CoverLetterWriter(ChatModels chatModels, LlmBudget budget) {
        this.chatModels = chatModels;
        this.budget = budget;
    }

    /**
     * Everything the model is shown for one letter.
     *
     * @param offerId      for the log only
     * @param language     the letter's language, ISO 639-1
     * @param advert       the advert as a person reads it: title, description, content; the
     *                     only place a contact person's name is read from
     * @param agency       who wrote the advert, or null
     * @param profile      the operator's profile; skills of every tier are offered
     * @param projects     the projects the ranking chose, already in the letter's language
     * @param startsOnText the start date as the letter's language writes it, or null
     * @param rules        the style rules for the letter's language
     * @param examples     the operator's own letters, for tone only
     */
    record Request(
            long offerId,
            String language,
            String advert,
            String agency,
            SkillProfile profile,
            List<ProjectView> projects,
            String startsOnText,
            CoverLetterStyle.Rules rules,
            List<String> examples) {}

    /**
     * A draft to hand to the guard, or nothing — see the class comment for the four misses.
     *
     * <p>The budget is asked only once a model is known to exist, so an installation without
     * a writing model spends nothing, and it is asked exactly once, before the one call.
     */
    Optional<CoverLetterGuard.Draft> draft(PipelineConfig.Llm llm, Request request) {
        return attempt(llm, request).draft();
    }

    /**
     * One try at a draft: the draft, or nothing, and whether nothing is because the budget
     * refused the call. Every other miss reads {@code budgetRefused == false}.
     */
    record Attempt(Optional<CoverLetterGuard.Draft> draft, boolean budgetRefused) {
        static final Attempt MISSED = new Attempt(Optional.empty(), false);
        static final Attempt REFUSED = new Attempt(Optional.empty(), true);
    }

    /** {@link #draft}, telling a spent budget apart from the other four misses. */
    Attempt attempt(PipelineConfig.Llm llm, Request request) {
        CoverLetterStyle.Rules rules = request.rules();
        if (rules == null || rules.neutralSalutation() == null || rules.namedSalutation() == null) {
            // No greeting anybody chose for this language, so no letter can be assembled from a
            // draft; asking the model would spend a call on an answer nobody can use.
            return Attempt.MISSED;
        }
        Optional<ChatModel> model = chatModels.writing(llm);
        if (model.isEmpty()) {
            return Attempt.MISSED;
        }
        if (!budget.take()) {
            return Attempt.REFUSED;
        }
        String content;
        try {
            content = Answers.textOf(ChatClient.create(model.get())
                    .prompt()
                    .system(INSTRUCTIONS)
                    .user(describe(request))
                    .call()
                    .chatResponse());
        } catch (RuntimeException e) {
            log.warn("Drafting the cover letter for offer {} failed: {}", request.offerId(), e.getMessage());
            return Attempt.MISSED;
        }
        return new Attempt(parse(content, request), false);
    }

    /** The user message: the advert first, then what may be claimed, then how to write it. */
    static String describe(Request request) {
        StringBuilder out = new StringBuilder();
        out.append("Letter language: ").append(request.language()).append('\n');
        CoverLetterStyle.Rules rules = request.rules() == null ? CoverLetterStyle.Rules.UNCONSTRAINED : request.rules();
        out.append("Neutral salutation: ").append(rules.neutralSalutation()).append('\n');
        out.append("Contact person named in the advert: ")
                .append(personsIn(request.advert()).stream()
                        .findFirst()
                        .map(Person::greeted)
                        .orElse("none — use the neutral salutation"))
                .append('\n');
        if (request.agency() != null && !request.agency().isBlank()) {
            out.append("Advertised by: ").append(request.agency().strip()).append('\n');
        }
        if (request.startsOnText() != null) {
            out.append("Start date: ").append(request.startsOnText()).append('\n');
        }

        out.append("\nADVERT\n")
                .append(request.advert() == null ? "" : request.advert().strip())
                .append('\n');

        out.append("\nPROFILE SKILLS\n");
        skillsOf(request.profile())
                .forEach(skill -> out.append("- ").append(skill).append('\n'));

        out.append("\nPROJECTS\n");
        List<ProjectView> projects = request.projects() == null ? List.of() : request.projects();
        if (projects.isEmpty()) {
            out.append("none — name no project\n");
        }
        for (ProjectView project : projects) {
            out.append("- ").append(project.title());
            if (project.period() != null) {
                out.append(" (").append(project.period()).append(')');
            }
            out.append(": ")
                    .append(project.pitch() == null ? "" : project.pitch())
                    .append('\n');
        }

        out.append("\nSTYLE RULES\n");
        if (rules.wordLimit() != null && rules.wordLimit() != Integer.MAX_VALUE) {
            out.append("- The body has at most ").append(rules.wordLimit()).append(" words.\n");
        }
        rules.structureNotes().forEach(note -> out.append("- ").append(note).append('\n'));
        if (!rules.bannedPhrases().isEmpty()) {
            out.append("- Banned phrases: ")
                    .append(rules.bannedPhrases().stream()
                            .map(p -> "\"" + p + "\"")
                            .collect(Collectors.joining(", ")))
                    .append('\n');
        }

        List<String> examples = request.examples() == null ? List.of() : request.examples();
        if (!examples.isEmpty()) {
            out.append("\nEXAMPLE LETTERS (TONE ONLY — never take a fact from them)\n");
            for (int i = 0; i < examples.size(); i++) {
                out.append("--- example ")
                        .append(i + 1)
                        .append(" ---\n")
                        .append(examples.get(i).strip())
                        .append('\n');
            }
        }
        return out.toString();
    }

    private Optional<CoverLetterGuard.Draft> parse(String content, Request request) {
        JsonNode node;
        try {
            node = json.readTree(Answers.objectIn(content));
        } catch (Exception e) {
            log.warn(
                    "The cover letter draft for offer {} was not JSON: {}",
                    request.offerId(),
                    Answers.abbreviate(content));
            return Optional.empty();
        }
        String body = node.path("body").asText("").strip();
        if (body.isEmpty()) {
            log.warn("The cover letter draft for offer {} had no body", request.offerId());
            return Optional.empty();
        }
        return Optional.of(new CoverLetterGuard.Draft(
                salutation(node.path("salutation").asText(""), request.advert(), request.rules()),
                body,
                strings(node.path("skills")),
                strings(node.path("projects"))));
    }

    /**
     * A person the advert names: the honorific phrase as written ("Frau", "Frau Dr.") and the
     * one to three name words after it.
     */
    record Person(String honorific, String names) {

        /** The last name word; "Anna Stader" is greeted as Stader. */
        String surname() {
            return names.substring(names.lastIndexOf(' ') + 1);
        }

        /** Honorific and surname, the way a formal greeting names somebody: "Frau Stader". */
        String greeted() {
            return honorific + " " + surname();
        }

        /** Both forms a greeting may use for this person, compared without dots or extra spaces. */
        Set<String> acceptedForms() {
            // copyOf, not of: for a surname alone ("Frau Schmidt") the two forms are the same.
            return Set.copyOf(List.of(key(greeted()), key(honorific + " " + names)));
        }
    }

    /**
     * Every person the text names with an honorific, in the order it names them. Empty for a
     * null or blank text.
     */
    static List<Person> personsIn(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<Person> out = new ArrayList<>();
        Matcher matcher = PERSON.matcher(text);
        while (matcher.find()) {
            List<String> words =
                    new ArrayList<>(List.of(spaced(matcher.group(2)).split(" ")));
            // "Frau Anna Stader Tel. 0221 …" and "Frau Stader Personalberaterin" put a label on the
            // same line; without trimming it, the surname would be the label.
            while (words.size() > 1 && LABELS.contains(words.getLast().toLowerCase(Locale.ROOT))) {
                words.removeLast();
            }
            // More than a first name and a surname is a job title on the same line as often as it is
            // a middle name ("Frau Anna Stader Senior Consultant"); not knowing which, name nobody.
            if (words.size() > 2) {
                continue;
            }
            // "an Herrn Müller" is the same man as "Herr Müller"; greet him in the nominative.
            String honorific = spaced(matcher.group(1)).replaceFirst("^Herrn\\b", "Herr");
            out.add(new Person(honorific, String.join(" ", words)));
        }
        return out;
    }

    /**
     * The model's greeting, kept only when it greets a person the advert names and does
     * nothing else.
     *
     * <p>The advert names nobody: always {@code rules.neutralSalutation()}, whatever the model
     * wrote. It names somebody: the model's greeting when it is one line of at most
     * {@value #GREETING_WORDS} words, carries no sentence punctuation beyond the honorifics'
     * own dots, and every person it greets is a person the advert names, by honorific and
     * surname or by the full name as written; otherwise {@code rules.namedSalutation()} for the
     * first person the advert names. Gender is never guessed: the honorific is the advert's.
     */
    static String salutation(String proposed, String advert, CoverLetterStyle.Rules rules) {
        List<Person> named = personsIn(advert);
        if (named.isEmpty()) {
            return rules.neutralSalutation();
        }
        String greeting = proposed == null ? "" : proposed.strip();
        if (greetsOnly(greeting, named)) {
            return greeting;
        }
        return rules.namedSalutation().replace("{name}", named.getFirst().greeted());
    }

    private static boolean greetsOnly(String greeting, List<Person> named) {
        if (greeting.isEmpty() || greeting.contains("\n") || greeting.contains("\r")) {
            return false;
        }
        if (greeting.split("\\s+").length > GREETING_WORDS) {
            return false;
        }
        Set<String> accepted = named.stream()
                .flatMap(person -> person.acceptedForms().stream())
                .collect(Collectors.toSet());
        Matcher matcher = PERSON.matcher(greeting);
        StringBuilder rest = new StringBuilder();
        int greeted = 0;
        while (matcher.find()) {
            if (!accepted.contains(key(matcher.group(1) + " " + matcher.group(2)))) {
                return false;
            }
            greeted++;
            // The honorifics' own dots ("Dr.") are not sentence marks, so the person is cut out
            // before the rest of the line is checked.
            matcher.appendReplacement(rest, " ");
        }
        matcher.appendTail(rest);
        String remainder = rest.toString().strip();
        if (remainder.endsWith(",")) {
            remainder = remainder.substring(0, remainder.length() - 1);
        }
        return greeted > 0 && !SENTENCE_MARK.matcher(remainder).find();
    }

    private static String spaced(String text) {
        return text.strip().replaceAll("[ \\t]+", " ");
    }

    private static String key(String form) {
        return spaced(form.replace(".", " "));
    }

    private static List<String> skillsOf(SkillProfile profile) {
        if (profile == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        Stream.of(profile.core(), profile.strong(), profile.peripheral())
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .forEach(skill -> out.add(
                        skill.aliases() == null || skill.aliases().isEmpty()
                                ? skill.skill()
                                : skill.skill() + " (also written: " + String.join(", ", skill.aliases()) + ")"));
        return out;
    }

    private static List<String> strings(JsonNode array) {
        if (!array.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        array.forEach(item -> {
            String value = item.asText("").strip();
            if (!value.isEmpty()) {
                out.add(value);
            }
        });
        return out;
    }
}
