package de.codeministry.leadgen.filter;

import de.codeministry.leadgen.config.model.MatchingRules;
import de.codeministry.leadgen.config.model.SkillProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The deterministic knockouts, applied in order, with no model and no network.
 *
 * <p>This is the stage that makes the tool affordable: it removes roughly four offers in
 * five for free, and only what survives costs a language model call. Without a model the
 * tool must still run, only weaker — so nothing here may depend on one.
 *
 * <p><b>Every list comes from configuration or from the profile.</b> Not one keyword is
 * written in Java, for the same reason no CSS selector is: a new market, a different home
 * region or another set of anti-roles is an edit to a YAML file, not a release.
 * `docs/samples/simulate_filter.py` is the reference these lists mirror, and ISC-41 is
 * what proves the two still agree.
 *
 * <p>Instances are immutable and built once per configuration snapshot: the patterns are
 * compiled here rather than per offer, which is the difference between compiling six
 * dozen regexes once and compiling them 1289 times.
 */
public final class HardFilter {

    private final List<Pattern> foreign;
    private final List<Pattern> remoteTokens;
    private final List<Pattern> nearCities;
    private final List<Pattern> rejectedTitles;
    private final List<Pattern> coreSkills;
    private final List<Pattern> rejectedContracts;
    private final Pattern remotePercent;
    private final int minRemotePercent;

    public HardFilter(MatchingRules rules, SkillProfile profile) {
        MatchingRules.HardFilters filters = rules.hardFilters();
        MatchingRules.HardFilters.Location location = filters.location();

        this.foreign = compile(location.rejectKeywords());
        this.nearCities = compile(location.onsiteCities());
        this.remoteTokens = compile(remoteTokensOf(filters.remote()));
        this.rejectedTitles = compile(filters.role() == null ? List.of() : filters.role().rejectedTitleKeywords());
        this.rejectedContracts = compile(filters.contract() == null ? List.of() : filters.contract().rejected());
        this.coreSkills = compile(coreSkillsOf(profile));
        this.minRemotePercent = filters.remote() == null ? 0 : filters.remote().minRemotePercent();
        this.remotePercent = Pattern.compile("(\\d{1,3})\\s*%\\s*remote");
    }

    /**
     * <p>Nothing here reads a date. The filter asks what an advert says and where it is;
     * how old it is decides whether it is on the working list, which is
     * {@code archive.ArchiveService}'s question and not a verdict about the advert.
     */
    public FilterVerdict judge(FilterCandidate offer) {
        String location = TextFold.fold(offer.location());
        String title = TextFold.fold(offer.title());
        String blob = title + " " + TextFold.fold(offer.description());
        String tags = offer.tags() == null
                ? ""
                : String.join(" ", offer.tags().stream().map(TextFold::fold).toList());

        String abroad = firstMatch(foreign, location);
        if (abroad != null) {
            return FilterVerdict.rejected(FilterStage.ABROAD, "location names " + abroad);
        }

        Matcher percent = remotePercent.matcher(blob);
        if (percent.find()) {
            int stated = Integer.parseInt(percent.group(1));
            if (stated < minRemotePercent) {
                return FilterVerdict.rejected(
                        FilterStage.REMOTE_SHARE, stated + " % remote, the minimum is " + minRemotePercent + " %");
            }
        }

        // An unstated share is not a rejection: `remote.accept_unknown` is true because
        // the sources state one in 8.8 % of offers. It survives and is flagged instead.
        boolean remote = matches(remoteTokens, location) || matches(remoteTokens, blob);
        // `min_remote_percent: 0` means no remote share is required, which means being on
        // site is acceptable — and then it is acceptable anywhere, so the city list stops
        // applying. Without this the two settings pull in opposite directions: the share
        // rule says "on site is fine" and the reach rule still rejects every town that is
        // not on a hand-written list. Measured on the archive: 145 of 254 offers died here
        // while the share was set to zero, none of them stating a remote share at all.
        //
        // Above zero the list is back, and it has to be: needing 40 % remote means being on
        // site for the other 60 %, and that part has to be somewhere reachable.
        if (minRemotePercent > 0 && !remote && !matches(nearCities, location)) {
            return FilterVerdict.rejected(
                    FilterStage.OUT_OF_REACH,
                    offer.location() == null ? "no location stated" : offer.location() + " is not within reach");
        }

        String role = firstMatch(rejectedTitles, title);
        if (role != null) {
            return FilterVerdict.rejected(FilterStage.ROLE_OR_STACK, "title names " + role);
        }

        if (!matches(coreSkills, tags) && !matches(coreSkills, blob)) {
            return FilterVerdict.rejected(FilterStage.NO_CORE_SKILL, "names none of the core skills");
        }

        String contract = firstMatch(rejectedContracts, blob);
        if (contract != null) {
            return FilterVerdict.rejected(FilterStage.CONTRACT_FORM, "names " + contract);
        }

        return FilterVerdict.accepted();
    }

    /**
     * The remote share is rarely stated, so the rules derive it from the location and the
     * title. Only the keyword derivations are read here — a regex derivation states a
     * number, and that number is what the percent rule above already looks for.
     */
    private static List<String> remoteTokensOf(MatchingRules.HardFilters.Remote remote) {
        if (remote == null || remote.deriveFrom() == null) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        remote.deriveFrom().stream()
                .map(MatchingRules.HardFilters.Remote.Derivation::containsAny)
                .filter(java.util.Objects::nonNull)
                .forEach(tokens::addAll);
        return tokens;
    }

    /**
     * Core skills and every spelling an ad uses for them. The aliases are the point: an
     * offer asking for "Springboot", "Spring Data" or "k8s" names a core skill, and a
     * list of eight bare names would answer no. Measured over the corpus, the aliases are
     * worth twelve offers.
     */
    private static List<String> coreSkillsOf(SkillProfile profile) {
        if (profile == null || profile.core() == null) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        for (SkillProfile.Skill skill : profile.core()) {
            tokens.add(skill.skill());
            if (skill.aliases() != null) {
                tokens.addAll(skill.aliases());
            }
        }
        return tokens;
    }

    private static List<Pattern> compile(List<String> keywords) {
        if (keywords == null) {
            return List.of();
        }
        return keywords.stream().map(TextFold::keyword).filter(java.util.Objects::nonNull).toList();
    }

    private static boolean matches(List<Pattern> patterns, String text) {
        return patterns.stream().anyMatch(p -> p.matcher(text).find());
    }

    /** The matched text, so a rejection can say which word decided it. */
    private static String firstMatch(List<Pattern> patterns, String text) {
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(text);
            if (matcher.find()) {
                return matcher.group();
            }
        }
        return null;
    }
}
