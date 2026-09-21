/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import de.codeministry.leadgen.config.model.MatchingRules;
import de.codeministry.leadgen.config.model.PipelineConfig;
import de.codeministry.leadgen.config.model.SkillProfile;
import de.codeministry.leadgen.config.model.SourcesConfig;
import de.codeministry.leadgen.security.SecurityConfig;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

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
        SourcesConfig sources = checkSelectors(resolveInheritance(
                read(source(dir, fileName(sourcesPath(pipeline), SOURCES_FILE)), SourcesConfig.class)));
        SkillProfile profile = read(source(dir, fileName(pipeline.profile().path(), PROFILE_FILE)), SkillProfile.class);

        checkConsistency(dir, pipeline, rules, sources);
        return new ConfigSnapshot(pipeline, rules, sources, profile, Instant.now());
    }

    /**
     * The files a reload has to watch — the external ones only. A default lives inside the
     * jar and cannot change while the process runs, so watching it would be watching
     * nothing.
     *
     * <p><b>The pipeline is passed in and not read here.</b> This used to bind and validate
     * `pipeline.yaml` on every call purely to learn the two configurable file names, and the
     * watcher calls it twice a second: measured on the deployed instance, thirty log lines a
     * minute announcing that the configuration had been read while nothing had changed, and a
     * full parse behind each one.
     *
     * <p>The caller passes the running snapshot, so a `pipeline.yaml` that renames the rules or
     * sources file is watched under its new name one cycle later — the same cycle the rename
     * takes effect in, because `pipeline.yaml` itself is always in the list. A file that is
     * broken on disk therefore stays watched too: the reload is rejected, the last good
     * snapshot stays, and fixing the file is still seen without a restart.
     */
    public List<Path> watchedFiles(PipelineConfig pipeline) {
        Path dir = properties.configDirectory();
        return Stream.of(
                        PIPELINE_FILE,
                        fileName(pipeline.rules().path(), RULES_FILE),
                        fileName(sourcesPath(pipeline), SOURCES_FILE))
                .distinct()
                .map(dir::resolve)
                .toList();
    }

    private ConfigSource source(Path dir, String name) {
        return ConfigSource.resolve(dir, name)
                .orElseThrow(() -> new ConfigValidationException(
                        name,
                        List.of(
                                "not found in %s and not on the classpath — the jar ships a default, so this means the artifact is broken"
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
            String strategy =
                    source.extraction().inherit() == null ? source.extraction().strategy() : "inherited";
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
     * What an IMAP selector has to say about which messages are this source's.
     *
     * <p>Both checks exist because the failure is silent in the direction that costs mail.
     * A selector naming no filter at all reads the whole folder, which is dedicated mode
     * arrived at by accident: it looks identical to a deliberate one until the day a second
     * kind of mail lands in that folder, and then the run extracts from it and nothing says
     * so. And `from` beside `match_all` reads as "take everything" while behaving as a
     * sender filter, because the senders are in the IMAP `SEARCH` term and no flag in the
     * selector takes them out again — removing them there is what lets one source flag a
     * neighbour's mail as taken. Saying so at load is the only place either can be said
     * before it has already happened.
     */
    private static SourcesConfig checkSelectors(SourcesConfig sources) {
        List<String> problems = new ArrayList<>();
        for (SourcesConfig.Source source : sources.sources()) {
            if (!"imap".equals(source.type())) {
                continue;
            }
            SourcesConfig.Selector selector = source.selector();
            if (selector == null) {
                problems.add("source '%s' is an imap source and names no selector".formatted(source.id()));
                continue;
            }
            boolean namesSenders = selector.from() != null && !selector.from().isEmpty();
            boolean namesSubject =
                    selector.subjectMatches() != null && !selector.subjectMatches().isBlank();
            if (selector.matchAll() && namesSenders) {
                problems.add(
                        ("source '%s' sets both 'match_all: true' and 'from'. The senders stay in the IMAP search"
                                        + " either way, so this reads as dedicated mode and behaves as a sender"
                                        + " filter — name one or the other")
                                .formatted(source.id()));
            }
            if (!selector.matchAll() && !namesSenders && !namesSubject) {
                problems.add(
                        ("source '%s' names neither 'from' nor 'subject_matches' nor 'match_all: true', so it would"
                                        + " read every message in '%s'. Say 'match_all: true' if that is the"
                                        + " intention")
                                .formatted(source.id(), selector.folder()));
            }
        }
        problems.addAll(dedicatedSourcesSharingAFolder(sources));
        if (!problems.isEmpty()) {
            throw new ConfigValidationException(SOURCES_FILE, problems);
        }
        return sources;
    }

    /**
     * Dedicated mode in a folder somebody else reads, which is the one arrangement that
     * loses mail rather than merely misreading it.
     *
     * <p>The progress flag is the single name {@code leadgen} and the receiver writes it to
     * everything its search returned. A source with {@code match_all} asks for the whole
     * folder, so it flags the other source's mail as taken before that source has run, and
     * the other source is then told the folder is empty. There is no error and no counter:
     * it looks exactly like a quiet week. Only enabled sources can do it to each other, so
     * only they are compared, and enabling one later fails here rather than in the mailbox.
     */
    private static List<String> dedicatedSourcesSharingAFolder(SourcesConfig sources) {
        List<SourcesConfig.Source> live = sources.sources().stream()
                .filter(SourcesConfig.Source::enabled)
                .filter(candidate -> "imap".equals(candidate.type()))
                .filter(candidate -> candidate.selector() != null)
                .toList();
        List<String> problems = new ArrayList<>();
        for (SourcesConfig.Source dedicated : live) {
            if (!dedicated.selector().matchAll()) {
                continue;
            }
            live.stream()
                    .filter(other -> !other.id().equals(dedicated.id()))
                    .filter(other -> Objects.equals(other.connection(), dedicated.connection()))
                    .filter(other -> Objects.equals(
                            other.selector().folder(), dedicated.selector().folder()))
                    .forEach(other -> problems.add(
                            ("source '%s' reads '%s' with 'match_all: true' while '%s' reads the same folder."
                                            + " The dedicated source marks that source's mail as taken before it"
                                            + " runs, and the loss is silent — give one of them a folder of its own")
                                    .formatted(dedicated.id(), dedicated.selector().folder(), other.id())));
        }
        return problems;
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
        // Not a problem: a fresh clone ships an empty list on purpose, because the
        // places you can reach are the one thing no default can guess. But a filter that
        // silently passes only remote offers looks exactly like a quiet market.
        var onsite = rules.hardFilters().location().onsiteCities();
        if (onsite == null || onsite.isEmpty()) {
            log.warn("hard_filters.location.onsite_cities is empty; only remote offers can pass the filter");
        }

        // Same class of lie as an unimplemented auth mode: `batch: true` on a provider with
        // no batch endpoint would be read, ignored, and score synchronously, while the
        // person who wrote it believes they are paying half.
        var llm = pipeline.llm();
        if (llm != null && llm.batch() && !PipelineConfig.Llm.BATCHING_PROVIDER.equals(llm.provider())) {
            problems.add(
                    "llm.batch is true and llm.provider is '%s'; only '%s' has a batch endpoint implemented, and any other provider would score synchronously at full price while the flag says otherwise"
                            .formatted(llm.provider(), PipelineConfig.Llm.BATCHING_PROVIDER));
        }

        // Inverted, this does not fail: `Score.band` tests the shortlist bound first, so a
        // `review` above `auto_shortlist` deletes the REVIEW band outright and builds an
        // application package for every offer above the lower of the two. Found live, with
        // `auto_shortlist: 30` under `review: 50`.
        var thresholds = rules.scoring() == null ? null : rules.scoring().thresholds();
        if (thresholds != null && thresholds.review() > thresholds.autoShortlist()) {
            problems.add(
                    "scoring.thresholds.review is %d and auto_shortlist is %d; review must not be the higher of the two — the bands are read shortlist-first, so the REVIEW band would never be reached and every offer above %d would get a package"
                            .formatted(thresholds.review(), thresholds.autoShortlist(), thresholds.autoShortlist()));
        }

        String mergePolicy = rules.deduplication().mergePolicy();
        if (mergePolicy != null && !"keep_first_seen_as_primary".equals(mergePolicy)) {
            problems.add(
                    "deduplication.merge_policy is '%s'; only 'keep_first_seen_as_primary' is implemented — any other value would be read, ignored, and silently do the first-seen thing anyway"
                            .formatted(mergePolicy));
        }
        // The worst possible failure here is the quiet one: someone writes a mode, believes
        // the write endpoints are protected, and they are not. So a mode that is not
        // implemented is refused by name, and `oidc` without an issuer is refused too —
        // there is nothing to verify a token against, and a resource server with no issuer
        // would either reject everything or, worse, be assembled as if it were configured.
        // `none` stays safe because the service binds to 127.0.0.1 unless SERVER_ADDRESS
        // says otherwise.
        String auth = pipeline.security().auth();
        if (!SecurityConfig.NONE.equals(auth) && !SecurityConfig.OIDC.equals(auth)) {
            problems.add(
                    "security.auth is '%s'; implemented are '%s' and '%s' — any other value would be read, ignored, and leave the write endpoints open while looking protected"
                            .formatted(auth, SecurityConfig.NONE, SecurityConfig.OIDC));
        }
        if (SecurityConfig.OIDC.equals(auth)) {
            String issuer = pipeline.security().oidc() == null
                    ? null
                    : pipeline.security().oidc().get("issuer");
            if (issuer == null || issuer.isBlank()) {
                problems.add(
                        "security.auth is 'oidc' and security.oidc.issuer is empty; set OIDC_ISSUER to the realm's issuer URL, the one whose /.well-known/openid-configuration answers");
            }
        }
        if (pipeline.enrichment().enabled()
                && !"hard_filter".equals(pipeline.enrichment().after())) {
            problems.add("enrichment.after is '%s'; only 'hard_filter' is allowed"
                    .formatted(pipeline.enrichment().after()));
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

    /**
     * Only for the log line at startup: which of the files came from outside the jar.
     */
    public List<String> overriddenFiles() {
        Path dir = properties.configDirectory();
        return List.of(PIPELINE_FILE, RULES_FILE, SOURCES_FILE, PROFILE_FILE).stream()
                .filter(name -> Files.isRegularFile(dir.resolve(name)))
                .toList();
    }
}
