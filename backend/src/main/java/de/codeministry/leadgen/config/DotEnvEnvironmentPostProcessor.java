package de.codeministry.leadgen.config;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * Puts `.env` into Spring's environment, so the file means one thing everywhere.
 *
 * <p><b>The failure this ends.</b> `.env` used to be read by this application's own
 * placeholder resolver and by nothing else, so a variable only `application.yaml` names —
 * `LEADGEN_CONFIG_DIR`, `POSTGRES_PASSWORD`, `SERVER_PORT` — could be written in the file,
 * be visibly there, and have no effect whatsoever. Compose passes those same names as real
 * environment variables, so the container behaved as written and a local run did not. The
 * value was in the file and the service said it was missing.
 *
 * <p><b>It behaves like the environment, only weaker.</b> The source is registered directly
 * below `systemEnvironment`, so a real exported variable still wins and nothing that used to
 * decide a value stops deciding it — and it is a {@link SystemEnvironmentPropertySource}, so
 * `SPRING_DATASOURCE_URL` maps to `spring.datasource.url` exactly as an exported variable
 * would. Everything below it, `application.yaml` included, now loses to the file, which is
 * the whole point: that is where the machine-specific values are meant to live.
 *
 * <p>Registered through `META-INF/spring.factories` rather than as a bean, because it has to
 * run before the environment is bound — and through the factories file rather than a hook in
 * `main`, so a test context, a jar and an IDE run all see the same file.
 */
public class DotEnvEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        // Only the assignments that carry a value: an empty one is a non-statement, and
        // letting it beat a default would break a run over a copied template.
        Map<String, Object> values = new LinkedHashMap<>(DotEnv.load().values());
        if (values.isEmpty()) {
            return;
        }

        var source = new SystemEnvironmentPropertySource(DotEnv.FILE_NAME, values);
        var sources = environment.getPropertySources();
        if (sources.contains(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)) {
            sources.addAfter(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, source);
        } else {
            sources.addLast(source);
        }
    }
}
