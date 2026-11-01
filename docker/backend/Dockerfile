# ─────────────────────────────────────────────────────────────────────────────
# Backend Dockerfile — Multi-stage build
# Stage 1: Build with Maven
# Stage 2: Minimal JRE runtime image
# ─────────────────────────────────────────────────────────────────────────────

# ── Stage 1: Build ────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /workspace

# Copy Maven wrapper and pom first (layer cache: dependencies only re-downloaded when pom changes)
COPY .mvn/ .mvn/
COPY mvnw mvnw
COPY pom.xml pom.xml

# Make wrapper executable and download dependencies (offline-friendly layer)
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B -q

# Copy source and build (skip tests — tests run in CI separately)
COPY src/ src/
RUN ./mvnw package -B -q -DskipTests

# ── Stage 2: Runtime ──────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime

# Non-root user for security
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app

# Copy the built jar from the build stage
COPY --from=build /workspace/target/*.jar app.jar

# Ensure non-root ownership
RUN chown appuser:appgroup app.jar

USER appuser

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
