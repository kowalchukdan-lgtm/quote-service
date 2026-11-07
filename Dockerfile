# ---- Java for the front end's API client generator, on the build machine's platform ----
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jre AS jre

# ---- Front-end build stage ----
# The output (HTML, JS, CSS) is the same on every platform, so build natively: no emulation on
# Apple chips. Java comes from the official Temurin image: both are glibc images, so it runs as is.
FROM --platform=$BUILDPLATFORM node:24-bookworm-slim AS frontend
COPY --from=jre /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"
WORKDIR /app/frontend

# Dependencies and the generator jar first, so they're cached until the lockfile or generator change
COPY frontend/package.json frontend/package-lock.json frontend/openapitools.json ./
RUN --mount=type=cache,target=/root/.npm npm ci && npx openapi-generator-cli version

# The contract the API client is generated from, then the app itself
COPY src/main/resources/openapi/quote-api.yaml /app/src/main/resources/openapi/quote-api.yaml
COPY frontend ./
RUN npm run build

# ---- Build stage ----
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk AS build
WORKDIR /app

# Download dependencies first so they're cached between builds
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN ./mvnw -B -q dependency:go-offline

# Build the app (tests run in CI, not here). The UI goes into the jar's static resources, so the
# same origin serves it and /v1.
COPY src src
COPY --from=frontend /app/frontend/dist/quote-frontend/browser src/main/resources/static
RUN ./mvnw -B -q -DskipTests package && cp target/*.jar app.jar

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre
WORKDIR /app

ARG AI_AGENT_VERSION=3.7.6
ADD --chmod=644 https://repo1.maven.org/maven2/com/microsoft/azure/applicationinsights-agent/${AI_AGENT_VERSION}/applicationinsights-agent-${AI_AGENT_VERSION}.jar /app/applicationinsights-agent.jar

# Run as a non-root user
RUN useradd --system --uid 1001 appuser
USER appuser

COPY --from=build /app/app.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
