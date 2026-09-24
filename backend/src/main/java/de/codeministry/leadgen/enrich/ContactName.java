/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.enrich;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The one thing the {@code contact} field may hold: a person's name, as the advert writes it.
 *
 * <p>The configured rule reads a contact by <i>position</i> — the text in front of a phone
 * label, or a portal's contact element — and position captures whatever stands there. On the
 * collapsed page the shipped pattern runs back to the last digit, so what arrives is the tail
 * of the previous sentence with the role label in tow: ". Ansprechpartnerin Frau Meier",
 * "Monate. Wir freuen uns auf Ihre Bewerbung per". Measured on the local corpus, 0 of 165
 * enriched offers held a person. A rule cannot fix that, because the rule is the portal's
 * markup and belongs in YAML; what a name looks like does not change per portal, so it is
 * decided here, once, and deterministically.
 *
 * <p>The rule is the letter's, from {@code CoverLetterWriter}: an honorific is the advert
 * saying "here is a person", and it may stand anywhere in the capture — the name starts at it
 * and ends at the first word that is not part of a name. Without one, the capture has to be
 * nothing but a name (two to four name words, a role label in front allowed), because two
 * capitalised words in running German text are a noun pair as often as a person, and a
 * sentence tail is exactly what this class exists to refuse.
 */
final class ContactName {

    /** Above this an advert is not naming a person; it is describing one. */
    private static final int MAX_LENGTH = 80;

    private static final int MAX_NAME_WORDS = 4;

    /** One capitalised name word, a double-barrelled one included: "Meier", "Müller-Lüdenscheidt". */
    private static final Pattern NAME_WORD = Pattern.compile("\\p{Lu}\\p{Ll}+(?:-\\p{Lu}\\p{Ll}+)*");

    /** Lower-case words that sit inside a name without being one: "Anna van der Berg". */
    private static final Set<String> PARTICLES = Set.of("von", "van", "der", "den", "de", "zu", "zur", "di", "da");

    /**
     * Case-sensitive on purpose, as in the letter: "frau" in running text is not an honorific.
     * A title alone ("Dr. Meier") also says a person follows.
     */
    private static final Set<String> HONORIFICS = Set.of(
            "Herr",
            "Herrn",
            "Frau",
            "Hr.",
            "Fr.",
            "Mr",
            "Mr.",
            "Mrs",
            "Mrs.",
            "Ms",
            "Ms.",
            "Mx",
            "Dr",
            "Dr.",
            "Prof",
            "Prof.",
            "Dipl.-Ing.",
            "Dr.-Ing.",
            "Mag.",
            "Ing.");

    /** A label that introduces the person; it may precede the name and never belongs to it. */
    private static final Set<String> ROLES = Set.of(
            "ansprechpartner",
            "ansprechpartnerin",
            "kontakt",
            "kontaktperson",
            "contact",
            "recruiter",
            "recruiterin",
            "recruiting",
            "personalberater",
            "personalberaterin",
            "personalreferent",
            "personalreferentin",
            "consultant",
            "senior",
            "team",
            "bewerbung",
            "ihr",
            "ihre",
            "your");

    /** A label that follows the name: everything from here on is a number or an address. */
    private static final Set<String> CHANNELS =
            Set.of("tel", "tel.", "telefon", "fax", "mobil", "mobile", "phone", "email", "e-mail", "mail");

    /** Leading Markdown or list chrome a converted element may carry: "#### Anna Meier". */
    private static final Pattern CHROME = Pattern.compile("^[#*_>\\-\\s]+");

    /** Separators that end a name without being a sentence: "Kontakt: Anna Meier | Tel". */
    private static final Pattern SEPARATOR = Pattern.compile("[|,;:()\\[\\]\"]");

    private static final Pattern NOT_A_NAME = Pattern.compile("[0-9@/]|https?:|www\\.");

    private ContactName() {}

    /** The name in the captured value, or null when the value holds none. */
    static String of(String captured) {
        if (captured == null) {
            return null;
        }
        String text = SEPARATOR
                .matcher(CHROME.matcher(captured.strip()).replaceFirst(""))
                .replaceAll(" ");
        List<String> words = new ArrayList<>(List.of(text.strip().split("\\s+")));
        words.removeIf(String::isEmpty);
        cutAtChannel(words);
        if (words.isEmpty() || NOT_A_NAME.matcher(String.join(" ", words)).find()) {
            return null;
        }

        int honorific = indexOfHonorific(words);
        List<String> name = honorific >= 0 ? afterHonorific(words, honorific) : withoutHonorific(words);
        if (name == null) {
            return null;
        }
        String value = String.join(" ", name);
        return value.length() > MAX_LENGTH ? null : value;
    }

    private static void cutAtChannel(List<String> words) {
        for (int i = 0; i < words.size(); i++) {
            if (CHANNELS.contains(words.get(i).toLowerCase(Locale.ROOT))) {
                words.subList(i, words.size()).clear();
                return;
            }
        }
    }

    private static int indexOfHonorific(List<String> words) {
        for (int i = 0; i < words.size(); i++) {
            if (HONORIFICS.contains(words.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * From the honorific on: further honorifics, then name words up to the first word that is
     * not one. A word ending in a full stop ends the name with itself ("Frau Meier. Wir …").
     */
    private static List<String> afterHonorific(List<String> words, int from) {
        List<String> out = new ArrayList<>();
        int i = from;
        while (i < words.size() && HONORIFICS.contains(words.get(i))) {
            out.add(words.get(i++));
        }
        int names = 0;
        for (; i < words.size(); i++) {
            String word = words.get(i);
            boolean last = word.endsWith(".");
            if (last) {
                word = word.substring(0, word.length() - 1);
            }
            if (isRole(word) || !(isNameWord(word) || (names > 0 && isParticle(word)))) {
                break;
            }
            out.add(word);
            if (isNameWord(word)) {
                names++;
            }
            if (last) {
                break;
            }
        }
        dropTrailingParticles(out);
        return names >= 1 && names <= MAX_NAME_WORDS ? out : null;
    }

    /**
     * No honorific: a role label may lead, and the rest has to be a name and nothing else.
     * Two words at least — a single capitalised word is a noun, not a person.
     */
    private static List<String> withoutHonorific(List<String> words) {
        int i = 0;
        while (i < words.size() && isRole(words.get(i))) {
            i++;
        }
        List<String> out = new ArrayList<>();
        int names = 0;
        for (; i < words.size(); i++) {
            String word = words.get(i);
            if (isRole(word)) {
                return null;
            }
            if (isNameWord(word)) {
                names++;
            } else if (!(names > 0 && isParticle(word))) {
                return null;
            }
            out.add(word);
        }
        dropTrailingParticles(out);
        return names >= 2 && names <= MAX_NAME_WORDS ? out : null;
    }

    private static void dropTrailingParticles(List<String> out) {
        while (!out.isEmpty() && isParticle(out.getLast())) {
            out.removeLast();
        }
    }

    private static boolean isNameWord(String word) {
        return NAME_WORD.matcher(word).matches();
    }

    private static boolean isParticle(String word) {
        return PARTICLES.contains(word);
    }

    private static boolean isRole(String word) {
        return ROLES.contains(word.toLowerCase(Locale.ROOT));
    }
}
