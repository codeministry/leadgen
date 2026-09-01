package de.codeministry.leadgen.config.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * `sources.yaml`. A new source is a block in here, never a code change — which is
 * why the extraction rules are data down to the CSS selector.
 */
public record SourcesConfig(
        @Min(1) int version,
        @NotNull List<@Valid Connection> connections,
        @NotNull List<@Valid Source> sources) {

    /** Credentials arrive exclusively as `${ENV}` placeholders, never as literals. */
    public record Connection(
            @NotBlank String id,
            @NotBlank String type,
            String host,
            Integer port,
            boolean ssl,
            String username,
            String password,
            String mode,
            Duration pollInterval) {}

    public record Source(
            @NotBlank String id,
            boolean enabled,
            @NotBlank String type,
            String connection,
            String url,
            String path,
            String glob,
            Duration schedule,
            @Valid Selector selector,
            @Valid @NotNull Extraction extraction,
            Map<String, String> defaults) {}

    /**
     * Which messages of a folder belong to this source.
     *
     * @param matchAll dedicated mode: the folder holds nothing but this newsletter, so no
     *     sender or subject filter is needed. Filter mode is the other case, where the
     *     newsletter sits in a mixed folder.
     * @param state {@code uid} is the only supported value, and deliberately so. Progress
     *     must not be tracked by seen/unseen: the same mailbox is read on a phone, and a
     *     flag-based cursor would skip everything opened there first.
     */
    public record Selector(
            String folder,
            List<String> from,
            List<String> excludeFrom,
            String subjectMatches,
            Integer sinceDays,
            boolean matchAll,
            boolean markSeen,
            String state) {}

    /**
     * {@code fallback} names what happens to the fields the deterministic rules did
     * not fill. For the measured newsletter it is {@code none}: CSS covers every
     * field, so no language model is involved in extraction at all.
     */
    /**
     * @param preferPart which alternative of a multipart message to read. A newsletter is
     *     `multipart/alternative` with the plain-text version first, and that version has
     *     none of the structure the rules address.
     * @param expectCountFromSubject a regex whose first group is the number of offers the
     *     document announces. When the extracted count differs, the selectors have drifted
     *     and offers were lost — a failure that otherwise looks exactly like a quiet day.
     * @param dateFormat the fallback for a field without its own `format`.
     * @param inherit the id of another source whose extraction applies here verbatim. A
     *     mail is a mail whether it arrives over IMAP or lies in a folder; two copies of a
     *     selector table drift, and the copy nobody looks at drifts unnoticed. One level
     *     only — an inherited block that inherits again is rejected.
     */
    public record Extraction(
            // Not @NotBlank: a block that inherits states nothing but `inherit`. That the
            // strategy is present AFTER inheritance is resolved is checked in ConfigLoader,
            // which is the only place that can know.
            String strategy,
            String blockSelector,
            Map<String, Field> fields,
            String inherit,
            String preferPart,
            String dateFormat,
            String expectCountFromSubject,
            String fallback) {

        public String preferPartOrHtml() {
            return preferPart == null || preferPart.isBlank() ? "html" : preferPart;
        }

        /**
         * One field of one offer, addressed declaratively. The kinds are combinable and
         * each exists because the measured newsletter needs it:
         *
         * <ul>
         *   <li>{@code css} — the element inside the block, text by default.
         *   <li>{@code attr} — take an attribute instead of the text.
         *   <li>{@code prefix} — pick the one sibling that starts with it. Four spans in
         *       one row carry company, location, date and portal, distinguished only by
         *       an emoji. Addressing them by position breaks the day a source omits one.
         *   <li>{@code ancestor} — look outside the block. The search tags belong to the
         *       group the block sits in, not to the block.
         *   <li>{@code list} plus {@code split} — several values from one element.
         *   <li>{@code unwrapQueryParam} — the link is a tracking proxy; the real target
         *       is a parameter, and the rest of the query carries the subscriber's mail
         *       address, which must not reach the archive.
         *   <li>{@code format} — how to read a date out of this field, when the source's
         *       {@code date_format} does not describe it. The pattern covers the whole
         *       value, including a time the offer keeps and the archive drops.
         * </ul>
         */
        public record Field(
                String css,
                String attr,
                String regex,
                String path,
                String html,
                String prefix,
                String ancestor,
                boolean list,
                String split,
                String unwrapQueryParam,
                String format) {}
    }
}
