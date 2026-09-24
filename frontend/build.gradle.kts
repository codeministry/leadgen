/**
 * Gradle brackets the frontend so `./gradlew check` covers both languages, but it
 * does not manage the toolchain: bun is the package manager everywhere in this
 * house, and the Node Gradle plugin does not speak it. Plain `Exec` tasks are the
 * smaller wiring and keep `bun run <script>` working identically inside and
 * outside Gradle — `package.json` stays the single list of frontend commands.
 */
plugins {
    base
}

val frontendDir = layout.projectDirectory
val nodeModules = frontendDir.dir("node_modules")

fun bun(vararg args: String) = listOf("bun", *args)

val installDeps by tasks.registering(Exec::class) {
    description = "Installs the frontend dependencies with bun."
    group = "build setup"
    workingDir = frontendDir.asFile
    commandLine(bun("install", "--frozen-lockfile"))
    inputs.files(frontendDir.file("package.json"), frontendDir.file("bun.lock"))
    outputs.dir(nodeModules)
}

val lint by tasks.registering(Exec::class) {
    description = "ESLint and Stylelint, both at zero warnings."
    group = "verification"
    dependsOn(installDeps)
    workingDir = frontendDir.asFile
    commandLine(bun("run", "check:static"))
}

/**
 * The coverage configuration rather than the bare run: v8 coverage costs almost nothing
 * on a suite this size, and a report that only CI produces is a report nobody reads
 * before pushing. `bun run test` stays the fast loop.
 */
val test by tasks.registering(Exec::class) {
    description = "Vitest unit suite, with coverage."
    group = "verification"
    dependsOn(installDeps)
    workingDir = frontendDir.asFile
    commandLine(bun("run", "test:coverage"))
}

/**
 * The browser tier: every `*.browser.spec.ts` in headless Chromium. jsdom resolves neither
 * custom properties nor `oklch()`, so a contrast assertion there passes with the element
 * invisible; this is the only tier that can say no to a colour.
 */
val testBrowser by tasks.registering(Exec::class) {
    description = "Vitest browser suite in headless Chromium."
    group = "verification"
    dependsOn(installDeps)
    workingDir = frontendDir.asFile
    commandLine(bun("run", "test:browser"))
}

val buildFrontend by tasks.registering(Exec::class) {
    description = "Production build into frontend/dist."
    group = "build"
    dependsOn(installDeps)
    workingDir = frontendDir.asFile
    commandLine(bun("run", "build"))
    inputs.dir(frontendDir.dir("src"))
    inputs.files(frontendDir.file("package.json"), frontendDir.file("angular.json"))
    outputs.dir(frontendDir.dir("dist"))
}

tasks.named("check") { dependsOn(lint, test, testBrowser) }
tasks.named("assemble") { dependsOn(buildFrontend) }
tasks.named<Delete>("clean") { delete(frontendDir.dir("dist"), frontendDir.dir(".angular")) }
