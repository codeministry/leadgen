package de.codeministry.leadgen.enrich;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;

/**
 * Whether a path may be fetched, per host, from that host's {@code /robots.txt}.
 *
 * <p>Deliberately small: this reads {@code User-agent}, {@code Disallow} and
 * {@code Allow}, longest-match wins, and nothing else. No crawl-delay, no sitemaps, no
 * wildcards beyond a trailing {@code *}. A crawler that fetches one page per offer, a few
 * dozen a day, does not need the rest — and a large implementation of a small need is how
 * a stage that leaves the machine grows behaviour nobody reviewed.
 *
 * <p><b>Unreachable means allowed.</b> That is the convention, and the alternative is
 * worse: a host whose robots.txt times out would otherwise silently stop being enriched,
 * and the offers would look merely incomplete.
 */
@Slf4j
public final class RobotsPolicy {

    private final Function<URI, String> fetchRobots;
    private final Map<String, List<Rule>> perHost = new ConcurrentHashMap<>();

    public RobotsPolicy(Function<URI, String> fetchRobots) {
        this.fetchRobots = fetchRobots;
    }

    public boolean allows(URI target, String userAgent) {
        String host = target.getHost();
        if (host == null) {
            return false;
        }
        List<Rule> rules = perHost.computeIfAbsent(host, h -> load(target, userAgent));
        String path = target.getRawPath() == null || target.getRawPath().isEmpty() ? "/" : target.getRawPath();

        Rule longest = null;
        for (Rule rule : rules) {
            if (path.startsWith(rule.prefix()) && (longest == null || rule.prefix().length() > longest.prefix().length())) {
                longest = rule;
            }
        }
        return longest == null || longest.allowed();
    }

    private List<Rule> load(URI target, String userAgent) {
        URI robots = target.resolve("/robots.txt");
        String body;
        try {
            body = fetchRobots.apply(robots);
        } catch (RuntimeException e) {
            log.debug("robots.txt for {} could not be read ({}); treating the host as open", robots, e.toString());
            return List.of();
        }
        if (body == null) {
            return List.of();
        }
        return parse(body, userAgent);
    }

    /**
     * The rules that apply to us: the group naming our agent if there is one, otherwise
     * the {@code *} group. A file that names neither leaves the host open.
     */
    static List<Rule> parse(String body, String userAgent) {
        String agent = userAgent == null ? "*" : userAgent.toLowerCase(Locale.ROOT);
        List<Rule> forAgent = new ArrayList<>();
        List<Rule> forEveryone = new ArrayList<>();
        boolean inAgentGroup = false;
        boolean inWildcardGroup = false;

        for (String raw : body.split("\\R")) {
            String line = raw.split("#", 2)[0].trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split(":", 2);
            if (parts.length < 2) {
                continue;
            }
            String key = parts[0].trim().toLowerCase(Locale.ROOT);
            String value = parts[1].trim();

            if ("user-agent".equals(key)) {
                String named = value.toLowerCase(Locale.ROOT);
                inWildcardGroup = "*".equals(named);
                inAgentGroup = !named.isEmpty() && agent.startsWith(named);
                continue;
            }
            boolean disallow = "disallow".equals(key);
            if (!disallow && !"allow".equals(key)) {
                continue;
            }
            // An empty Disallow means "nothing is disallowed" and is not a rule.
            if (disallow && value.isEmpty()) {
                continue;
            }
            Rule rule = new Rule(value.endsWith("*") ? value.substring(0, value.length() - 1) : value, !disallow);
            if (inAgentGroup) {
                forAgent.add(rule);
            } else if (inWildcardGroup) {
                forEveryone.add(rule);
            }
        }
        return forAgent.isEmpty() ? forEveryone : forAgent;
    }

    record Rule(String prefix, boolean allowed) {}
}
