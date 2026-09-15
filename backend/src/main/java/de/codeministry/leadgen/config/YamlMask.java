/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.config;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Configuration file text, with the values that must not be published replaced.
 *
 * <p>This exists because a source's block is shown in a browser, on an endpoint that stands
 * behind nothing — {@code security.auth} has one implemented value and the container binds off
 * loopback — and because the second configuration layer explicitly allows a personal
 * {@code config/sources.yaml} with literal credentials in it rather than {@code ${VARIABLES}}.
 *
 * <p><b>{@link Secrets} decides which keys are secret, and that is deliberate.</b> A second
 * rule for the same question is the second implementation that drifts, and its argument holds
 * here unchanged: the name decides, because a password is not recognisable by looking at it.
 * What this class adds is everything that only matters when the subject is a <i>file</i>
 * rather than a log line.
 *
 * <h2>The four rules a log line does not need</h2>
 *
 * <p><b>Only {@link Secrets#MASK}, never {@code UNSET} or {@code EMPTY}.</b> Those two are
 * renderings of a resolved value, and in a file view "not set" does not exist: an absent key is
 * an absent line, and {@code password:} with nothing after it is meaningful exactly as written.
 * <b>A file view must not contain text the file does not contain.</b>
 *
 * <p><b>A bare placeholder survives; a defaulted one does not.</b> {@code ${IMAP_PASSWORD}}
 * renders verbatim, because the <i>name</i> of an environment variable is not a secret and
 * "which variable do I have to set" is half the reason to open the panel at all.
 * {@code ${IMAP_PASSWORD:hunter2}} has a literal inside it, so everything past the colon goes.
 *
 * <p><b>Flow mappings and block scalars are covered.</b> A line-and-colon masker misses
 * {@code defaults: { token: abc }} entirely, and the shipped file's whole {@code fields:}
 * section is flow mappings. A {@code password: >} followed by an indented run needs the run.
 *
 * <p><b>The view masks more than the banner does.</b> {@code username} is not a secret by
 * {@link Secrets}' rule and should not become one: the banner's job is "is it set" on the
 * operator's own terminal, where a mailbox address is the useful half of the line. In a browser
 * tab it is a personal datum, so {@link #VIEW_MASKED} is added here and nowhere else.
 *
 * <p><b>What this does not protect.</b> Masking covers credentials, not identity. A real
 * block still names the portal somebody subscribes to, the folder they file it under and the
 * paths on their machine, and no rule keyed on names can decide that {@code INBOX/Jobs} is
 * private while {@code INBOX} is not. That is handled by which configuration a published
 * screenshot is taken against, not here.
 */
public final class YamlMask {

    /**
     * Masked in a file view although the banner prints them.
     *
     * <p>Kept apart from {@link Secrets} rather than added to it, because the two consumers
     * want different answers about the same key and the difference is the whole point: a
     * terminal the operator is already looking at, against a page anyone on the network can
     * fetch and anyone at all can paste into an issue.
     */
    static final Set<String> VIEW_MASKED = Set.of("username", "user", "email", "mail", "login");

    /**
     * {@code   key: value}, with the leading dash of a sequence item folded into the indent.
     */
    private static final Pattern ENTRY = Pattern.compile("^(\\s*(?:-\\s+)?)([A-Za-z0-9_.\\-]+):(\\s*)(.*)$");

    /**
     * A whole placeholder and nothing else, with an optional default after the first colon.
     */
    private static final Pattern PLACEHOLDER = Pattern.compile("^\\$\\{([^:}]+)(:([^}]*))?}$");

    /**
     * {@code key: value} inside a flow mapping, where the value ends at a comma or the brace.
     */
    private static final Pattern FLOW_ENTRY = Pattern.compile("([A-Za-z0-9_.\\-]+)(\\s*:\\s*)([^,}]*)");

    /**
     * A query parameter, so a portal's saved-search URL cannot smuggle a token past the
     * key-name rule one level down.
     */
    private static final Pattern QUERY_PARAM = Pattern.compile("([?&])([A-Za-z0-9_.\\-]+)=([^&\\s\"']*)");

    private YamlMask() {
    }

    /**
     * The text as it may be published.
     */
    public static String apply(String yaml) {
        List<String> lines = yaml.lines().toList();
        StringBuilder out = new StringBuilder();
        // How far a block scalar under a secret key reaches. Everything indented past the key
        // belongs to that value, so the run is masked until the indentation comes back.
        int scalarIndent = -1;

        for (String line : lines) {
            if (scalarIndent >= 0) {
                int indent = indentOf(line);
                if (line.isBlank() || indent > scalarIndent) {
                    out.append(maskedScalarLine(line, scalarIndent)).append('\n');
                    continue;
                }
                scalarIndent = -1;
            }
            Matcher entry = ENTRY.matcher(line);
            if (!entry.matches()) {
                out.append(line).append('\n');
                continue;
            }
            String indent = entry.group(1);
            String key = entry.group(2);
            String gap = entry.group(3);
            String value = entry.group(4);

            if (secret(key)) {
                // A key with nothing after it is meaningful as written, and there is nothing
                // to hide: the line is passed through byte for byte rather than rebuilt,
                // because rebuilding it would normalise the gap and add a space the file does
                // not have. The same rule as `(not set)`, one notch finer.
                if (value.isEmpty() || isBlockScalarHeader(value)) {
                    scalarIndent = isBlockScalarHeader(value) ? indent.length() : scalarIndent;
                    out.append(line).append('\n');
                    continue;
                }
                out.append(indent).append(key).append(':').append(gap)
                    .append(maskedValue(value)).append('\n');
                continue;
            }
            out.append(indent).append(key).append(':').append(gap)
                .append(maskedNonSecret(value)).append('\n');
        }
        return out.toString();
    }

    private static boolean secret(String key) {
        return Secrets.isSecret(key) || VIEW_MASKED.contains(key.toLowerCase());
    }

    /**
     * A value under a secret key. The placeholder rule lives here, and a trailing comment is
     * kept only when the value carries no quote — splitting one out of a quoted scalar needs a
     * parser, and dropping a comment is the safe direction to be wrong in.
     */
    private static String maskedValue(String value) {
        if (value.isEmpty()) {
            return "";
        }
        String body = value;
        String comment = "";
        if (!value.contains("\"") && !value.contains("'")) {
            int hash = value.indexOf(" #");
            if (hash >= 0) {
                body = value.substring(0, hash);
                comment = value.substring(hash);
            }
        }
        String trimmed = body.strip();
        if (trimmed.startsWith("{")) {
            return maskFlow(body) + comment;
        }
        Matcher placeholder = PLACEHOLDER.matcher(trimmed);
        if (placeholder.matches()) {
            // The variable's name is not a secret; a default written beside it is.
            return placeholder.group(2) == null
                ? body + comment
                : "${" + placeholder.group(1) + ":" + Secrets.MASK + "}" + comment;
        }
        return Secrets.MASK + comment;
    }

    /**
     * A value under a key nobody calls secret. Two things can still hide in it: a password
     * inside a URL, which {@link Secrets} already knows how to find, and a secret key inside a
     * flow mapping, which only exists in a file.
     */
    private static String maskedNonSecret(String value) {
        String flow = value.strip().startsWith("{") ? maskFlow(value) : value;
        return maskQueryParams(Secrets.maskUrlCredentials(flow));
    }

    private static String maskFlow(String value) {
        Matcher matcher = FLOW_ENTRY.matcher(value);
        return matcher.replaceAll(match -> Matcher.quoteReplacement(
            secret(match.group(1))
                ? match.group(1) + match.group(2) + Secrets.MASK
                : match.group(1) + match.group(2) + match.group(3)));
    }

    /**
     * The same "the name decides" rule, one level down into a URL's query. A portal's saved
     * search routinely carries a token in one, and the key it sits under is {@code url}.
     */
    private static String maskQueryParams(String value) {
        Matcher matcher = QUERY_PARAM.matcher(value);
        return matcher.replaceAll(match -> Matcher.quoteReplacement(
            secret(match.group(2))
                ? match.group(1) + match.group(2) + "=" + Secrets.MASK
                : match.group()));
    }

    private static boolean isBlockScalarHeader(String value) {
        String trimmed = value.strip();
        return trimmed.startsWith(">") || trimmed.startsWith("|");
    }

    /**
     * One line of a block scalar under a secret key. The indentation is kept so the document
     * still parses; everything on it is the value.
     */
    private static String maskedScalarLine(String line, int keyIndent) {
        if (line.isBlank()) {
            return line;
        }
        return " ".repeat(Math.max(keyIndent + 2, indentOf(line))) + Secrets.MASK;
    }

    private static int indentOf(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') {
            i++;
        }
        return i;
    }
}
