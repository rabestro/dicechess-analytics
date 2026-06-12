# syntax=docker/dockerfile:1

# Build stage — runs on the builder's native platform: JVM bytecode is
# architecture-independent, so only the runtime stage needs multi-arch.
FROM --platform=$BUILDPLATFORM eclipse-temurin:25-jdk AS build

ARG SBT_VERSION=1.12.11
ADD https://github.com/sbt/sbt/releases/download/v${SBT_VERSION}/sbt-${SBT_VERSION}.tgz /tmp/sbt.tgz
RUN tar -xzf /tmp/sbt.tgz -C /usr/local && ln -s /usr/local/sbt/bin/sbt /usr/local/bin/sbt

WORKDIR /build

# Resolve dependencies first for layer caching. The engine artifact comes from
# GitHub Packages, so sbt needs a read:packages token — passed as a BuildKit
# secret so it never lands in image layers.
ARG GITHUB_ACTOR=rabestro
COPY project/ project/
COPY build.sbt ./
RUN --mount=type=secret,id=github_token \
    GITHUB_TOKEN=$(cat /run/secrets/github_token) sbt update

COPY src/main/ src/main/
RUN --mount=type=secret,id=github_token \
    GITHUB_TOKEN=$(cat /run/secrets/github_token) sbt stage

# Runtime stage
FROM eclipse-temurin:25-jre

RUN groupadd --system app && useradd --system --gid app app
WORKDIR /app
COPY --from=build /build/target/universal/stage /app
USER app

EXPOSE 8000
ENTRYPOINT ["/app/bin/dicechess-analytics-backend"]
