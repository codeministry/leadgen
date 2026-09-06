/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.content;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A block's identity, so the same paragraph is decided once.
 *
 * <p><b>Deliberately not {@code filter/TextFold}.</b> That one folds umlauts and strips
 * accents because it exists to make a pattern <em>match</em> a text; this exists to make two
 * texts the <em>same</em>. Folding here would make "Datenschutz" and "Datenschütz" one row,
 * which is a decision nobody asked for and cannot be undone once the cache is full.
 *
 * <p>Link targets are dropped and link text kept: a portal that appends a session token or a
 * tracking parameter to every link would otherwise give the same boilerplate a fresh digest
 * on every fetch, and the cache would never hit. Changing what a link <em>says</em> does move
 * the digest, because that is a change to the words on the page.
 */
public final class BlockDigest {

    /**
     * {@code [text](target)} and {@code [text][ref]} — the text survives, the target does not.
     */
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]*)\\]\\((?:[^()]|\\([^()]*\\))*\\)");

    private static final Pattern REFERENCE_LINK = Pattern.compile("\\[([^\\]]*)\\]\\[[^\\]]*\\]");

    /**
     * Backslash escapes, which flexmark emits inconsistently around punctuation.
     */
    private static final Pattern ESCAPE = Pattern.compile("\\\\([\\p{Punct}])");

    /**
     * Leading structure: heading hashes, list bullets, block quotes.
     */
    private static final Pattern LEADING_MARKER = Pattern.compile("(?m)^ {0,3}(?:#{1,6}|[-*+]|\\d{1,9}[.)]|>)\\s+");

    /**
     * Emphasis runs, which differ between two renderings of the same sentence.
     */
    private static final Pattern EMPHASIS = Pattern.compile("[*_`~]+");

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /**
     * How much of a block is kept beside its digest, so a row can be read by a person.
     */
    public static final int SAMPLE_LENGTH = 200;

    private BlockDigest() {
    }

    /**
     * 32 hex characters of SHA-256 over the normalised block. Truncated because this is a
     * cache key rather than a signature: 128 bits is far past the point where a collision
     * across a few hundred thousand paragraphs is worth reasoning about, and a shorter key
     * is one a person can compare by eye in a query result.
     */
    public static String of(String block) {
        byte[] hash = sha256(normalise(block).getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash).substring(0, 32);
    }

    /**
     * The text as it is compared. Public because a test that cannot see this can only assert
     * that two digests differ, never why.
     */
    public static String normalise(String block) {
        if (block == null) {
            return "";
        }
        String text = LINK.matcher(block).replaceAll("$1");
        text = REFERENCE_LINK.matcher(text).replaceAll("$1");
        text = ESCAPE.matcher(text).replaceAll("$1");
        text = LEADING_MARKER.matcher(text).replaceAll("");
        text = EMPHASIS.matcher(text).replaceAll("");
        text = WHITESPACE.matcher(text).replaceAll(" ");
        return text.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * The head of a block, for the cache row and for the classifier's prompt. Both want the
     * same thing: enough to recognise the paragraph, not enough to pay for it twice.
     */
    public static String sample(String block) {
        String text = block == null ? "" : block.strip();
        return text.length() <= SAMPLE_LENGTH ? text : text.substring(0, SAMPLE_LENGTH) + "…";
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            // Required of every Java platform. If it is genuinely absent, nothing below can work.
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
