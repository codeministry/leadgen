/**
 * The root build only brackets the two real modules. `base` is what gives the
 * root the lifecycle tasks (`build`, `check`, `clean`) that fan out into
 * `:backend` and `:frontend`, so one `./gradlew check` covers both languages.
 *
 * Nothing is configured for the subprojects from here: `:frontend` has no Java
 * source, and a `subprojects { apply(plugin = "java") }` block would give it an
 * empty `src/main/java` and a toolchain it does not need.
 */
plugins {
    base
}
