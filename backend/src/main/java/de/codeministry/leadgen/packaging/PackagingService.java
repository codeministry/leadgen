/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.packaging;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import de.codeministry.leadgen.application.ApplicationService;
import de.codeministry.leadgen.application.ApplicationStatus;
import de.codeministry.leadgen.config.*;
import de.codeministry.leadgen.config.model.PipelineConfig;
import de.codeministry.leadgen.config.model.SkillProfile;
import de.codeministry.leadgen.content.ContentText;
import de.codeministry.leadgen.filter.TextFold;
import de.codeministry.leadgen.llm.Vectors;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import freemarker.template.TemplateExceptionHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

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

    /**
     * The offers somebody has asked for a package for.
     *
     * <p><b>The gate is the person's decision, not the score band.</b> It used to be
     * {@code score_band = 'SHORTLISTED'}, which meant the run built a folder for everything
     * the tool liked — measured on the deployed instance on 2026-09-17: 93 packages against 2
     * applications actually sent, every one of them paying for its templates, its CV copy and
     * its reference-ranking embeddings. Reaching the shortlist now opens an application at
     * NEW and nothing else; moving it to PACKAGED is what asks for the folder, and that is
     * what this reads.
     *
     * <p>Dropping the band is deliberate rather than incidental: an operator may decide to
     * answer a MAYBE, and a gate that second-guessed them would leave a PACKAGED application
     * with nothing behind it — the exact state
     * {@link de.codeministry.leadgen.application.ApplicationStatus#allowedNext()} exists to
     * make impossible.
     *
     * <p>{@code packaged_at IS NULL} still carries "not built yet", so a restored offer whose
     * folder survived the archive is not rebuilt.
     */
    private static final String REQUESTED = """
        SELECT id, title, description, full_text, url, location, portal, agency, tags,
               published_on, rate_eur, duration, workload, remote_percent, starts_on, contact,
               score_value, score_band, score_model, enrichment_note, content_blocks,
               retrieval_embedding
        FROM offer
        WHERE status = 'PASSED' AND duplicate_of_id IS NULL AND archived_at IS NULL
          AND packaged_at IS NULL
          AND EXISTS (SELECT 1 FROM application a
                       WHERE a.offer_id = offer.id AND a.status = 'PACKAGED')
        """;

    private static final String DUE = REQUESTED + """
        ORDER BY score_value DESC, id
        """;

    private static final String DUE_ONE = REQUESTED + """
          AND id = ?
        """;

    /**
     * The heuristic that picks the cover letter and the CV. Measured over the sample
     * corpus: 0 of 1289 descriptions contain none of these, so German is what the market
     * writes in and an English ad is the exception this exists to catch.
     */
    private static final Pattern GERMAN = Pattern.compile(
            "(?<![a-z])(der|die|das|und|fur|mit|wir|sie|unser|kenntnisse|erfahrung|projekt|kunde)(?![a-z])");

    /**
     * How many projects a letter pitches. Two is what the templates render and what a reader
     * gets through; it was a literal on the old selection and is named here because two places
     * now read it.
     */
    private static final int REFERENCES = 2;

    private static final Pattern UNSAFE = Pattern.compile("[^a-z0-9]+");

    private final ConfigRegistry config;
    private final ConfigProperties properties;
    private final ApplicationService applications;
    private final ProfileEmbeddings profileEmbeddings;
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Configuration freemarker;

    PackagingService(
            ConfigRegistry config,
            ConfigProperties properties,
            ApplicationService applications,
            ProfileEmbeddings profileEmbeddings,
            DataSource dataSource) {
        this.config = config;
        this.properties = properties;
        this.applications = applications;
        this.profileEmbeddings = profileEmbeddings;
        this.jdbc = JdbcClient.create(dataSource);
        // Named, not discovered. `findAndRegisterModules()` is a ServiceLoader scan, which
        // means the set of modules depends on the classpath's order and, in a native image,
        // is empty unless somebody registered the scan as a hint. This mapper writes
        // `meta.json`, whose only non-trivial types are the java.time ones.
        this.json = JsonMapper.builder().addModule(new JavaTimeModule()).build();
        this.freemarker = new Configuration(Configuration.VERSION_2_3_34);
        this.freemarker.setDefaultEncoding(StandardCharsets.UTF_8.name());
        // A missing value in a template is a bug in the template, not something to paper
        // over with an empty string in a document that goes to a client.
        this.freemarker.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        this.freemarker.setLogTemplateExceptions(false);
    }

    /**
     * One shortlisted offer, read through a {@link ResultSet} and not through
     * {@code listOfRows()}.
     *
     * <p>That is the whole of the fix this record exists for, and it is worth naming: a
     * {@code jsonb} column arrives from the driver as a {@code PGobject} and a {@code TEXT[]}
     * as a {@code PgArray}, so a map of {@code Object} hands both straight on. The cast on
     * {@code content_blocks} threw a {@link ClassCastException} for every advert that had been
     * segmented, the per-offer catch in {@link #run()} turned that into a counter, and
     * {@code package_dir} was therefore never written — which on screen is every offer above
     * the threshold reporting that it has no package. The {@code tags} array never threw at
     * all; it simply reached Freemarker as a wrapper around a JDBC array.
     *
     * <p>The three other readers of {@code content_blocks} — {@code ScoreCandidate.of},
     * {@code OfferQueryService.row} and {@code ContentService}'s own mapper — all call
     * {@code rs.getString(...)}, which is where the driver renders the JSON as text. This is
     * the fourth, and now it is the same one.
     */
    private record Due(
        long id,
        String title,
        String description,
        String fullText,
        String url,
        String location,
        String portal,
        String agency,
        List<String> tags,
        LocalDate publishedOn,
        BigDecimal rateEur,
        String duration,
        String workload,
        Integer remotePercent,
        LocalDate startsOn,
        String contact,
        Integer scoreValue,
        String scoreBand,
        String scoreModel,
        String enrichmentNote,
        String contentBlocks,
        String retrievalEmbedding) {

        static Due of(ResultSet rs, int row) throws SQLException {
            return new Due(
                rs.getLong("id"),
                rs.getString("title"),
                rs.getString("description"),
                rs.getString("full_text"),
                rs.getString("url"),
                rs.getString("location"),
                rs.getString("portal"),
                rs.getString("agency"),
                tags(rs),
                rs.getObject("published_on", LocalDate.class),
                rs.getBigDecimal("rate_eur"),
                rs.getString("duration"),
                rs.getString("workload"),
                (Integer) rs.getObject("remote_percent"),
                rs.getObject("starts_on", LocalDate.class),
                rs.getString("contact"),
                (Integer) rs.getObject("score_value"),
                rs.getString("score_band"),
                rs.getString("score_model"),
                rs.getString("enrichment_note"),
                rs.getString("content_blocks"),
                // A `vector` column comes back as text, the way it went in. Read with
                // `getString` and never through `listOfRows()`, where the driver hands over a
                // `PGobject` and the cast that looks right is a 500.
                rs.getString("retrieval_embedding"));
        }

        private static List<String> tags(ResultSet rs) throws SQLException {
            var array = rs.getArray("tags");
            return array == null ? List.of() : List.of((String[]) array.getArray());
        }

        /**
         * What a template sees. camelCase, not the database's snake_case: {@code offer.fullText}
         * is what a template author writes, and {@code offer.full_text} silently resolves to
         * nothing in Freemarker rather than failing.
         *
         * <p>Written out rather than derived from the column names, because the record's
         * components are already the camelCase spelling and a second mechanism converting them
         * back and forth is one more thing that can disagree with the templates.
         */
        Map<String, Object> model() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", id);
            out.put("title", title);
            out.put("description", description);
            out.put("fullText", fullText);
            out.put("url", url);
            out.put("location", location);
            out.put("portal", portal);
            out.put("agency", agency);
            out.put("tags", tags);
            out.put("publishedOn", publishedOn);
            out.put("rateEur", rateEur);
            out.put("duration", duration);
            out.put("workload", workload);
            out.put("remotePercent", remotePercent);
            out.put("startsOn", startsOn);
            out.put("contact", contact);
            out.put("scoreValue", scoreValue);
            out.put("scoreBand", scoreBand);
            out.put("scoreModel", scoreModel);
            out.put("enrichmentNote", enrichmentNote);
            return out;
        }
    }

    /**
     * Builds every folder that has been asked for and is still missing.
     *
     * <p>This is no longer where packages normally come from — {@link #buildFor(long)} is,
     * the moment somebody moves an application to PACKAGED — and it stays in the run as the
     * retry. A build that failed, or one whose listener never ran because the process went
     * down between the commit and the disk, leaves the offer due and this picks it up. On a
     * healthy instance it reports zero, and that is the expected reading.
     */
    @Transactional
    public PackageReport run() {
        return buildAll(jdbc.sql(DUE).query(Due::of).list());
    }

    /**
     * The same for one offer, which is what asking for a package looks like.
     *
     * <p>Called after the status change has committed, so a rolled-back move never leaves a
     * folder behind. A no-op when the offer's application is not PACKAGED or the folder is
     * already there: the query is the authority, not the caller.
     */
    @Transactional
    public PackageReport buildFor(long offerId) {
        return buildAll(jdbc.sql(DUE_ONE).param(offerId).query(Due::of).list());
    }

    private PackageReport buildAll(List<Due> due) {
        ConfigSnapshot snapshot = config.snapshot();
        PipelineConfig.Packaging settings = snapshot.application().packaging();
        if (settings == null) {
            return PackageReport.nothing();
        }

        int built = 0;
        int failed = 0;
        List<Path> folders = new ArrayList<>();

        for (Due row : due) {
            try {
                Path folder = build(snapshot, settings, row);
                folders.add(folder);
                built++;
            } catch (IOException | TemplateException | RuntimeException e) {
                // One unbuildable package must not stop the rest, and the offer keeps its
                // PACKAGED application with no `packaged_at`, so the next run tries again.
                log.error("Offer {} could not be packaged: {}", row.id(), e.getMessage(), e);
                failed++;
            }
        }

        if (built > 0 || failed > 0) {
            log.info("Packaging: {} of {} built, {} failed", built, due.size(), failed);
        }
        return new PackageReport(due.size(), built, failed, folders);
    }

    private Path build(ConfigSnapshot snapshot, PipelineConfig.Packaging settings, Due row)
            throws IOException, TemplateException {
        SkillProfile profile = snapshot.profile();
        String language = languageOf(row, profile);
        // After the language, because the pitch is compared in the language the letter is
        // written in: a German pitch against a German advert is the comparison that means
        // something, and a mixed-language blob is not.
        List<SkillProfile.ReferenceProject> projects = referencesFor(row, profile, language);
        List<String> matchedSkills = matchedSkills(row, profile);

        Path folder = Path.of(settings.outputDir()).resolve(folderName(settings.naming(), row));
        Files.createDirectories(folder);

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("offer", row.model());
        model.put("profile", profile);
        // Views and not the profile's own records: the language is decided once, here,
        // rather than by each template guessing which of two title and two pitch fields
        // it wants. Both letters then read identically.
        model.put("projects", projects.stream().map(project -> ProjectView.of(project, language)).toList());
        model.put("matchedSkills", matchedSkills);
        // `offer.startsOn` is a LocalDate and Freemarker renders one as ISO, which reads as
        // a machine's date in a sentence written for a person. The archive keeps the ISO
        // form on purpose; a letter does not.
        model.put("startsOnText", startsOnText(row.startsOn(), language));
        model.put("archivedAt", Instant.now().toString());

        List<String> written = new ArrayList<>();
        for (PipelineConfig.Packaging.Document document : settings.documents()) {
            written.add(
                    switch (document.id()) {
                        case "cv" -> copyCv(folder, profile, language);
                        case "meta" -> writeMeta(folder, row, language, projects, matchedSkills);
                        default -> render(folder, document, language, model);
                    });
        }

        jdbc.sql("UPDATE offer SET package_dir = ?, packaged_at = now(), language = ? WHERE id = ?")
            .params(folder.toString(), language, row.id())
                .update();

        // A no-op on the normal path: the PACKAGED application is what asked for this
        // folder, so the row is already there. It stands for the abnormal one — a folder
        // with no application behind it would be invisible on the only screen that reads
        // this state. Idempotent, so it cannot reset a status anybody has moved on.
        applications.open(row.id(), ApplicationStatus.PACKAGED);
        log.info("Offer {} packaged into {} ({})", row.id(), folder, String.join(", ", written));
        return folder;
    }

    /**
     * A template's `{lang}` is the language of the ad; everything else is its file name.
     */
    private String render(
            Path folder, PipelineConfig.Packaging.Document document, String language, Map<String, Object> model)
            throws IOException, TemplateException {
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
    private String writeMeta(
            Path folder,
            Due row,
            String language,
            List<SkillProfile.ReferenceProject> projects,
            List<String> matchedSkills)
            throws IOException {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("offerId", row.id());
        meta.put("title", row.title());
        meta.put("url", row.url());
        meta.put("portal", row.portal());
        meta.put("agency", row.agency());
        meta.put("location", row.location());
        meta.put("rateEur", row.rateEur());
        meta.put("duration", row.duration());
        meta.put("workload", row.workload());
        meta.put("startsOn", row.startsOn());
        meta.put("contact", row.contact());
        meta.put("publishedOn", row.publishedOn());
        meta.put("language", language);
        meta.put("incomplete", row.enrichmentNote() != null);
        meta.put("enrichmentNote", row.enrichmentNote());
        meta.put("score", row.scoreValue());
        meta.put("band", row.scoreBand());
        meta.put("model", row.scoreModel());
        meta.put(
                "reasons",
                jdbc.sql("SELECT factor, label, points FROM offer_score_reason WHERE offer_id = ? ORDER BY position")
                    .param(row.id())
                        .query()
                        .listOfRows());
        meta.put("matchedSkills", matchedSkills);
        meta.put(
                "referenceProjects",
                projects.stream().map(SkillProfile.ReferenceProject::id).toList());
        meta.put("packagedAt", Instant.now().toString());
        // Every portal the cluster came through, so a duplicate is one package and not three.
        meta.put(
                "sources",
                jdbc.sql("SELECT portal, agency, url FROM offer WHERE id = ? OR duplicate_of_id = ?")
                    .params(row.id(), row.id())
                        .query()
                        .listOfRows());

        Files.writeString(
                folder.resolve("meta.json"),
                json.writerWithDefaultPrettyPrinter().writeValueAsString(meta),
                StandardCharsets.UTF_8);
        return "meta.json";
    }

    /**
     * Two reference projects: the ones the advert asked for, and where it asked for none, the
     * ones it is about.
     *
     * <p>The lexical rule still decides everything it can — {@link ReferenceRanking} carries
     * the argument, and the reason this method is now three lines is that the rule and the
     * blend are worth testing without a database.
     */
    private List<SkillProfile.ReferenceProject> referencesFor(Due row, SkillProfile profile, String language) {
        String haystack = haystack(row);
        return ReferenceRanking.choose(
                profile,
                project -> project.stack() == null
                        ? 0
                        : project.stack().stream().filter(s -> names(haystack, s)).count(),
                Vectors.parse(row.retrievalEmbedding()),
                profileEmbeddings.forLanguage(profile, language),
                REFERENCES);
    }

    private static List<String> matchedSkills(Due row, SkillProfile profile) {
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

    /**
     * The advert as the skill matcher and the language detector read it: the content blocks
     * when the advert has been read that way, `full_text` when it has not. A portal's own tag
     * cloud otherwise decides which reference projects a cover letter pitches.
     */
    private static String haystack(Due row) {
        String advert = ContentText.of(row.contentBlocks(), row.fullText() == null ? "" : row.fullText());
        return TextFold.fold("%s %s %s"
            .formatted(row.title(), row.description() == null ? "" : row.description(), advert));
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
    private static String languageOf(Due row, SkillProfile profile) {
        String folded = haystack(row);
        if (GERMAN.matcher(folded).find()) {
            return "de";
        }
        if (!folded.isBlank()) {
            return "en";
        }
        return profile == null || profile.localePrimary() == null ? "de" : profile.localePrimary();
    }

    /**
     * `{date}_{company}_{slug}`, with everything reduced to what a file system likes.
     */
    /**
     * The start date as the language of the letter writes it: {@code 01.10.2026} in German,
     * {@code 1 October 2026} in English. Null when the advert states no day — nineteen of
     * twenty-one adverts named a start and only four of those named a calendar day, so the
     * templates guard this value rather than assume it.
     */
    private static String startsOnText(LocalDate startsOn, String language) {
        if (startsOn == null) {
            return null;
        }
        return "en".equalsIgnoreCase(language)
            ? startsOn.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH))
            : startsOn.format(DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.GERMAN));
    }

    private static String folderName(String naming, Due row) {
        String date = row.publishedOn() == null
            ? LocalDate.now().toString()
            : row.publishedOn().toString();
        return naming.replace("{date}", date)
            .replace("{company}", safe(row.agency() == null ? "unknown" : row.agency()))
            .replace("{slug}", safe(String.valueOf(row.title())))
            .replace("{id}", String.valueOf(row.id()));
    }

    private static String safe(String value) {
        String folded = UNSAFE.matcher(TextFold.fold(value)).replaceAll("-").replaceAll("^-|-$", "");
        String trimmed = folded.length() > 60 ? folded.substring(0, 60) : folded;
        return trimmed.isEmpty() ? "unknown" : trimmed.toLowerCase(Locale.ROOT);
    }
}
