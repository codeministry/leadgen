package de.codeministry.leadgen.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import de.codeministry.leadgen.config.model.ApplicationConfig;
import de.codeministry.leadgen.config.model.MatchingRules;
import de.codeministry.leadgen.config.model.SourcesConfig;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Reads, resolves, binds and validates the three configuration files.
 *
 * <p>Every file is bound strictly: an unknown key is an error, not a shrug. A
 * misspelled `min_remote_percent` would otherwise disable a hard filter and nothing
 * would ever say so — the only visible effect is a slightly longer shortlist, which
 * looks exactly like a good day on the market.
 */
@Component
public class ConfigLoader {

    public static final String APPLICATION_FILE = "application.yaml";
    public static final String SOURCES_FILE = "sources.yaml";

    private final ConfigProperties properties;
    private final Validator validator;
    private final PlaceholderResolver placeholders;
    private final JsonMapper mapper;

    // Two constructors, so the one Spring uses has to say so. The other exists for
    // tests, which supply their own environment instead of the process's.
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
        ApplicationConfig application = read(dir.resolve(APPLICATION_FILE), ApplicationConfig.class);

        // The paths in application.yaml are file names, resolved against the config
        // directory. They used to carry `config/local/` themselves, which broke the
        // moment the directory moved — inside the container it is /config.
        MatchingRules rules = read(resolveAgainst(dir, application.rules().path()), MatchingRules.class);
        SourcesConfig sources = read(dir.resolve(SOURCES_FILE), SourcesConfig.class);

        checkConsistency(application, rules, sources);
        return new ConfigSnapshot(application, rules, sources, Instant.now());
    }

    /** The files a reload has to watch. */
    public List<Path> watchedFiles() {
        Path dir = properties.configDirectory();
        return List.of(dir.resolve(APPLICATION_FILE), dir.resolve(SOURCES_FILE), dir.resolve("matching-rules.yaml"));
    }

    private static Path resolveAgainst(Path dir, String path) {
        Path given = Path.of(path);
        return given.isAbsolute() ? given : dir.resolve(given);
    }

    private <T> T read(Path file, Class<T> type) {
        if (!Files.isRegularFile(file)) {
            throw new ConfigValidationException(
                    file.toString(), List.of("file not found — copy the matching example from config/examples/"));
        }

        String raw;
        try {
            raw = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }

        T bound;
        try {
            bound = mapper.readValue(placeholders.resolve(raw), type);
        } catch (IOException e) {
            throw new ConfigValidationException(file.toString(), List.of(rootCause(e)));
        }

        Set<ConstraintViolation<T>> violations = validator.validate(bound);
        if (!violations.isEmpty()) {
            List<String> problems = violations.stream()
                    .map(v -> "%s: %s".formatted(v.getPropertyPath(), v.getMessage()))
                    .sorted()
                    .toList();
            throw new ConfigValidationException(file.toString(), problems);
        }
        return bound;
    }

    /**
     * The checks no single file can make on its own — plus the one repo-wide
     * invariant that fails silently in both directions: the rate filter applied
     * before enrichment discards either every offer or none, because the newsletter
     * states a rate in 0.0 % of them.
     */
    private void checkConsistency(ApplicationConfig application, MatchingRules rules, SourcesConfig sources) {
        List<String> problems = new ArrayList<>();

        if (!"enrichment".equals(rules.hardFilters().rate().applyAfter())) {
            problems.add(
                    "hard_filters.rate.apply_after is '%s'; only 'enrichment' is allowed — the sources state a rate in 0.0 %% of offers, so applied earlier this rule filters either everything or nothing"
                            .formatted(rules.hardFilters().rate().applyAfter()));
        }
        if (application.enrichment().enabled() && !"hard_filter".equals(application.enrichment().after())) {
            problems.add("enrichment.after is '%s'; only 'hard_filter' is allowed"
                    .formatted(application.enrichment().after()));
        }

        Path profile = resolveAgainst(properties.configDirectory(), application.profile().path());
        if (!Files.isRegularFile(profile)) {
            problems.add("profile.path points at %s, which does not exist".formatted(profile));
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
                problems.add("source '%s' names connection '%s', which is not declared".formatted(s.id(), s.connection()));
            }
            // Credentials come from the environment, so an enabled source is the only
            // place where an empty value is worth failing over: a disabled block may
            // legitimately reference variables nobody has set.
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
            throw new ConfigValidationException(properties.configDirectory().toString(), problems);
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
}
