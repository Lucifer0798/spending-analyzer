# syntax=docker/dockerfile:1

# Builds the whole app — React client compiled and packaged inside the Spring Boot jar — so the
# result is one image serving one port. The frontend build is driven by the pom's `frontend`
# profile rather than a separate Node stage, so there is a single definition of how the client
# is built and `./mvnw -Pfrontend package` produces the same artifact outside Docker.

FROM eclipse-temurin:25-jdk AS build
WORKDIR /src

COPY client client
COPY server-springboot server-springboot

WORKDIR /src/server-springboot
# Tests run in CI against the full matrix; re-running them here would only slow image builds.
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -ntp -Pfrontend -DskipTests clean package


FROM eclipse-temurin:25-jre AS runtime

# Runs unprivileged: nothing in the app needs root, and the only writable path it wants is /data.
RUN groupadd --system app && useradd --system --gid app --home-dir /app app \
    && mkdir -p /app /data \
    && chown -R app:app /app /data

WORKDIR /app
COPY --from=build --chown=app:app /src/server-springboot/target/spending-analyzer.jar app.jar

USER app

# The SQLite file lives on a volume so the database survives `docker rm`.
ENV SPENDING_ANALYZER_DB=/data/spending-analyzer.sqlite
VOLUME ["/data"]

EXPOSE 4000
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
