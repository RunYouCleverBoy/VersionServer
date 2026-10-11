#!/bin/sh
set -eu

mkdir -p "$VERSION_SERVER_STORAGE" "$VERSION_SERVER_LOG_DIR" "$(dirname "$VERSION_SERVER_JSON")"

exec java -jar /opt/version-server/version-server.jar
