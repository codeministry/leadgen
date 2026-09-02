package de.codeministry.leadgen.ingest.extract;

import de.codeministry.leadgen.config.model.SourcesConfig.Extraction;
import de.codeministry.leadgen.config.model.SourcesConfig.Extraction.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

/**
 * Splits an HTML document into blocks and reads each block's fields, driven entirely by
 * the source's `extraction` section. No selector is written down in this class, which is
 * what makes a new source a YAML block rather than a deploy.
 *
 * <p>Text is taken through jsoup's {@code text()}, so markup inside a value — a search
 * term wrapped in {@code <mark>} — disappears before any comparison sees it. The one
 * exception is the prose field, which keeps its structure as Markdown; see
 * {@link HtmlToMarkdown} and {@link #PROSE_FIELDS}.
 */
@Component
public class HtmlBlockExtractor {

    /**
     * The fields whose value is a document rather than a value, and the only ones read as
     * Markdown.
     *
     * <p>It is a name and not a rule about the markup, because the markup does not say
     * which is which: a title sits in an {@code <h3>} on this source and would come out as
     * "### Senior Java Developer", which is then the title in the shortlist, in the
     * fingerprint and in the cover letter. The eight field names are already the contract
     * between `sources.yaml` and {@link OfferMapper}, so naming one of them here adds no
     * coupling that was not there.
     */
    private static final java.util.Set<String> PROSE_FIELDS = java.util.Set.of("description");

    /** One block's fields, by config key. A list-valued field arrives as a {@code List<String>}. */
    public List<Map<String, Object>> extract(String html, Extraction extraction) {
        Document document = Jsoup.parse(html);
        List<Map<String, Object>> blocks = new ArrayList<>();

        for (Element block : document.select(extraction.blockSelector())) {
            Map<String, Object> values = new LinkedHashMap<>();
            extraction.fields().forEach((name, field) -> {
                Object value = read(block, field, PROSE_FIELDS.contains(name));
                if (value != null) {
                    values.put(name, value);
                }
            });
            blocks.add(values);
        }
        return blocks;
    }

    private static Object read(Element block, Field field, boolean prose) {
        // `ancestor` deliberately climbs out of the block: the search tags belong to the
        // group a block sits in, and copying them onto every block in the source would
        // be duplication the source does not have.
        Element scope = block;
        if (field.ancestor() != null) {
            scope = block.closest(field.ancestor());
            if (scope == null) {
                return null;
            }
        }

        List<Element> matches = field.css() == null ? List.of(scope) : scope.select(field.css());
        if (matches.isEmpty()) {
            return null;
        }

        if (field.prefix() != null) {
            return matches.stream()
                    // A meta span is one line by construction, and the prefix it is
                    // addressed by sits at the very front of it.
                    .map(element -> element.text().trim())
                    .filter(t -> t.startsWith(field.prefix()))
                    .map(t -> t.substring(field.prefix().length()).trim())
                    .filter(t -> !t.isEmpty())
                    .findFirst()
                    .orElse(null);
        }

        if (field.list()) {
            List<String> values = matches.stream()
                    .map(element -> valueOf(element, field, prose))
                    .flatMap(value -> field.split() == null
                            ? java.util.stream.Stream.of(value)
                            : Arrays.stream(value.split(Pattern.quote(field.split()))))
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .toList();
            return values.isEmpty() ? null : values;
        }

        String value = valueOf(matches.getFirst(), field, prose);
        return value.isEmpty() ? null : value;
    }

    private static String valueOf(Element element, Field field, boolean prose) {
        String value = field.attr() == null ? text(element, field, prose) : element.attr(field.attr());

        if (field.unwrapQueryParam() != null) {
            value = ProxyLink.unwrap(value, field.unwrapQueryParam());
        }
        if (field.regex() != null) {
            Matcher matcher = Pattern.compile(field.regex()).matcher(value);
            value = matcher.find() ? (matcher.groupCount() >= 1 ? matcher.group(1) : matcher.group()) : "";
        }
        return value.trim();
    }

    /**
     * A prose field keeps the source's own structure as Markdown; everything else is a
     * value and reads as one line. A pattern also forces the collapsed text: a pattern is
     * written against a line, `.` does not match a newline, and `**` around a word would
     * break it outright.
     */
    private static String text(Element element, Field field, boolean prose) {
        return prose && field.regex() == null ? HtmlToMarkdown.of(element) : element.text().trim();
    }
}
