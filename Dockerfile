# ============================================================================
# Stage 1: Build Stage (Eclipse Temurin 21 JDK)
# ============================================================================
FROM eclipse-temurin:21-jdk-jammy AS builder

WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B

COPY src/ src/
RUN ./mvnw clean package -DskipTests -B

# ============================================================================
# Stage 2: Production Runtime Stage (Non-Root User)
# ============================================================================
FROM eclipse-temurin:21-jre-jammy AS runtime

RUN groupadd --system --gid 10001 payshield && \
    useradd --system --uid 10001 --gid payshield --shell /usr/sbin/nologin payshield

WORKDIR /app

COPY --from=builder --chown=payshield:payshield /workspace/target/payshield-ai-1.0.0.jar /app/app.jar

USER payshield:payshield

EXPOSE 8080

ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/./urandom"

HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=5 \
    CMD curl -fsS http://localhost:8080/actuator/health/readiness || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
