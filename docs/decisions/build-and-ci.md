<img src="../brand/leadgen.png" alt="leadGEN / AI" height="28">

# The build's own rules

What `./gradlew check` and `build` have to do in a particular order, and why — the decisions
that live in the build files rather than in the application.

## The three test suites take turns

`:frontend:test` runs after `:backend:test`, and `:frontend:testBrowser` after both
(`mustRunAfter` in `frontend/build.gradle.kts`). Lint and the production build still run
beside them.

- **Measured, not guessed.** On 2026-10-10 two full `./gradlew build` runs went red on two
  different tests: `ChatRedactionTest` saw a chat stream end without `done`, and
  `chat-drawer.browser.spec.ts` failed on an `AbortError` with no application stack. The backend
  suite alone was green three times in a row, and with the ordering two full builds were green.
  The same overload explains the CI `testBrowser` failures at the v0.5.2 and v0.5.3 releases —
  a Chromium session that never connected, a dynamically imported module that failed to fetch.
- **Why these three.** All of them measure time: Testcontainers and Spring contexts in the
  backend, timers and transitions in the unit tier, a real browser in the last. Under
  `org.gradle.parallel` they starved each other into failing on the clock rather than on a bug.
  The unit and browser tiers also share the Angular and Vite caches under `frontend/`, the race
  CI's warm-up step works around. Running the two in turn takes that pair out of it; the
  production build still uses the same cache beside them, so it is narrowed, not removed.
- **What it costs.** About two minutes per full build (8:25 before, 10:27 and 11:00 after, on the
  developer machine). A red backend suite now hides the frontend results unless `--continue` is
  passed.
- **Not taken, for now.** A Gradle `BuildService` with `maxParallelUsages = 1` that every
  timing-sensitive task uses would protect a fourth such task without another edge, and would
  not tie the frontend build to a backend task path. With three tasks the edges are the smaller
  wiring; a fourth suite is the moment to switch.
