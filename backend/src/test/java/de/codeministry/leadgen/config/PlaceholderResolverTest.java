package de.codeministry.leadgen.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class PlaceholderResolverTest {

    private final PlaceholderResolver resolver =
            new PlaceholderResolver(Map.of("SET", "value", "EMPTY", "")::get);

    @Test
    void substitutesFromTheEnvironment() {
        assertThat(resolver.resolve("host: ${SET}")).isEqualTo("host: value");
    }

    @Test
    void fallsBackToTheDefaultAfterTheColon() {
        assertThat(resolver.resolve("port: ${MISSING:993}")).isEqualTo("port: 993");
    }

    @Test
    void treatsAnEmptyVariableAsUnset() {
        // A .env copied from the template carries `IMAP_PORT=`. Letting that beat the
        // default would break a run for no reason.
        assertThat(resolver.resolve("port: ${EMPTY:993}")).isEqualTo("port: 993");
    }

    @Test
    void resolvesToEmptyWhenNeitherVariableNorDefaultExists() {
        // Whether empty is acceptable is a question about the field, and validation
        // answers it — the resolver stays dumb on purpose.
        assertThat(resolver.resolve("key: ${MISSING}")).isEqualTo("key: ");
    }

    @Test
    void leavesRegexBracesAlone() {
        String regex = "  title: { regex: \"(\\\\d{1,3})\\\\s*%\\\\s*remote\" }";
        assertThat(resolver.resolve(regex)).isEqualTo(regex);
    }

    @Test
    void leavesRegexBackreferencesAlone() {
        assertThat(resolver.resolve("set: \"$1\"")).isEqualTo("set: \"$1\"");
    }
}
