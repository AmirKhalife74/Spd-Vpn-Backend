FROM eclipse-temurin:17-jdk-jammy AS build

WORKDIR /workspace

COPY gradlew gradlew.bat ./
COPY gradle ./gradle
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY prober/build.gradle.kts ./prober/build.gradle.kts
COPY src ./src

RUN chmod +x gradlew \
    && ./gradlew --no-daemon clean test buildFatJar


FROM eclipse-temurin:17-jre-jammy

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system spdvpn \
    && useradd --system \
        --gid spdvpn \
        --home-dir /app \
        --shell /usr/sbin/nologin \
        spdvpn

WORKDIR /app

COPY --from=build \
    /workspace/build/libs/spd-vpn-backend-all.jar \
    /app/app.jar

RUN mkdir -p /app/data \
    && chown -R spdvpn:spdvpn /app

USER spdvpn

EXPOSE 8080
VOLUME ["/app/data"]

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD curl --fail --silent --show-error \
    http://127.0.0.1:8080/health/live >/dev/null || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
