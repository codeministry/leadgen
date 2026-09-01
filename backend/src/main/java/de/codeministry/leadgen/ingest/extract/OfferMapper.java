package de.codeministry.leadgen.ingest.extract;

import de.codeministry.leadgen.config.model.SourcesConfig.Extraction;
import de.codeministry.leadgen.ingest.ExtractedOffer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Turns one block's raw field values into an offer.
 *
 * <p>The field names are the contract between `sources.yaml` and this code: whatever
 * else a source's `extraction.fields` section is called, these eight keys are what the
 * pipeline reads. A source that spells one of them differently loses that field
 * silently, which is why they are listed here and in the example config.
 */
@Slf4j
@Component
public class OfferMapper {


    public static final String TITLE = "title";
    public static final String URL = "url";
    public static final String DESCRIPTION = "description";
    public static final String LOCATION = "location";
    public static final String PORTAL = "portal";
    public static final String AGENCY = "agency";
    public static final String PUBLISHED = "published";
    public static final String TAGS = "tags";

    public ExtractedOffer map(Map<String, Object> block, Extraction extraction) {
        String title = string(block, TITLE);
        String url = string(block, URL);

        return new ExtractedOffer(
                url == null || url.isBlank() ? null : url,
                title,
                string(block, DESCRIPTION),
                url,
                string(block, LOCATION),
                string(block, PORTAL),
                string(block, AGENCY),
                date(string(block, PUBLISHED), patternFor(extraction)),
                strings(block, TAGS),
                TitleNormalizer.normalize(title));
    }

    private static String string(Map<String, Object> block, String key) {
        Object value = block.get(key);
        return value instanceof String text ? text : null;
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Map<String, Object> block, String key) {
        Object value = block.get(key);
        return value instanceof List<?> list ? (List<String>) list : List.of();
    }

    /** The field's own `format` wins; the source's `date_format` is the fallback. */
    private static String patternFor(Extraction extraction) {
        var field = extraction.fields() == null ? null : extraction.fields().get(PUBLISHED);
        return field != null && field.format() != null ? field.format() : extraction.dateFormat();
    }

    /**
     * The source states a date and a time; only the date is kept. The time carries no zone,
     * and a value like that cannot become an instant without guessing which one — the
     * freshness rule counts days, so the guess would buy nothing.
     *
     * <p>A pattern describing the whole value is parsed and the date taken from the result.
     * Cutting the raw string to the pattern's length instead only works while the two happen
     * to line up, and stops the moment a pattern contains a quoted literal.
     */
    private static LocalDate date(String raw, String pattern) {
        if (raw == null || pattern == null) {
            return null;
        }
        var formatter = DateTimeFormatter.ofPattern(pattern, Locale.GERMAN);
        try {
            return LocalDate.from(formatter.parse(raw));
        } catch (java.time.DateTimeException e) {
            // Not a knockout: an offer without a date is still an offer, and only the
            // freshness rule cares. Logged at debug so a source that changes its format
            // is findable without flooding a normal run.
            log.debug("Cannot parse '{}' as {}", raw, pattern);
            return null;
        }
    }
}
