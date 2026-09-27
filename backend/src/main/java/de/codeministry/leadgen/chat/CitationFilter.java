/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The stream stage between the model and the screen that turns citation markers into links — or
 * refuses to.
 *
 * <p><b>A marker becomes a link only when both hold</b>: a tool of this turn returned the id
 * ({@link TurnLedger#contains}), and the row is still inside the working set or the archive (the
 * injected predicate; for an offer {@code status = 'PASSED' AND duplicate_of_id IS NULL}, archived
 * allowed). Everything else becomes {@code ⟨unverified:ID⟩}, text and never a link, because the
 * browser renders what it is given and decides nothing about grounding ({@link ChatText}).
 *
 * <p><b>Only this filter mints a {@code cite:} link.</b> The model's own text is defused before any
 * marker is resolved: a resolved link it writes itself — copied from an earlier answer, or planted
 * by an advert a tool returned — becomes {@code ⟨unverified:ID⟩}, and any other {@code cite} scheme
 * in its text (an autolink, a reference definition, an entity-encoded colon) gets a space before
 * the colon, which no Markdown renderer reads as a URL scheme. Without this the grounding check
 * would hold only for models that follow the marker instruction, and the one text that must not
 * be trusted — an advert — could write a verified pill for any id it liked.
 *
 * <p><b>A marker never leaves half.</b> The model streams in chunks that split anywhere, so from a
 * {@code [} that could open {@code [[} — or a numbered link — the text is held until it closes or
 * proves not to be one; text before it goes out at once. A hold that grows past {@link
 * #LONGEST_MARKER} without closing is no marker, and a tail still held when the stream ends is
 * flushed as plain text.
 *
 * <p>One per turn, fed from one stream; not thread-safe.
 */
public class CitationFilter {

    /** Longer than any {@code [[application:ID]]} a long id makes; past this, it is prose. */
    static final int LONGEST_MARKER = 48;

    private static final String OPEN = "[[";
    private static final String CLOSE = "]]";
    private static final Pattern MARKER =
            Pattern.compile("\\s*(offer|application)\\s*:\\s*(\\d{1,18})\\s*", Pattern.CASE_INSENSITIVE);

    /** A resolved link as this filter writes it, and the few spellings Markdown reads the same. */
    private static final Pattern LINK = Pattern.compile(
            "\\[\\d{1,4}]\\([ \\t]{0,4}<?cite:(offer|application)/(\\d{1,18})>?[ \\t]{0,4}\\)",
            Pattern.CASE_INSENSITIVE);

    /** What turns stored answers back into markers before the model reads them again. */
    private static final Pattern STORED_LINK = Pattern.compile("\\[\\d+]\\(cite:(offer|application)/(\\d+)\\)");

    private static final Pattern STORED_UNVERIFIED = Pattern.compile("⟨unverified:(\\d+)⟩");

    /** The scheme; followed by one of {@link #SCHEME_ENDS} it is defused. */
    private static final String SCHEME = "cite";

    /** A colon, and the two ways Markdown lets a colon be written without writing one. */
    private static final String SCHEME_ENDS = ":&\\";

    private final TurnLedger ledger;
    private final BiPredicate<ChatSourceKind, Long> reachable;

    /** The model's text not yet defused, because it may still become a link or a scheme. */
    private final StringBuilder raw = new StringBuilder();

    /** Defused text not yet resolved, because it may still become a marker. */
    private final StringBuilder held = new StringBuilder();

    /** What {@link #reachable} answered for each row this turn; see {@link #reachable(TurnLedger.Ref)}. */
    private final Map<TurnLedger.Ref, Boolean> reachability = new HashMap<>();

    /** The last character the defusing stage let through, to tell {@code (cite:} from {@code recite:}. */
    private char before = ' ';

    /**
     * @param ledger    this turn's ledger; resolved citations take their number from it.
     * @param reachable whether a row is inside the working set or the archive right now.
     */
    public CitationFilter(TurnLedger ledger, BiPredicate<ChatSourceKind, Long> reachable) {
        this.ledger = ledger;
        this.reachable = reachable;
    }

    /**
     * Feeds the next chunk.
     *
     * @return the text that may be shown now — possibly empty while a marker is still open.
     */
    public String accept(String chunk) {
        raw.append(chunk);
        return resolveMarkers(defuse(false));
    }

    /** Ends the stream: whatever is still held was never a marker and goes out as it came. */
    public String finish() {
        String resolved = resolveMarkers(defuse(true));
        String tail = held.toString();
        held.setLength(0);
        return resolved + tail;
    }

    /**
     * An answer as it was stored — links resolved, unverified ids marked — written back in the
     * marker format the model is told to use, so its own history never shows it a {@code cite:}
     * link to copy, and an id that was unverified goes back as the bare number it was.
     */
    static String asMarkers(String answer) {
        String markers = STORED_LINK.matcher(answer).replaceAll("[[$1:$2]]");
        return STORED_UNVERIFIED.matcher(markers).replaceAll("$1");
    }

    /**
     * Takes from {@link #raw} what can no longer become a model-written link or scheme, defused.
     *
     * @param all true at the end of the stream, when nothing can grow any more
     */
    private String defuse(boolean all) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < raw.length()) {
            char c = raw.charAt(i);
            if (c == '[') {
                Matcher link = LINK.matcher(raw).region(i, raw.length());
                if (link.lookingAt()) {
                    out.append("⟨unverified:").append(link.group(2)).append('⟩');
                    before = '⟩';
                    i = link.end();
                    continue;
                }
                if (link.hitEnd() && !all) {
                    break;
                }
            }
            if (!Character.isLetterOrDigit(before) && startsScheme(i)) {
                int rest = raw.length() - i;
                if (rest <= SCHEME.length()) {
                    if (!all) {
                        break;
                    }
                } else if (SCHEME_ENDS.indexOf(raw.charAt(i + SCHEME.length())) >= 0) {
                    out.append(raw, i, i + SCHEME.length()).append(' ');
                    before = ' ';
                    i += SCHEME.length();
                    continue;
                }
            }
            out.append(c);
            before = c;
            i++;
        }
        raw.delete(0, i);
        return out.toString();
    }

    /** Whether the text from {@code i} is {@code cite}, or as much of it as there is. */
    private boolean startsScheme(int i) {
        int length = Math.min(SCHEME.length(), raw.length() - i);
        return raw.substring(i, i + length).equalsIgnoreCase(SCHEME.substring(0, length));
    }

    private String resolveMarkers(String chunk) {
        held.append(chunk);
        StringBuilder out = new StringBuilder();
        while (!held.isEmpty()) {
            int open = held.indexOf(OPEN);
            if (open < 0) {
                // A lone trailing `[` may be the first half of `[[`; everything else can go.
                int keep = held.charAt(held.length() - 1) == '[' ? 1 : 0;
                out.append(held, 0, held.length() - keep);
                held.delete(0, held.length() - keep);
                break;
            }
            out.append(held, 0, open);
            held.delete(0, open);
            int close = held.indexOf(CLOSE, OPEN.length());
            if (close < 0) {
                if (held.length() > LONGEST_MARKER) {
                    // No marker closes this far out: let the brackets go as text and look again after them.
                    out.append(held, 0, OPEN.length());
                    held.delete(0, OPEN.length());
                    continue;
                }
                break;
            }
            Matcher marker = MARKER.matcher(held.substring(OPEN.length(), close));
            if (!marker.matches()) {
                // A stray `[[` — a shell test, a half-typed wiki link — is paired here with the `]]`
                // of whatever marker comes next. Sending the whole span out as text took that real
                // marker with it, so only the first bracket goes, and the search starts again right
                // after it: a later `[[` in the span, or a third bracket, can still open a marker.
                out.append(held.charAt(0));
                held.deleteCharAt(0);
                continue;
            }
            out.append(resolve(marker));
            held.delete(0, close + CLOSE.length());
        }
        return out.toString();
    }

    /** A marker's link, or its id marked unverified; {@code matcher} has matched {@link #MARKER}. */
    private String resolve(Matcher matcher) {
        ChatSourceKind kind = ChatSourceKind.valueOf(matcher.group(1).toUpperCase(Locale.ROOT));
        long id = Long.parseLong(matcher.group(2));
        if (!ledger.contains(kind, id) || !reachable(new TurnLedger.Ref(kind, id))) {
            return "⟨unverified:" + id + "⟩";
        }
        int n = ledger.cite(kind, id);
        return "[" + n + "](cite:" + kind.name().toLowerCase(Locale.ROOT) + "/" + id + ")";
    }

    /**
     * The predicate's answer for a row, asked once per turn. The predicate is a database round-trip,
     * and a model that cites one offer in every paragraph asked it again for every marker; a row
     * does not leave the working set in the seconds a turn streams, and if it did, the first answer
     * is the one the turn's citation number already stands for.
     */
    private boolean reachable(TurnLedger.Ref row) {
        return reachability.computeIfAbsent(row, key -> reachable.test(key.kind(), key.id()));
    }
}
