package de.codeministry.leadgen.digest;

import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.config.model.MatchingRules;
import de.codeministry.leadgen.config.model.PipelineConfig;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * The morning's one page, rendered to a file.
 *
 * <p><b>There is no transport here, and there is not going to be one.</b> The digest is a
 * file: text or HTML, written to a directory, read by a human who then decides. The
 * configuration models no channel and no recipient either, because a schema for something
 * the tool must not do is an invitation to write the code — the same rule the application
 * packages follow.
 *
 * <p>An unscored offer is listed under its own heading rather than sorted to the bottom of
 * the shortlist. Without a language model there is no ranking to trust, and pretending
 * there is one is worse than saying so.
 */
@Slf4j
@Service
public class DigestService {

    private static final String OFFERS =
            """
            SELECT o.id, o.title, o.location, o.portal, o.agency, o.url, o.rate_eur,
                   o.duration, o.score_value, o.score_band, o.enrichment_note
            FROM offer o
            WHERE o.status = 'PASSED' AND o.duplicate_of_id IS NULL AND o.score_band = ?
            ORDER BY o.score_value DESC NULLS LAST, o.id
            """;

    private static final String REASONS =
            "SELECT label, points FROM offer_score_reason WHERE offer_id = ? ORDER BY position";

    private final ConfigRegistry config;
    private final JdbcClient jdbc;

    DigestService(ConfigRegistry config, DataSource dataSource) {
        this.config = config;
        this.jdbc = JdbcClient.create(dataSource);
    }

    /** @return the file written, or empty when the digest is switched off. */
    public java.util.Optional<Path> render(LocalDate day) {
        PipelineConfig.Digest settings = config.snapshot().application().digest();
        if (settings == null || !settings.enabled()) {
            log.info("The digest is disabled; nothing is written");
            return java.util.Optional.empty();
        }

        MatchingRules.Scoring scoring = config.snapshot().rules().scoring();
        List<String> include = settings.include() == null ? List.of() : settings.include();
        boolean html = "html".equalsIgnoreCase(settings.format());

        List<Section> sections = new java.util.ArrayList<>();
        if (include.contains("shortlisted")) {
            sections.add(new Section("Shortlisted", "at or above %d".formatted(scoring.thresholds().autoShortlist()),
                    offers("SHORTLISTED")));
        }
        if (include.contains("review")) {
            sections.add(new Section("For review", "between %d and %d"
                    .formatted(scoring.thresholds().review(), scoring.thresholds().autoShortlist() - 1),
                    offers("REVIEW")));
        }
        // Always, and not behind a flag: an unscored offer is invisible in a digest that
        // only knows bands, and invisible is exactly what it must not be.
        List<Offer> unscored = offers("UNSCORED");
        if (!unscored.isEmpty()) {
            sections.add(new Section("Unscored", "no language model was configured", unscored));
        }

        Path file = write(settings, day, html ? renderHtml(day, sections) : renderText(day, sections));
        log.info("Digest written to {} ({} offers across {} sections)",
                file, sections.stream().mapToInt(s -> s.offers().size()).sum(), sections.size());
        return java.util.Optional.of(file);
    }

    private Path write(PipelineConfig.Digest settings, LocalDate day, String content) {
        try {
            Path directory = Path.of(settings.outputDir());
            Files.createDirectories(directory);
            Path file = directory.resolve("digest-%s.%s"
                    .formatted(day, "html".equalsIgnoreCase(settings.format()) ? "html" : "txt"));
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<Offer> offers(String band) {
        return jdbc.sql(OFFERS)
                .param(band)
                .query((rs, row) -> new Offer(
                        rs.getLong("id"),
                        rs.getString("title"),
                        rs.getString("location"),
                        rs.getString("portal"),
                        rs.getString("agency"),
                        rs.getString("url"),
                        rs.getObject("rate_eur", BigDecimal.class),
                        rs.getString("duration"),
                        rs.getObject("score_value", Integer.class),
                        rs.getString("enrichment_note") != null))
                .list();
    }

    private List<Reason> reasons(long offerId) {
        return jdbc.sql(REASONS)
                .param(offerId)
                .query((rs, row) -> new Reason(rs.getString("label"), rs.getInt("points")))
                .list();
    }

    private String renderText(LocalDate day, List<Section> sections) {
        StringBuilder out = new StringBuilder();
        out.append("Lead Generation, ").append(day).append('\n');
        out.append("=".repeat(60)).append("\n\n");

        for (Section section : sections) {
            out.append(section.title()).append(" (").append(section.note()).append(") — ")
                    .append(section.offers().size()).append("\n")
                    .append("-".repeat(60)).append('\n');
            if (section.offers().isEmpty()) {
                out.append("  nothing\n");
            }
            for (Offer offer : section.offers()) {
                out.append("  ").append(offer.score() == null ? " — " : "%3d".formatted(offer.score()))
                        .append("  ").append(offer.title()).append('\n');
                out.append("       ").append(meta(offer)).append('\n');
                for (Reason reason : reasons(offer.id())) {
                    out.append("       %+d  %s%n".formatted(reason.points(), reason.label()));
                }
                out.append("       ").append(offer.url()).append("\n\n");
            }
            out.append('\n');
        }
        out.append("Nothing here has been sent. This is a file.\n");
        return out.toString();
    }

    private String renderHtml(LocalDate day, List<Section> sections) {
        StringBuilder out = new StringBuilder();
        out.append("<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">")
                .append("<title>Lead Generation, ").append(day).append("</title>")
                .append("<style>body{font-family:system-ui,sans-serif;max-width:52rem;margin:2rem auto;padding:0 1rem}")
                .append("h2{margin-top:2rem}article{border-top:1px solid #ddd;padding:.75rem 0}")
                .append(".score{font-variant-numeric:tabular-nums;font-weight:600}")
                .append(".meta,.reason{color:#555;font-size:.9rem}</style></head><body>\n")
                .append("<h1>Lead Generation, ").append(day).append("</h1>\n");

        for (Section section : sections) {
            out.append("<h2>").append(escape(section.title())).append(" <small>(")
                    .append(escape(section.note())).append(", ").append(section.offers().size())
                    .append(")</small></h2>\n");
            if (section.offers().isEmpty()) {
                out.append("<p>nothing</p>\n");
            }
            for (Offer offer : section.offers()) {
                out.append("<article><p><span class=\"score\">")
                        .append(offer.score() == null ? "&mdash;" : offer.score())
                        .append("</span> <a href=\"").append(escape(offer.url())).append("\">")
                        .append(escape(offer.title())).append("</a></p>\n")
                        .append("<p class=\"meta\">").append(escape(meta(offer))).append("</p>\n<ul>");
                for (Reason reason : reasons(offer.id())) {
                    out.append("<li class=\"reason\">%+d %s</li>".formatted(reason.points(), escape(reason.label())));
                }
                out.append("</ul></article>\n");
            }
        }
        out.append("<p><em>Nothing here has been sent. This is a file.</em></p>\n</body></html>\n");
        return out.toString();
    }

    private static String meta(Offer offer) {
        List<String> parts = new java.util.ArrayList<>();
        parts.add(offer.rate() == null ? "rate unknown" : offer.rate() + " €/h");
        parts.add(offer.location() == null ? "location unknown" : offer.location());
        parts.add(offer.duration() == null ? "duration unknown" : offer.duration());
        parts.add(offer.agency() == null ? "no agency stated" : offer.agency());
        if (offer.portal() != null) {
            parts.add(offer.portal());
        }
        if (offer.incomplete()) {
            parts.add("incomplete");
        }
        return String.join(" · ", parts);
    }

    private static String escape(String value) {
        return value == null
                ? ""
                : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private record Section(String title, String note, List<Offer> offers) {}

    private record Offer(
            long id,
            String title,
            String location,
            String portal,
            String agency,
            String url,
            BigDecimal rate,
            String duration,
            Integer score,
            boolean incomplete) {}

    private record Reason(String label, int points) {}
}
