# syntax=docker/dockerfile:1
FROM gradle:9.4.1-jdk21 AS build
WORKDIR /workspace
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY backend/build.gradle.kts ./backend/
# The frontend module is declared in settings.gradle.kts, so its build file has to
# exist for configuration to succeed — its sources are not needed here.
COPY frontend/build.gradle.kts ./frontend/
RUN gradle :backend:dependencies --no-daemon --quiet || true
COPY backend/src ./backend/src
RUN gradle :backend:bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S -G app app
COPY --from=build /workspace/backend/build/libs/*.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
