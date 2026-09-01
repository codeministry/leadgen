package de.codeministry.leadgen.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

    @TempDir
    Path directory;

    @Test
    void readsValuesFromTheDotenvFile() throws IOException {
        Path file = directory.resolve(PlaceholderResolver.DOTENV);
        Files.writeString(
                file,
                """
                # a comment
                IMAP_HOST=imap.example.org
                IMAP_PORT=993   # a trailing comment is not part of the value
                DIGEST_DIR="./with spaces"
                EMPTY=
                """);

        Map<String, String> values = PlaceholderResolver.parse(file);

        assertThat(values).containsEntry("IMAP_HOST", "imap.example.org");
        assertThat(values).containsEntry("IMAP_PORT", "993");
        assertThat(values).containsEntry("DIGEST_DIR", "./with spaces");
        // An empty assignment is a non-statement, so the default in the YAML still applies.
        assertThat(values).doesNotContainKey("EMPTY");
    }
}
