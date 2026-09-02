/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.config;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides which configuration values must not be printed, and prints them anyway — masked.
 *
 * <p><b>The name decides, not the value.</b> A password is not recognisable by looking at
 * it, so the only workable rule is the key it sits under, and the only safe direction to be
 * wrong in is masking something harmless. `sort.key` losing its value in a log line costs
 * nothing; `LLM_API_KEY` keeping its value costs a key.
 *
 * <p><b>The mask is a fixed width.</b> Stars matching the length would publish the length,
 * which is a real hint for anything short. What the banner still has to say is whether a
 * value is <i>there</i> — an unset secret and a set one look identical otherwise, and
 * "is it set" is the whole reason to print the line.
 *
 * <p>Credentials also hide inside values, not only under keys: a JDBC or AMQP URL carries
 * {@code scheme://user:password@host}. That one is masked wherever it appears, under any key.
 */
public final class Secrets {

    /** What a masked value looks like, at a width that says nothing about the original. */
    public static final String MASK = "********";

    /** How a value that is configured nowhere is printed. Distinct from a masked one. */
    public static final String UNSET = "(not set)";

    /** How a key that is declared with no value is printed. Distinct from being absent. */
    public static final String EMPTY = "(empty)";

    private static final Set<String> SECRET_WORDS = Set.of(
            "password",
            "passwd",
            "pwd",
            "passphrase",
            "secret",
            "secrets",
            "token",
            "tokens",
            "key",
            "keys",
            "apikey",
            "credential",
            "credentials",
            "auth-token",
            "signature");

    // A segment that only ends in one of these is a secret too — `LLM_APIKEY`, `privatekey`.
    // `key` is deliberately not in this list: every word ending in it would qualify.
    private static final List<String> SECRET_SUFFIXES =
            List.of("password", "passwd", "secret", "token", "apikey", "credentials");

    private static final Pattern SEGMENT = Pattern.compile("[^A-Za-z0-9]+");

    // scheme://user:password@host — the password is group 1 and nothing else is touched.
    private static final Pattern URL_CREDENTIALS = Pattern.compile("(?<=://)([^/@:\\s]+):([^/@\\s]+)(?=@)");

    private Secrets() {}

    /** Whether a value under this key may be printed. Word-based, so `LLM_API_KEY` counts. */
    public static boolean isSecret(String name) {
        return Arrays.stream(SEGMENT.split(name.toLowerCase()))
                .anyMatch(segment -> SECRET_WORDS.contains(segment)
                        || SECRET_SUFFIXES.stream().anyMatch(segment::endsWith));
    }

    /** The value as it may appear in a log line: masked by key, and stripped of URL credentials. */
    public static String mask(String name, String value) {
        if (value == null) {
            return UNSET;
        }
        if (value.isEmpty()) {
            return EMPTY;
        }
        return isSecret(name) ? MASK : maskUrlCredentials(value);
    }

    /** Replaces the password in a {@code scheme://user:password@host} with the mask. */
    public static String maskUrlCredentials(String value) {
        Matcher matcher = URL_CREDENTIALS.matcher(value);
        return matcher.replaceAll(match -> Matcher.quoteReplacement(match.group(1) + ":" + MASK));
    }
}
