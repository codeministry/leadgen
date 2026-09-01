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
    implementation(libs.jsoup)
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
 * No `.env` handling here on purpose. It used to live in a `bootRun` hook, which
 * meant launching the very same configuration from an IDE silently saw none of it — the
 * symptom was "the value is in the file, the service says it is missing", with an error
 * naming the right setting and no reason. `PlaceholderResolver` reads the file itself now,
 * so every start path behaves identically.
 */
