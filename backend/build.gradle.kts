plugins {
    java
    jacoco
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.spotless)
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
    // All four configurations, not two: without the `test*` pair `main` compiles and the
    // test sources fail with "cannot find symbol: getX()".
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // JDBC and not JPA: the pipeline writes offers in batches and upserts them by
    // `ON CONFLICT`, which is one statement of plain SQL against a schema Flyway owns.
    // An ORM would add a mapping layer over Postgres arrays for no gain here.
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    // Reading .eml files now, the IMAP connector in the next step — same library.
    implementation("org.springframework.boot:spring-boot-starter-mail")
    // Spring Integration for the mail receiver, and the starter with it. The mail module
    // alone is not enough: `ImapMailReceiver` resolves `integrationEvaluationContext` from
    // the bean factory on init, so without `@EnableIntegration`'s infrastructure beans it
    // fails with "No such bean" — measured, not assumed.
    //
    // What is deliberately NOT used is the flow half: no channel, no poller, no inbound
    // channel adapter. A run here is a synchronous pull that has to come back with per-source
    // counts, and an adapter pushing messages into a channel has nothing to hand back.
    implementation("org.springframework.boot:spring-boot-starter-integration")
    implementation("org.springframework.integration:spring-integration-mail")
    // The BOM governs the two starters' versions and everything they drag in, so the
    // Anthropic and OpenAI client SDKs are pinned by one line rather than by three.
    implementation(platform(libs.spring.ai.bom))
    // Naming two vendors here is deliberate and is the one place the "nothing is wired in"
    // rule does not reach: a dependency is not a value. No host, key or model name appears
    // in any committed file, and `base_url` still decides who answers.
    //
    // The model modules, deliberately NOT the `spring-ai-starter-model-*` ones. The judge is
    // built per run from the hot-reloadable snapshot, so the starters' auto-configuration has
    // nothing to configure — and it is not merely useless: it builds every model the module
    // knows at startup, so the OpenAI one failed the whole context with "At least one
    // credential source must be specified" while trying to construct an *audio speech* model
    // this application will never call. Measured, not guessed.
    implementation(libs.spring.ai.chat)
    implementation(libs.spring.ai.anthropic)
    implementation(libs.spring.ai.openai)
    implementation(libs.jsoup)
    implementation(libs.freemarker)
    implementation(libs.flexmark.html2md)
    // The three config files in config/local are read by this application, not by
    // Spring: they are data with their own schema, not Spring properties.
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

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
    // A real IMAP server in-process. The UID semantics this connector depends on cannot
    // be faked convincingly, and they are exactly where the mistakes are.
    testImplementation(libs.greenmail.junit5)
    testImplementation(libs.wiremock.standalone)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

/**
 * Formatting and the SPDX header are enforced, not remembered. `spotlessCheck` hangs off
 * `check`, so a file without the header fails the same gate a failing test does — which is
 * the only way a licence header survives contact with a project over time.
 *
 * The header sits in a file rather than inline: `licenseHeaderFile` compares the whole
 * block, and a header maintained in two places drifts the first time the year changes.
 */
spotless {
    java {
        target("src/*/java/**/*.java")
        palantirJavaFormat(libs.versions.palantirJavaFormat.get())
        removeUnusedImports()
        formatAnnotations()
        licenseHeaderFile(rootProject.file("gradle/spotless/java-license-header.txt"))
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint()
    }
}

/**
 * Coverage is measured and published, but nothing fails on it. A threshold set on the day
 * the tool is introduced is a threshold somebody disables the first time it is
 * inconvenient; the number is worth having as a trend long before it is worth having as a
 * gate. The XML report exists for CI to upload, the HTML one for a person to read.
 */
jacoco {
    toolVersion = libs.versions.jacoco.get()
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = true
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestReport)
}

/**
 * No `.env` handling here on purpose. It used to live in a `bootRun` hook, which
 * meant launching the very same configuration from an IDE silently saw none of it — the
 * symptom was "the value is in the file, the service says it is missing", with an error
 * naming the right setting and no reason. `PlaceholderResolver` reads the file itself now,
 * so every start path behaves identically.
 */
