# Runtime-only image. The fat jar is built by `sbt assembly` (locally or in CI, where the GitHub
# Packages token is available) and lands at target/dicechess-bot-gcp.jar — see .dockerignore — so
# this Docker build needs no registry credentials. Base is a JRE >= 25 because dicechess-bot-runtime
# is compiled to Java 25 bytecode.
FROM eclipse-temurin:25-jre

WORKDIR /app
COPY target/dicechess-bot-gcp.jar /app/app.jar

# Cloud Run routes requests to $PORT (default 8080); Main reads it. EXPOSE is documentation only.
ENV PORT=8080
EXPOSE 8080

# MaxRAMPercentage lets the JVM size its heap to the Cloud Run instance's configured memory; the
# default G1 collector is fine for the short-lived, CPU-bound rollout workload.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
