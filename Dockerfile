# ---- Build stage ----
FROM eclipse-temurin:21-jdk AS build
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

# Run as a non-root user
RUN useradd --system --uid 1001 appuser
USER appuser

COPY --from=build /app/app.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
