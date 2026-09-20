# syntax=docker/dockerfile:1
FROM gradle:9.4.1-jdk25 AS build
WORKDIR /workspace
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY backend/build.gradle.kts ./backend/
# The frontend module is declared in settings.gradle.kts, so its build file has to
# exist for configuration to succeed — its sources are not needed here.
COPY frontend/build.gradle.kts ./frontend/
RUN gradle :backend:dependencies --no-daemon --quiet || true
COPY backend/src ./backend/src
# `bootJar` runs `processAot` now that the GraalVM plugin is applied: the context is
# started here, at build time, and what it can decide ahead of the run is generated into
# the jar. It needs no database — `processAot` refreshes the bean factory and never
# instantiates the singletons, so neither Flyway nor `ConfigRegistry`'s constructor runs.
# `.dockerignore` excludes `.env`, which matters: AOT freezes condition outcomes.
RUN gradle :backend:bootJar --no-daemon -x test
# Extracted here and not in the runtime stage, because a layer that copies the fat jar in
# and a layer that unpacks it are both kept: deleting it afterwards does not shrink the
# image, it only hides it. Measured — doing this one stage later cost 126 MB.
# The jar carries its version in its name and the runtime path must not, so the thin jar
# is renamed once, here.
RUN java -Djarmode=tools -jar backend/build/libs/backend-*.jar extract --destination /extracted \
 && mv /extracted/backend-*.jar /extracted/app.jar \
 && test -f /extracted/app.jar && test -d /extracted/lib

FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S -G app app
# Extracted, not the fat jar. A JDK AOT cache — the layer that may be built on top of this
# image — applies only to this layout and is silently ignored against a fat jar: no error,
# no warning without `-Xlog:aot`, just no improvement. Shipping extracted means the runtime
# command is the same whether or not a cache is present.
COPY --from=build --chown=app:app /extracted/ /app/
USER app
EXPOSE 8080
# `spring.aot.enabled` is what makes the generated code above actually be used; without it
# the jar carries it and starts reflectively anyway.
ENTRYPOINT ["java", "-Dspring.aot.enabled=true", "-jar", "/app/app.jar"]
