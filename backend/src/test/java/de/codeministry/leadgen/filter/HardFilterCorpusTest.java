package de.codeministry.leadgen.filter;

import static org.assertj.core.api.Assertions.assertThat;

import de.codeministry.leadgen.config.ConfigFixtures;
import de.codeministry.leadgen.config.ConfigProperties;
import de.codeministry.leadgen.config.ConfigSource;
import de.codeministry.leadgen.ingest.ExtractedOffer;
import de.codeministry.leadgen.ingest.RawDocument;
import de.codeministry.leadgen.ingest.connector.FileSourceConnector;
import de.codeministry.leadgen.ingest.extract.HtmlBlockExtractor;
import de.codeministry.leadgen.ingest.extract.OfferMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ISC-41 and ISC-42: the Java hard filter has to reproduce
 * {@code docs/samples/simulate_filter.py} over the sample corpus, exactly.
 *
 * <p>The numbers below are that reference's output, not a snapshot of this
 * implementation. A deviation is a bug in one of the two, and the point of having both is
 * that they are written from different angles against the same measured corpus.
 *
 * <p><b>Skipped without the corpus and without the operator's configuration.</b> The
 * mails are gitignored because they carry the subscriber's address, and the rules that
 * produce these numbers name the operator's home region, which does not belong in a
 * public repository either. {@link HardFilterTest} covers the same mechanics against a
 * fictional rule set that ships.
 */
class HardFilterCorpusTest {

    /** From `python3 docs/samples/simulate_filter.py`. */
    private static final int TOTAL = 1289;

    private static final int EXPECTED_PASSED = 239;

    private static final Map<FilterStage, Integer> EXPECTED_REMOVED = Map.of(
            FilterStage.OUT_OF_REACH, 717,
            FilterStage.NO_CORE_SKILL, 171,
            FilterStage.ROLE_OR_STACK, 115,
            FilterStage.ABROAD, 25,
            FilterStage.REMOTE_SHARE, 12,
            FilterStage.CONTRACT_FORM, 8,
            FilterStage.STALE, 2);

    private static final Validator VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    @TempDir
    static Path configDir;

    private static List<ExtractedOffer> offers;
    private static HardFilter filter;
    private static LocalDate newest;

    @BeforeAll
    static void extractAndConfigure() {
        Path root = ConfigFixtures.repositoryRoot();
        Path corpus = root.resolve("docs/samples/emails");
        Path operator = root.resolve("config");

        Assumptions.assumeTrue(
                Files.isDirectory(corpus),
                "docs/samples/emails is absent — the corpus is gitignored, see docs/SAMPLE-ANALYSIS.md");
        Assumptions.assumeTrue(
                Files.isRegularFile(operator.resolve("matching-rules.yaml"))
                        && Files.isRegularFile(operator.resolve("skill-profile.yaml")),
                "config/ is absent — the rules that produce these numbers name a home region and are gitignored");

        // Shipped pipeline and sources, the operator's rules and profile: the two files
        // that carry the lists the reference mirrors.
        shipped("pipeline.yaml");
        shipped("sources.yaml");
        copy(operator.resolve("matching-rules.yaml"), configDir.resolve("matching-rules.yaml"));
        copy(operator.resolve("skill-profile.yaml"), configDir.resolve("skill-profile.yaml"));

        var snapshot = ConfigFixtures.loaderFor(configDir, VALIDATOR, Map.of("INBOX_DIR", corpus.toString()))
                .load();
        var source = snapshot.sources().sources().stream()
                .filter(s -> s.id().equals("local-eml"))
                .findFirst()
                .orElseThrow();

        var extractor = new HtmlBlockExtractor();
        var mapper = new OfferMapper();
        offers = new ArrayList<>();
        for (RawDocument document : new FileSourceConnector(new ConfigProperties(configDir.toString(), null, null)).read(source, 0L)) {
            extractor.extract(document.html(), source.extraction()).stream()
                    .map(block -> mapper.map(block, source.extraction()))
                    .forEach(offers::add);
        }

        filter = new HardFilter(snapshot.rules(), snapshot.profile());
        newest = offers.stream()
                .map(ExtractedOffer::publishedOn)
                .filter(java.util.Objects::nonNull)
                .max(LocalDate::compareTo)
                .orElseThrow();
    }

    @Test
    void passesTheMeasuredShareOfTheCorpus() {
        var removed = judgeAll();
        int passed = TOTAL - removed.values().stream().mapToInt(Integer::intValue).sum();

        assertThat(offers).hasSize(TOTAL);
        assertThat(passed).isEqualTo(EXPECTED_PASSED);
        assertThat((double) passed / TOTAL * 100).isCloseTo(18.5, org.assertj.core.data.Offset.offset(0.05));
    }

    @Test
    void removesTheMeasuredCountAtEveryStage() {
        // ISC-42. A total alone cannot show a stage quietly dropping an offer without
        // counting it, or two stages both claiming the same one.
        assertThat(judgeAll()).containsExactlyInAnyOrderEntriesOf(EXPECTED_REMOVED);
    }

    @Test
    void everyOfferIsAccountedForExactlyOnce() {
        var removed = judgeAll();
        int sum = removed.values().stream().mapToInt(Integer::intValue).sum();
        assertThat(sum + EXPECTED_PASSED).isEqualTo(TOTAL);
    }

    private static Map<FilterStage, Integer> judgeAll() {
        Map<FilterStage, Integer> removed = new EnumMap<>(FilterStage.class);
        for (ExtractedOffer offer : offers) {
            var verdict = filter.judge(
                    new FilterCandidate(
                            0L,
                            offer.title(),
                            offer.description(),
                            offer.location(),
                            offer.tags(),
                            offer.publishedOn()),
                    newest);
            if (!verdict.passed()) {
                removed.merge(verdict.stage(), 1, Integer::sum);
            }
        }
        return removed;
    }

    private static void shipped(String name) {
        var source = ConfigSource.fromClasspath(name)
                .orElseThrow(() -> new IllegalStateException("no default ships for " + name));
        try {
            Files.writeString(configDir.resolve(name), source.content(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void copy(Path from, Path to) {
        try {
            Files.copy(from, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
