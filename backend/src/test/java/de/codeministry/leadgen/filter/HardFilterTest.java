package de.codeministry.leadgen.filter;

import static org.assertj.core.api.Assertions.assertThat;

import de.codeministry.leadgen.config.ConfigFixtures;
import de.codeministry.leadgen.config.model.MatchingRules;
import de.codeministry.leadgen.config.model.SkillProfile;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The stages, against a fictional rule set. The corpus numbers live in
 * {@link HardFilterCorpusTest}; this is about the mechanics, and the mechanics are where
 * the reference implementation was wrong three times over.
 */
class HardFilterTest {

    private static final Validator VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 1);

    @TempDir
    static Path configDir;

    private static HardFilter filter;

    @BeforeAll
    static void loadFictionalRules() {
        copyFixture("matching-rules.yaml");
        copyFixture("skill-profile.yaml");
        copyDefault("pipeline.yaml");
        copyDefault("sources.yaml");

        var snapshot = ConfigFixtures.loaderFor(configDir, VALIDATOR).load();
        filter = new HardFilter(snapshot.rules(), snapshot.profile());
    }

    @Test
    void acceptsAnOfferThatClearsEveryStage() {
        assertThat(judge(offer("Barista (m/w/d)", "Espresso und Handaufguss", "Musterstadt")).passed())
                .isTrue();
    }

    @Test
    void foldsUmlautsWithoutBreakingTheWord() {
        // The defect that started this: NFKD plus a filter that drops the combining mark
        // without removing it leaves "ko ln", which matches no city in any list. The two
        // cities nearest the operator's home base were being discarded.
        assertThat(TextFold.fold("Köln")).isEqualTo("koln");
        assertThat(TextFold.fold("Düsseldorf")).isEqualTo("dusseldorf");
        assertThat(TextFold.fold("Straße")).isEqualTo("strasse");
        assertThat(TextFold.fold("Alt-Musterstadt")).isEqualTo("alt musterstadt");
    }

    @Test
    void matchesKeywordsOnWordBoundariesAndNotAsSubstrings() {
        // "ANÜ" as a substring also hits Planung, Manufacturing and manuellen; in the
        // sample corpus that was 23 false rejections from one three-letter token.
        var innocent = offer("Barista (m/w/d)", "Espresso, mit sorgfältiger Planung", "Musterstadt");
        assertThat(judge(innocent).passed()).isTrue();

        var guilty = offer("Barista (m/w/d)", "Espresso, Einsatz per ANÜ", "Musterstadt");
        assertThat(judge(guilty).stage()).isEqualTo(FilterStage.CONTRACT_FORM);
    }

    @Test
    void foldsThePatternTheSameWayAsTheText() {
        // ".net" and "c#" were dead entries in the reference: the text no longer contained
        // "." or "#", so they matched nothing. Folding them makes them match — and word
        // boundaries stop the folded ".net" from also matching "Netzwerk".
        assertThat(judge(offer(".NET Entwickler", "Espresso", "Musterstadt")).stage())
                .isEqualTo(FilterStage.ROLE_OR_STACK);
        assertThat(judge(offer("C# Entwickler", "Espresso", "Musterstadt")).stage())
                .isEqualTo(FilterStage.ROLE_OR_STACK);
        assertThat(judge(offer("Barista Netzwerkpflege", "Espresso", "Musterstadt")).passed())
                .isTrue();
    }

    @Test
    void rejectsAbroadBeforeAnythingElse() {
        var verdict = judge(offer("Kobolderei in Auslandistan", "kein Espresso", "Auslandistan"));
        assertThat(verdict.stage()).isEqualTo(FilterStage.ABROAD);
        assertThat(verdict.reason()).contains("auslandistan");
    }

    @Test
    void rejectsAStatedRemoteShareBelowTheMinimum() {
        assertThat(judge(offer("Barista", "Espresso, 40 % remote", "Musterstadt")).stage())
                .isEqualTo(FilterStage.REMOTE_SHARE);
        assertThat(judge(offer("Barista", "Espresso, 80 % remote", "Musterstadt")).passed())
                .isTrue();
    }

    @Test
    void keepsAnOfferWithNoStatedRemoteShare() {
        // ISC-43. `accept_unknown` is true because the sources state a share in 8.8 % of
        // offers; rejecting the silent ones would throw away nine in ten.
        assertThat(judge(offer("Barista", "Espresso, nichts über Remote", "Beispielheim")).passed())
                .isTrue();
    }

    @Test
    void keepsARemoteOfferWhoseLocationIsNowhereNear() {
        assertThat(judge(offer("Barista", "Espresso, homeoffice möglich", "Irgendwo")).passed())
                .isTrue();
    }

    @Test
    void rejectsAnOfferThatIsNeitherRemoteNorNear() {
        var verdict = judge(offer("Barista", "Espresso vor Ort", "Irgendwo"));
        assertThat(verdict.stage()).isEqualTo(FilterStage.OUT_OF_REACH);
        assertThat(verdict.reason()).contains("Irgendwo");
    }

    @Test
    void rejectsAnOfferNamingNoCoreSkill() {
        assertThat(judge(offer("Hufschmied", "Beschlagen von Pferden", "Musterstadt")).stage())
                .isEqualTo(FilterStage.NO_CORE_SKILL);
    }

    @Test
    void countsAnAliasAsTheCoreSkill() {
        // Eight bare skill names would answer "no" to an ad asking for Springboot or k8s.
        // Over the corpus the aliases are worth twelve offers.
        assertThat(judge(offer("Fachkraft", "Espresso-Zubereitung erwünscht", "Musterstadt")).passed())
                .isTrue();
    }

    @Test
    void findsACoreSkillInTheTagsAsWellAsTheText() {
        var tagged = new FilterCandidate(
                1L, "Fachkraft (m/w/d)", "keine Details", "Musterstadt", List.of("Drachenzähmen"), TODAY);
        assertThat(filter.judge(tagged, TODAY).passed()).isTrue();
    }

    @Test
    void rejectsAnOfferOlderThanTheFreshnessLimit() {
        var stale = new FilterCandidate(
                1L, "Barista", "Espresso", "Musterstadt", List.of(), TODAY.minusDays(22));
        assertThat(filter.judge(stale, TODAY).stage()).isEqualTo(FilterStage.STALE);

        var fresh = new FilterCandidate(
                1L, "Barista", "Espresso", "Musterstadt", List.of(), TODAY.minusDays(21));
        assertThat(filter.judge(fresh, TODAY).passed()).isTrue();
    }

    @Test
    void neverLooksAtTheRate() {
        // ISC-44. `rate.apply_after` is `enrichment` and the config loader refuses any
        // other value: the sources state a rate in 0.0 % of offers, so a rate rule here
        // would discard everything or nothing. An offer far below the floor survives.
        var cheap = offer("Barista", "Espresso, 5 €/h, mehr ist nicht drin", "Musterstadt");
        assertThat(judge(cheap).passed()).isTrue();
    }

    private static FilterVerdict judge(FilterCandidate candidate) {
        return filter.judge(candidate, TODAY);
    }

    private static FilterCandidate offer(String title, String description, String location) {
        return new FilterCandidate(1L, title, description, location, List.of(), TODAY);
    }

    private static void copyFixture(String name) {
        try (var in = HardFilterTest.class.getResourceAsStream("/filter/" + name)) {
            Files.writeString(
                    configDir.resolve(name),
                    new String(in.readAllBytes(), StandardCharsets.UTF_8),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The two files this test does not care about, taken from the shipped defaults. */
    private static void copyDefault(String name) {
        try (var in = HardFilterTest.class.getResourceAsStream("/leadgen/" + name)) {
            Files.writeString(
                    configDir.resolve(name),
                    new String(in.readAllBytes(), StandardCharsets.UTF_8),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
