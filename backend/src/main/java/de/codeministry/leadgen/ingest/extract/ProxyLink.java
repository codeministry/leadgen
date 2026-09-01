package de.codeministry.leadgen.ingest.extract;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

/**
 * Unwraps a tracking link and throws the rest of the query away.
 *
 * <p>This is a privacy boundary, not a convenience. The newsletter's links carry the
 * subscriber's mail address as a query parameter, so anything derived from them —
 * database rows, archived offers, an exported package — would carry it too. The
 * address must not leave the machine, and the cheapest way to guarantee that is to
 * never let it past the extractor.
 */
public final class ProxyLink {

    private ProxyLink() {}

    /**
     * Returns the value of {@code parameter} if the URL carries it, otherwise the URL
     * with its query removed. Never the original query — an unrecognised wrapper is
     * exactly the case where something unwanted would slip through.
     */
    public static String unwrap(String url, String parameter) {
        if (url == null || url.isBlank()) {
            return "";
        }
        if (parameter == null || parameter.isBlank()) {
            return url;
        }
        String query;
        try {
            query = URI.create(url).getRawQuery();
        } catch (IllegalArgumentException e) {
            return stripQuery(url);
        }
        if (query == null) {
            return url;
        }
        Optional<String> target = Arrays.stream(query.split("&"))
                .map(pair -> pair.split("=", 2))
                .filter(pair -> pair.length == 2 && pair[0].equals(parameter))
                .map(pair -> URLDecoder.decode(pair[1], StandardCharsets.UTF_8))
                .findFirst();

        return target.orElseGet(() -> stripQuery(url));
    }

    private static String stripQuery(String url) {
        int question = url.indexOf('?');
        return question < 0 ? url : url.substring(0, question);
    }
}
