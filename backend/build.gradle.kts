plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

group = "de.codeministry"
version = "0.1.0"

/**
 * Java 21 through the toolchain rather than the ambient JDK: Gradle runs on
 * whatever JVM launched it, and pinning the toolchain is what makes this build
 * produce the same bytecode here and in CI.
 */
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    // Boot 4 split the integrations into their own modules. Without
    // `spring-boot-flyway` the migrations sit on the classpath and never run,
    // and the only symptom is Hibernate complaining about missing tables.
    implementation("org.springframework.boot:spring-boot-flyway")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Boot 4 moved the MockMvc slice into its own module. Without it there is no
    // @WebMvcTest, and the HTTP wiring stays the one layer nothing covers.
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

/**
 * The local run reads the untracked `.env` from the repository root — the same
 * file Docker Compose reads. One credential has one place for the whole stack,
 * and none of them ends up in `application.yaml`, which is committed.
 *
 * Deliberately here and not via `spring.config.import`: an import in
 * `application.yaml` would also apply to every test context, and a green test
 * run would then hang on a file nobody sees in the repo. That `bootRun` is
 * exempt is structural, not measured — `test` is a different task and never
 * sees these `environment` calls.
 *
 * A real environment variable beats the file: only unset keys are filled, so a
 * one-off run with an exported value needs no edit here. An empty value counts
 * as "not specified", not as "specified as empty".
 */
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    val dotEnv = rootProject.file(".env")
    if (dotEnv.exists()) {
        dotEnv.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
            .forEach { line ->
                val key = line.substringBefore("=").trim()
                val value = line.substringAfter("=").substringBefore("#").trim()
                if (value.isNotEmpty() && System.getenv(key) == null) {
                    environment(key, value)
                }
            }
    }
}
