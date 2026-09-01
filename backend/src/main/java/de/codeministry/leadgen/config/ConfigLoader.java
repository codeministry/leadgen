package de.codeministry.leadgen.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import de.codeministry.leadgen.config.model.MatchingRules;
import de.codeministry.leadgen.config.model.PipelineConfig;
import de.codeministry.leadgen.config.model.SourcesConfig;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Reads, resolves, binds and validates the configuration.
 *
 * <p><b>Two layers, the same as Spring's own.</b> Working defaults ship on the classpath
 * under {@code /leadgen/} and are part of the jar; an external directory overrides them
 * file by file. Running the tool needs no configuration at all, and anything individual —
 * credentials, the profile, the real sources — lives outside the artifact.
 *
 * <p><b>These are not Spring properties.</b> They are the tool's own data with their own
 * schema, read by Jackson, bound strictly and validated across files. Strictly, because an
 * unknown key is an error and not a shrug: a misspelled `min_remote_percent` would
 * otherwise disable a hard filter and nothing would say so — the only visible effect is a
 * slightly longer shortlist, which looks exactly like a good day on the market. Spring's
 * relaxed binding would ignore it silently, which is why this layer exists at all.
 */
@Slf4j
@Component
public class ConfigLoader {

    public static final String PIPELINE_FILE = "pipeline.yaml";
    public static final String SOURCES_FILE = "sources.yaml";
    public static final String RULES_FILE = "matching-rules.yaml";
    public static final String PROFILE_FILE = "skill-profile.yaml";

    private final ConfigProperties properties;
    private final Validator validator;
    private final PlaceholderResolver placeholders;
    private final JsonMapper mapper;

    // Two constructors, so the one Spring uses has to say so. The other exists for tests,
    // which supply their own environment instead of the process's.
    @org.springframework.beans.factory.annotation.Autowired
    ConfigLoader(ConfigProperties properties, Validator validator) {
        this(properties, validator, PlaceholderResolver.fromSystemEnvironment());
    }

    ConfigLoader(ConfigProperties properties, Validator validator, PlaceholderResolver placeholders) {
        this.properties = properties;
        this.validator = validator;
        this.placeholders = placeholders;
        this.mapper = JsonMapper.builder(new YAMLFactory())
                .addModule(new JavaTimeModule())
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
                .build();
    }

    public ConfigSnapshot load() {
        Path dir = properties.configDirectory();

        PipelineConfig pipeline = read(source(dir, PIPELINE_FILE), PipelineConfig.class);
        MatchingRules rules = read(source(dir, fileName(pipeline.rules().path(), RULES_FILE)), MatchingRules.class);
        SourcesConfig sources = resolveInheritance(
                read(source(dir, fileName(sourcesPath(pipeline), SOURCES_FILE)), SourcesConfig.class));

        checkConsistency(dir, pipeline, rules, sources);
        return new ConfigSnapshot(pipeline, rules, sources, Instant.now());
    }

    /**
     * The files a reload has to watch — the external ones only. A default lives inside the
     * jar and cannot change while the process runs, so watching it would be watching
     * nothing.
     */
    public List<Path> watchedFiles() {
        Path dir = properties.configDirectory();
        List<String> names;
        try {
            PipelineConfig pipeline = read(source(dir, PIPELINE_FILE), PipelineConfig.class);
            names = List.of(
                    PIPELINE_FILE,
                    fileName(pipeline.rules().path(), RULES_FILE),
                    fileName(sourcesPath(pipeline), SOURCES_FILE));
        } catch (RuntimeException e) {
            // A broken pipeline.yaml still has to be watched, or fixing it would need a
            // restart — which is exactly the situation hot reload exists for.
            names = List.of(PIPELINE_FILE, RULES_FILE, SOURCES_FILE);
        }
        return names.stream().distinct().map(dir::resolve).toList();
    }

    private ConfigSource source(Path dir, String name) {
        return ConfigSource.resolve(dir, name)
                .orElseThrow(() -> new ConfigValidationException(
                        name,
                        List.of("not found in %s and not on the classpath — the jar ships a default, so this means the artifact is broken"
                                .formatted(dir))));
    }

    /**
     * A path in `pipeline.yaml` names a file, never a location. Only the file name is used,
     * and the two-layer lookup decides where it comes from.
     *
     * <p>Anything more forgiving was measured and removed: resolving a path like
     * `config/local/matching-rules.yaml` from the working directory upwards made a run read
     * a file from outside the directory it was pointed at, and look entirely normal doing
     * it. Two configurations became one, silently.
     */
    private static String fileName(String configured, String fallback) {
        if (configured == null || configured.isBlank()) {
            return fallback;
        }
        Path given = Path.of(configured);
        String name = given.getFileName().toString();
        if (given.getNameCount() > 1 || given.isAbsolute()) {
            log.warn("'{}' names a location; only '{}' is used — a path here is a file name", configured, name);
        }
        return name;
    }

    private static String sourcesPath(PipelineConfig pipeline) {
        return pipeline.sources() == null ? null : pipeline.sources().path();
    }

    private <T> T read(ConfigSource file, Class<T> type) {
        T bound;
        try {
            bound = mapper.readValue(placeholders.resolve(file.content()), type);
        } catch (IOException e) {
            throw new ConfigValidationException(file.origin(), List.of(rootCause(e)));
        }

        Set<ConstraintViolation<T>> violations = validator.validate(bound);
        if (!violations.isEmpty()) {
            List<String> problems = violations.stream()
                    .map(v -> "%s: %s".formatted(v.getPropertyPath(), v.getMessage()))
                    .sorted()
                    .toList();
            throw new ConfigValidationException(file.origin(), problems);
        }
        log.info("{} read from {}", file.name(), file.origin());
        return bound;
    }

    /**
     * Replaces every `extraction.inherit: <id>` with the named source's extraction. One
     * level only: an inherited block that inherits again is rejected rather than followed,
     * because a chain is a cycle waiting to happen and nothing here needs one.
     */
    private static SourcesConfig resolveInheritance(SourcesConfig sources) {
        List<String> problems = new ArrayList<>();
        List<SourcesConfig.Source> resolved = new ArrayList<>();

        for (SourcesConfig.Source source : sources.sources()) {
            String parentId = source.extraction().inherit();
            if (parentId == null) {
                resolved.add(source);
                continue;
            }
            Optional<SourcesConfig.Source> parent = sources.sources().stream()
                    .filter(candidate -> candidate.id().equals(parentId))
                    .findFirst();
            if (parent.isEmpty()) {
                problems.add("source '%s' inherits extraction from '%s', which is not declared"
                        .formatted(source.id(), parentId));
                continue;
            }
            if (parent.get().extraction().inherit() != null) {
                problems.add("source '%s' inherits from '%s', which inherits itself — one level only"
                        .formatted(source.id(), parentId));
                continue;
            }
            resolved.add(new SourcesConfig.Source(
                    source.id(),
                    source.enabled(),
                    source.type(),
                    source.connection(),
                    source.url(),
                    source.path(),
                    source.glob(),
                    source.schedule(),
                    source.selector(),
                    parent.get().extraction(),
                    source.defaults()));
        }

        sources.sources().forEach(source -> {
            String strategy = source.extraction().inherit() == null ? source.extraction().strategy() : "inherited";
            if (strategy == null || strategy.isBlank()) {
                problems.add("source '%s' states no extraction strategy and inherits none".formatted(source.id()));
            }
        });

        if (!problems.isEmpty()) {
            throw new ConfigValidationException(SOURCES_FILE, problems);
        }
        return new SourcesConfig(sources.version(), sources.connections(), resolved);
    }

    /**
     * The checks no single file can make on its own — plus the one repo-wide invariant that
     * fails silently in both directions: the rate filter applied before enrichment discards
     * either every offer or none, because the sources state a rate in 0.0 % of them.
     */
    private void checkConsistency(Path dir, PipelineConfig pipeline, MatchingRules rules, SourcesConfig sources) {
        List<String> problems = new ArrayList<>();

        if (!"enrichment".equals(rules.hardFilters().rate().applyAfter())) {
            problems.add(
                    "hard_filters.rate.apply_after is '%s'; only 'enrichment' is allowed — the sources state a rate in 0.0 %% of offers, so applied earlier this rule filters either everything or nothing"
                            .formatted(rules.hardFilters().rate().applyAfter()));
        }
        String mergePolicy = rules.deduplication().mergePolicy();
        if (mergePolicy != null && !"keep_first_seen_as_primary".equals(mergePolicy)) {
            problems.add(
                    "deduplication.merge_policy is '%s'; only 'keep_first_seen_as_primary' is implemented — any other value would be read, ignored, and silently do the first-seen thing anyway"
                            .formatted(mergePolicy));
        }
        if (pipeline.enrichment().enabled() && !"hard_filter".equals(pipeline.enrichment().after())) {
            problems.add("enrichment.after is '%s'; only 'hard_filter' is allowed"
                    .formatted(pipeline.enrichment().after()));
        }

        String profile = fileName(pipeline.profile().path(), PROFILE_FILE);
        if (ConfigSource.resolve(dir, profile).isEmpty()) {
            problems.add("profile.path names '%s', which is neither in %s nor on the classpath".formatted(profile, dir));
        }

        Set<String> connectionIds = new HashSet<>();
        sources.connections().forEach(c -> {
            if (!connectionIds.add(c.id())) {
                problems.add("duplicate connection id '%s'".formatted(c.id()));
            }
        });

        Set<String> sourceIds = new HashSet<>();
        sources.sources().forEach(s -> {
            if (!sourceIds.add(s.id())) {
                problems.add("duplicate source id '%s'".formatted(s.id()));
            }
            if (s.connection() != null && !connectionIds.contains(s.connection())) {
                problems.add(
                        "source '%s' names connection '%s', which is not declared".formatted(s.id(), s.connection()));
            }
            // Credentials come from the environment, so an enabled source is the only place
            // where an empty value is worth failing over: a disabled block may legitimately
            // reference variables nobody has set.
            if (s.enabled() && s.connection() != null) {
                sources.connections().stream()
                        .filter(c -> c.id().equals(s.connection()))
                        .findFirst()
                        .filter(c -> "imap".equals(c.type()))
                        .filter(c -> isBlank(c.host()) || isBlank(c.username()) || isBlank(c.password()))
                        .ifPresent(c -> problems.add(
                                "source '%s' is enabled but connection '%s' has no host, user or password — set IMAP_HOST, IMAP_USER and IMAP_PASSWORD in .env"
                                        .formatted(s.id(), c.id())));
            }
        });

        if (!problems.isEmpty()) {
            problems.sort(Comparator.naturalOrder());
            throw new ConfigValidationException(dir.toString(), problems);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String rootCause(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.toString() : cause.getMessage();
    }

    /** Only for the log line at startup: which of the files came from outside the jar. */
    public List<String> overriddenFiles() {
        Path dir = properties.configDirectory();
        return List.of(PIPELINE_FILE, RULES_FILE, SOURCES_FILE, PROFILE_FILE).stream()
                .filter(name -> Files.isRegularFile(dir.resolve(name)))
                .toList();
    }
}
