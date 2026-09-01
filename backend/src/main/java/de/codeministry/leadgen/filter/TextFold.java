package de.codeministry.leadgen.filter;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Folds free text and keywords into the one form they are compared in, and builds the
 * matchers that compare them.
 *
 * <p>Both halves exist because the reference implementation got both wrong, silently, in
 * ways that moved the survivor count by hundreds:
 *
 * <ul>
 *   <li><b>The fold has to keep the word whole.</b> Decomposing "Köln" and then dropping
 *       the combining diaeresis without removing it from the string leaves "ko ln", which
 *       matches neither "köln" nor "koln". 54 offers in Köln and Düsseldorf — the two
 *       cities nearest the home base — were discarded as out of reach.
 *   <li><b>Keywords match on word boundaries, never as substrings.</b> "ch" for
 *       Switzerland also matches Aachen and Bochum, and rejected 127 German offers as
 *       abroad. "essen" also matches Hessen, and accepted six offers from 200 km away.
 *       "ANÜ" also matches Planung, Manufacturing and manuellen.
 * </ul>
 *
 * <p>Patterns are folded with exactly the same function as the text. Comparing an
 * unfolded pattern against folded text is the third way to get this wrong: ".net" and
 * "c#" simply never matched, because the text no longer contained "." or "#".
 */
public final class TextFold {

    private static final Pattern COMBINING = Pattern.compile("\\p{M}+");
    private static final Pattern NON_WORD = Pattern.compile("[^a-z0-9%]+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private TextFold() {}

    /** Lowercase ASCII words: diacritics removed, ß to ss, everything else to spaces. */
    public static String fold(String text) {
        if (text == null) {
            return "";
        }
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFKD).toLowerCase(Locale.ROOT);
        String withoutMarks = COMBINING.matcher(decomposed).replaceAll("").replace("ß", "ss");
        return WHITESPACE.matcher(NON_WORD.matcher(withoutMarks).replaceAll(" ").trim())
                .replaceAll(" ");
    }

    /**
     * A folded keyword or phrase, matched on word boundaries. Returns null for a keyword
     * that folds away to nothing — a list entry of only punctuation would otherwise
     * compile to a pattern matching every position in every string.
     */
    public static Pattern keyword(String pattern) {
        String folded = fold(pattern);
        if (folded.isEmpty()) {
            return null;
        }
        return Pattern.compile("(?<![a-z0-9])" + Pattern.quote(folded) + "(?![a-z0-9])");
    }
}
