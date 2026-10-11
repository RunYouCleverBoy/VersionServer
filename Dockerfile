# Version Server image.
# Host state is mounted on /var/lib/version-server (see docker-compose.yml).
# TLS certificates are not copied into this image. Mount them at run time.

FROM eclipse-temurin:22-jdk AS build
WORKDIR /src

COPY gradle/wrapper/ gradle/wrapper/
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
RUN chmod +x gradlew \
    && ./gradlew --no-daemon dependencies

COPY src src
COPY docs docs
RUN ./gradlew --no-daemon buildFatJar

FROM eclipse-temurin:22-jre
WORKDIR /var/lib/version-server

ENV VERSION_SERVER_STORAGE=/var/lib/version-server/files \
    VERSION_SERVER_JSON=/var/lib/version-server/store.json \
    VERSION_SERVER_LOG_DIR=/var/lib/version-server/logs \
    VERSION_SERVER_UPLOAD_MAX_BYTES=1073741824 \
    JAVA_TOOL_OPTIONS=-Xmx2g

COPY --from=build /src/build/libs/version-server.jar /opt/version-server/version-server.jar
COPY docker/entrypoint.sh /opt/version-server/entrypoint.sh
RUN chmod +x /opt/version-server/entrypoint.sh

EXPOSE 8080 8443

HEALTHCHECK --interval=5s --timeout=3s --start-period=25s --retries=20 \
    CMD bash -c 'echo >/dev/tcp/127.0.0.1/8080'

ENTRYPOINT ["/opt/version-server/entrypoint.sh"]
