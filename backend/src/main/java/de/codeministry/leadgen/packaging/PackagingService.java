package de.codeministry.leadgen.packaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.codeministry.leadgen.application.ApplicationService;
import de.codeministry.leadgen.application.ApplicationStatus;
import de.codeministry.leadgen.config.ConfigProperties;
import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.config.ConfigSnapshot;
import de.codeministry.leadgen.config.ConfigSource;
import de.codeministry.leadgen.config.Directories;
import de.codeministry.leadgen.config.model.PipelineConfig;
import de.codeministry.leadgen.config.model.SkillProfile;
import de.codeministry.leadgen.filter.TextFold;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import freemarker.template.TemplateExceptionHandler;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assembles the folder an application is sent from — by hand, by a person, later.
 *
 * <p>The hour saved on sorting is given back if assembling the documents still takes
 * twenty minutes, so this is the stage that closes the loop. It is also the stage that
 * most invites a send button, and does not have one: there is no transport here, no
 * recipient, no channel, and the configuration models none either. The output is a
 * directory on disk. What happens to it is the operator's decision.
 *
 * <p><b>No CV is tailored.</b> The language of the ad picks a fixed PDF and nothing else,
 * which is the whole of the rule. A generated CV would be a different document every time
 * and impossible to stand behind six months later.
 */
@Slf4j
@Service
public class PackagingService {

    private static final String DUE =
            """
            SELECT id, title, description, full_text, url, location, portal, agency, tags,
                   published_on, rate_eur, duration, workload, remote_percent, starts_on, contact,
                   score_value, score_band, score_model, enrichment_note
            FROM offer
            WHERE status = 'PASSED' AND duplicate_of_id IS NULL AND archived_at IS NULL
              AND score_band = 'SHORTLISTED' AND packaged_at IS NULL
            ORDER BY score_value DESC, id
            """;

    /**
     * The heuristic that picks the cover letter and the CV. Measured over the sample
     * corpus: 0 of 1289 descriptions contain none of these, so German is what the market
     * writes in and an English ad is the exception this exists to catch.
     */
    private static final Pattern GERMAN = Pattern.compile(
            "(?<![a-z])(der|die|das|und|fur|mit|wir|sie|unser|kenntnisse|erfahrung|projekt|kunde)(?![a-z])");

    private static final Pattern UNSAFE = Pattern.compile("[^a-z0-9]+");

    private final ConfigRegistry config;
    private final ConfigProperties properties;
    private final ApplicationService applications;
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final Configuration freemarker;

    PackagingService(
            ConfigRegistry config,
            ConfigProperties properties,
            ApplicationService applications,
            DataSource dataSource) {
        this.config = config;
        this.properties = properties;
        this.applications = applications;
        this.jdbc = JdbcClient.create(dataSource);
        this.json = new ObjectMapper().findAndRegisterModules();
        this.freemarker = new Configuration(Configuration.VERSION_2_3_34);
        this.freemarker.setDefaultEncoding(StandardCharsets.UTF_8.name());
        // A missing value in a template is a bug in the template, not something to paper
        // over with an empty string in a document that goes to a client.
        this.freemarker.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        this.freemarker.setLogTemplateExceptions(false);
    }

    @Transactional
    public PackageReport run() {
        ConfigSnapshot snapshot = config.snapshot();
        PipelineConfig.Packaging settings = snapshot.application().packaging();
        if (settings == null) {
            return PackageReport.nothing();
        }

        List<Map<String, Object>> due = jdbc.sql(DUE).query().listOfRows();
        int built = 0;
        int failed = 0;
        List<Path> folders = new ArrayList<>();

        for (Map<String, Object> row : due) {
            try {
                Path folder = build(snapshot, settings, row);
                folders.add(folder);
                built++;
            } catch (IOException | TemplateException | RuntimeException e) {
                // One unbuildable package must not stop the rest, and the offer stays
                // shortlisted so the next run tries again.
                log.error("Offer {} could not be packaged: {}", row.get("id"), e.getMessage(), e);
                failed++;
            }
        }

        log.info("Packaging: {} of {} built, {} failed", built, due.size(), failed);
        return new PackageReport(due.size(), built, failed, folders);
    }

    private Path build(ConfigSnapshot snapshot, PipelineConfig.Packaging settings, Map<String, Object> row)
            throws IOException, TemplateException {
        SkillProfile profile = snapshot.profile();
        String language = languageOf(row, profile);
        List<SkillProfile.ReferenceProject> projects = referencesFor(row, profile);
        List<String> matchedSkills = matchedSkills(row, profile);

        Path folder = Path.of(settings.outputDir()).resolve(folderName(settings.naming(), row));
        Files.createDirectories(folder);

        Map<String, Object> model = new LinkedHashMap<>();
        // Templates see camelCase, not the database's snake_case: `offer.fullText` is
        // what a template author writes, and `offer.full_text` silently resolves to
        // nothing in Freemarker rather than failing.
        model.put("offer", camelCased(row));
        model.put("profile", profile);
        model.put("projects", projects);
        model.put("matchedSkills", matchedSkills);
        model.put("archivedAt", Instant.now().toString());

        List<String> written = new ArrayList<>();
        for (PipelineConfig.Packaging.Document document : settings.documents()) {
            written.add(switch (document.id()) {
                case "cv" -> copyCv(folder, profile, language);
                case "meta" -> writeMeta(folder, row, language, projects, matchedSkills);
                default -> render(folder, document, language, model);
            });
        }

        jdbc.sql("UPDATE offer SET package_dir = ?, packaged_at = now(), language = ? WHERE id = ?")
                .params(folder.toString(), language, row.get("id"))
                .update();

        // The first moment there is something for a person to act on, so this is where
        // the application opens. Idempotent: a second packaging run must not reset a
        // status the operator has already moved on.
        applications.open(((Number) row.get("id")).longValue(), ApplicationStatus.PACKAGED);
        log.info("Offer {} packaged into {} ({})", row.get("id"), folder, String.join(", ", written));
        return folder;
    }

    /** A template's `{lang}` is the language of the ad; everything else is its file name. */
    private String render(Path folder, PipelineConfig.Packaging.Document document, String language,
            Map<String, Object> model) throws IOException, TemplateException {
        String name = document.template().replace("{lang}", language);
        ConfigSource source = ConfigSource.resolve(properties.configDirectory(), name)
                .orElseThrow(() -> new IllegalStateException(
                        "packaging document '%s' names template '%s', which is neither in the configuration directory nor on the classpath"
                                .formatted(document.id(), name)));

        StringWriter out = new StringWriter();
        new Template(name, new StringReader(source.content()), freemarker).process(model, out);

        String fileName = document.id() + (name.endsWith(".ftl") ? ".txt" : "");
        Files.writeString(folder.resolve(fileName), out.toString(), StandardCharsets.UTF_8);
        return fileName;
    }

    /**
     * The fixed PDF for the ad's language. Missing is recorded rather than fatal: a
     * package without the CV is still most of the work, and the operator drops the file
     * in beside it.
     *
     * <p>A relative path resolves against the configuration directory, the same rule the
     * inbox and the four YAML files follow. Against the working directory the very same
     * configuration points at `backend/…` under `bootRun`, at the repository root in an
     * IDE and at neither from a jar — three missing files that all look like a CV nobody
     * put there, and the only symptom is a `cv-MISSING.txt` in every package. An absolute
     * path is taken as given.
     */
    private String copyCv(Path folder, SkillProfile profile, String language) throws IOException {
        if (profile == null || profile.cvVariants() == null) {
            return "cv missing (no variants configured)";
        }
        SkillProfile.CvVariant variant = profile.cvVariants().get(language);
        if (variant == null) {
            variant = profile.cvVariants().values().stream()
                    .filter(SkillProfile.CvVariant::isDefault)
                    .findFirst()
                    .orElse(null);
        }
        if (variant == null) {
            return "cv missing (no variant for '%s' and no default)".formatted(language);
        }
        Path from = Directories.under(properties.configDirectory(), variant.file());
        if (!Files.isRegularFile(from)) {
            Files.writeString(
                    folder.resolve("cv-MISSING.txt"),
                    "The CV for '%s' is configured as %s, and that file does not exist.%n"
                            .formatted(language, variant.file()),
                    StandardCharsets.UTF_8);
            return "cv missing (" + variant.file() + ")";
        }
        Path to = folder.resolve(from.getFileName().toString());
        Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
        return to.getFileName().toString();
    }

    /**
     * Everything the decision rested on, in a form something else can read: the score and
     * every reason behind it, the fields, and the language that picked the documents.
     */
    private String writeMeta(Path folder, Map<String, Object> row, String language,
            List<SkillProfile.ReferenceProject> projects, List<String> matchedSkills) throws IOException {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("offerId", row.get("id"));
        meta.put("title", row.get("title"));
        meta.put("url", row.get("url"));
        meta.put("portal", row.get("portal"));
        meta.put("agency", row.get("agency"));
        meta.put("location", row.get("location"));
        meta.put("rateEur", row.get("rate_eur"));
        meta.put("duration", row.get("duration"));
        meta.put("workload", row.get("workload"));
        meta.put("startsOn", row.get("starts_on"));
        meta.put("contact", row.get("contact"));
        meta.put("publishedOn", row.get("published_on"));
        meta.put("language", language);
        meta.put("incomplete", row.get("enrichment_note") != null);
        meta.put("enrichmentNote", row.get("enrichment_note"));
        meta.put("score", row.get("score_value"));
        meta.put("band", row.get("score_band"));
        meta.put("model", row.get("score_model"));
        meta.put("reasons", jdbc.sql(
                        "SELECT factor, label, points FROM offer_score_reason WHERE offer_id = ? ORDER BY position")
                .param(row.get("id"))
                .query()
                .listOfRows());
        meta.put("matchedSkills", matchedSkills);
        meta.put("referenceProjects", projects.stream().map(SkillProfile.ReferenceProject::id).toList());
        meta.put("packagedAt", Instant.now().toString());
        // Every portal the cluster came through, so a duplicate is one package and not three.
        meta.put("sources", jdbc.sql(
                        "SELECT portal, agency, url FROM offer WHERE id = ? OR duplicate_of_id = ?")
                .params(row.get("id"), row.get("id"))
                .query()
                .listOfRows());

        Files.writeString(
                folder.resolve("meta.json"),
                json.writerWithDefaultPrettyPrinter().writeValueAsString(meta),
                StandardCharsets.UTF_8);
        return "meta.json";
    }

    private static Map<String, Object> camelCased(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        row.forEach((key, value) -> out.put(camel(key), value));
        return out;
    }

    private static String camel(String snake) {
        StringBuilder out = new StringBuilder(snake.length());
        boolean upper = false;
        for (char c : snake.toCharArray()) {
            if (c == '_') {
                upper = true;
            } else {
                out.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return out.toString();
    }

    /** The reference projects whose stack the offer actually asks for, strongest first. */
    private static List<SkillProfile.ReferenceProject> referencesFor(Map<String, Object> row, SkillProfile profile) {
        if (profile == null || profile.referenceProjects() == null) {
            return List.of();
        }
        String haystack = haystack(row);
        record Scored(SkillProfile.ReferenceProject project, long overlap) {}
        return profile.referenceProjects().stream()
                .map(project -> new Scored(project, project.stack() == null
                        ? 0
                        : project.stack().stream().filter(s -> names(haystack, s)).count()))
                .filter(scored -> scored.overlap() > 0)
                .sorted((a, b) -> Long.compare(b.overlap(), a.overlap()))
                .limit(2)
                .map(Scored::project)
                .toList();
    }

    private static List<String> matchedSkills(Map<String, Object> row, SkillProfile profile) {
        if (profile == null || profile.core() == null) {
            return List.of();
        }
        String haystack = haystack(row);
        return profile.core().stream()
                .filter(skill -> names(haystack, skill.skill())
                        || (skill.aliases() != null && skill.aliases().stream().anyMatch(a -> names(haystack, a))))
                .map(SkillProfile.Skill::skill)
                .toList();
    }

    private static String haystack(Map<String, Object> row) {
        return TextFold.fold("%s %s %s".formatted(
                row.get("title"), row.getOrDefault("description", ""), row.getOrDefault("full_text", "")));
    }

    private static boolean names(String haystack, String keyword) {
        Pattern pattern = TextFold.keyword(keyword);
        return pattern != null && pattern.matcher(haystack).find();
    }

    /**
     * German when the text contains German, English when it contains text and no German,
     * and the profile's primary locale only when there is nothing to go on.
     *
     * <p>The order matters. Falling back to `locale_primary` for an ad that simply has no
     * German in it sends a German letter to an English posting — measured over the corpus,
     * 0 of 1289 descriptions lack a German function word, so English really is the
     * exception this exists to catch and not the default it should collapse into.
     */
    private static String languageOf(Map<String, Object> row, SkillProfile profile) {
        String folded = haystack(row);
        if (GERMAN.matcher(folded).find()) {
            return "de";
        }
        if (!folded.isBlank()) {
            return "en";
        }
        return profile == null || profile.localePrimary() == null ? "de" : profile.localePrimary();
    }

    /** `{date}_{company}_{slug}`, with everything reduced to what a file system likes. */
    private static String folderName(String naming, Map<String, Object> row) {
        Object published = row.get("published_on");
        String date = published instanceof LocalDate day ? day.toString() : LocalDate.now().toString();
        return naming.replace("{date}", date)
                .replace("{company}", safe(String.valueOf(row.getOrDefault("agency", "unknown"))))
                .replace("{slug}", safe(String.valueOf(row.get("title"))))
                .replace("{id}", String.valueOf(row.get("id")));
    }

    private static String safe(String value) {
        String folded = UNSAFE.matcher(TextFold.fold(value)).replaceAll("-").replaceAll("^-|-$", "");
        String trimmed = folded.length() > 60 ? folded.substring(0, 60) : folded;
        return trimmed.isEmpty() ? "unknown" : trimmed.toLowerCase(Locale.ROOT);
    }

}
