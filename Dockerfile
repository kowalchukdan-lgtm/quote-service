# ---- Build stage ----
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk AS build
WORKDIR /app

# Download dependencies first so they're cached between builds
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN ./mvnw -B -q dependency:go-offline

# Build the app (tests run in CI, not here)
COPY src src
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
