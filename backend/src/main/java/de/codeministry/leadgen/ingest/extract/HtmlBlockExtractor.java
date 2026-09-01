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
 * term wrapped in {@code <mark>}, a line break in a description — disappears before any
 * comparison sees it.
 */
@Component
public class HtmlBlockExtractor {

    /** One block's fields, by config key. A list-valued field arrives as a {@code List<String>}. */
    public List<Map<String, Object>> extract(String html, Extraction extraction) {
        Document document = Jsoup.parse(html);
        List<Map<String, Object>> blocks = new ArrayList<>();

        for (Element block : document.select(extraction.blockSelector())) {
            Map<String, Object> values = new LinkedHashMap<>();
            extraction.fields().forEach((name, field) -> {
                Object value = read(block, field);
                if (value != null) {
                    values.put(name, value);
                }
            });
            blocks.add(values);
        }
        return blocks;
    }

    private static Object read(Element block, Field field) {
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
                    .map(HtmlBlockExtractor::text)
                    .filter(t -> t.startsWith(field.prefix()))
                    .map(t -> t.substring(field.prefix().length()).trim())
                    .filter(t -> !t.isEmpty())
                    .findFirst()
                    .orElse(null);
        }

        if (field.list()) {
            List<String> values = matches.stream()
                    .map(element -> valueOf(element, field))
                    .flatMap(value -> field.split() == null
                            ? java.util.stream.Stream.of(value)
                            : Arrays.stream(value.split(Pattern.quote(field.split()))))
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .toList();
            return values.isEmpty() ? null : values;
        }

        String value = valueOf(matches.getFirst(), field);
        return value.isEmpty() ? null : value;
    }

    private static String valueOf(Element element, Field field) {
        String value = field.attr() == null ? text(element) : element.attr(field.attr());

        if (field.unwrapQueryParam() != null) {
            value = ProxyLink.unwrap(value, field.unwrapQueryParam());
        }
        if (field.regex() != null) {
            Matcher matcher = Pattern.compile(field.regex()).matcher(value);
            value = matcher.find() ? (matcher.groupCount() >= 1 ? matcher.group(1) : matcher.group()) : "";
        }
        return value.trim();
    }

    private static String text(Element element) {
        return element.text().trim();
    }
}
