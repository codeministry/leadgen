package de.codeministry.leadgen.enrich;

import de.codeministry.leadgen.config.model.PipelineConfig.Enrichment.Extract;
import de.codeministry.leadgen.ingest.extract.HtmlToMarkdown;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Reads the configured fields out of an ad page.
 *
 * <p><b>No selector and no pattern is written here.</b> Every portal renders an ad
 * differently, so a new one has to be a YAML block and not a release — the same invariant
 * that governs `sources.yaml`, for the same reason.
 *
 * <p>The seven field names are the contract with the enrichment stage: {@code rate},
 * {@code duration}, {@code workload}, {@code remote_percent}, {@code start_date},
 * {@code contact}, {@code full_text}. A field spelled differently in the configuration is
 * read and then ignored, in silence, which is why they are listed here as well.
 */
@Slf4j
public class AdExtractor {

    private static final DateTimeFormatter GERMAN_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final Map<String, Extract.Field> fields;
    private final Map<String, Pattern> compiled;

    public AdExtractor(Extract extract) {
        this.fields = extract == null || extract.fields() == null ? Map.of() : extract.fields();
        Map<String, Pattern> patterns = new java.util.HashMap<>();
        this.fields.forEach((name, rule) -> {
            if (rule.regex() != null) {
                Pattern pattern = compile(name, rule.regex());
                if (pattern != null) {
                    patterns.put(name, pattern);
                }
            }
        });
        this.compiled = Map.copyOf(patterns);
    }

    /**
     * The page's own address is passed in so a relative link inside the ad resolves to the
     * portal rather than to this application. Without it a converted `[Argo CD](/projects/
     * argo-cd)` is a link into our own router, which answers with the shortlist.
     */
    public Enrichment extract(String html, String baseUri) {
        Document document = Jsoup.parse(html, baseUri == null ? "" : baseUri);
        String text = document.text();

        return new Enrichment(
                decimal(value("rate", document, text)),
                value("duration", document, text),
                value("workload", document, text),
                integer(value("remote_percent", document, text)),
                date(value("start_date", document, text)),
                trimmed(value("contact", document, text)),
                value("full_text", document, text),
                null);
    }

    /**
     * `full_text` is the one field here whose value is a document rather than a value, and
     * the only one read as Markdown. Named rather than inferred from the markup: a portal
     * that wraps its contact line in an `<h4>` would otherwise hand over "#### Leonardo
     * Ladu" as the contact. A pattern also forces the collapsed text, because a pattern is
     * written against a line.
     */
    private static String text(String field, Element element, Extract.Field rule) {
        return "full_text".equals(field) && rule.regex() == null
                ? HtmlToMarkdown.of(element)
                : element.text();
    }

    /**
     * A field's {@code css} narrows the search before its {@code regex} runs, and stands
     * on its own when there is no regex. That is what lets `full_text` be a selector and
     * `rate` a pattern without two mechanisms.
     */
    private String value(String field, Document document, String wholeText) {
        Extract.Field rule = fields.get(field);
        if (rule == null) {
            return null;
        }

        String scope = wholeText;
        if (rule.css() != null && !rule.css().isBlank()) {
            Element element = document.selectFirst(rule.css());
            if (element == null) {
                return null;
            }
            // The same split as in the ingest extractor: a pattern reads a line, a field
            // reads a document. `full_text` is the whole reason the detail page has
            // paragraphs at all.
            scope = rule.attr() == null ? text(field, element, rule) : element.attr(rule.attr());
        }

        Pattern pattern = compiled.get(field);
        if (pattern == null) {
            return scope.isBlank() ? null : scope;
        }

        Matcher matcher = pattern.matcher(scope);
        if (!matcher.find()) {
            return null;
        }
        int group = Math.min(rule.groupOrFirst(), matcher.groupCount());
        return group < 1 ? matcher.group() : matcher.group(group);
    }

    private static Pattern compile(String field, String regex) {
        try {
            return Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException e) {
            // Loud and not fatal: one broken pattern must not stop the six that work,
            // and the stage is allowed to yield less than everything.
            log.error("enrichment.extract.fields.{}.regex does not compile: {}", field, e.getMessage());
            return null;
        }
    }

    private static BigDecimal decimal(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return new BigDecimal(raw.replace(',', '.').trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer integer(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDate date(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim(), GERMAN_DATE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String trimmed(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        return value.isEmpty() ? null : value;
    }
}
