package de.codeministry.leadgen.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The watcher is driven by calling its poll directly. Waiting on the scheduler would
 * only measure the scheduler.
 */
class ConfigWatcherTest {

    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    @TempDir
    Path configDir;

    private ConfigLoader loader;
    private ConfigRegistry registry;
    private ConfigWatcher watcher;

    @BeforeEach
    void setUp() {
        ConfigFixtures.materialize(configDir);
        loader = ConfigFixtures.loaderFor(configDir, VALIDATOR);
        registry = new ConfigRegistry(loader);
        watcher = new ConfigWatcher(registry, loader);
    }

    @AfterAll
    static void closeFactory() {
        FACTORY.close();
    }

    @Test
    void appliesAValidChange() throws IOException {
        assertThat(registry.snapshot().rules().hardFilters().remote().minRemotePercent()).isEqualTo(80);

        rewrite("matching-rules.yaml", "min_remote_percent: 80", "min_remote_percent: 60");
        settle();

        assertThat(registry.snapshot().rules().hardFilters().remote().minRemotePercent()).isEqualTo(60);
    }

    @Test
    void keepsTheLastGoodConfigurationWhenTheChangeIsInvalid() throws IOException {
        var before = registry.snapshot();

        rewrite("matching-rules.yaml", "apply_after: enrichment", "apply_after: hard_filter");
        settle();

        // A rejected reload must not take the running tool down, and must not leave it
        // running on half a configuration either.
        assertThat(registry.snapshot()).isSameAs(before);
    }

    @Test
    void waitsOneCycleBeforeActingOnAChange() throws IOException {
        rewrite("matching-rules.yaml", "min_remote_percent: 80", "min_remote_percent: 60");

        watcher.pollForChanges(); // first sighting only

        assertThat(registry.snapshot().rules().hardFilters().remote().minRemotePercent()).isEqualTo(80);
    }

    @Test
    void ignoresAChangeWhenHotReloadIsOff() throws IOException {
        rewrite("application.yaml", "hot_reload: true", "hot_reload: false");
        settle();
        var withHotReloadOff = registry.snapshot();
        assertThat(withHotReloadOff.application().rules().hotReload()).isFalse();

        rewrite("matching-rules.yaml", "min_remote_percent: 80", "min_remote_percent: 60");
        settle();

        assertThat(registry.snapshot()).isSameAs(withHotReloadOff);
    }

    @Test
    void doesNothingWhileTheFilesAreUntouched() {
        var before = registry.snapshot();
        settle();
        assertThat(registry.snapshot()).isSameAs(before);
    }

    /** Two polls: the first sees the change, the second confirms it has settled. */
    private void settle() {
        watcher.pollForChanges();
        watcher.pollForChanges();
    }

    private void rewrite(String file, String from, String to) throws IOException {
        Path path = configDir.resolve(file);
        String content = Files.readString(path);
        assertThat(content).as("fixture must contain %s", from).contains(from);
        Files.writeString(path, content.replace(from, to));
    }
}
