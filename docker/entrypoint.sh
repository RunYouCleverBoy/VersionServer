#!/bin/sh
set -eu

mkdir -p "$VERSION_SERVER_STORAGE" "$VERSION_SERVER_LOG_DIR" "$(dirname "$VERSION_SERVER_JSON")"

if [ "$(id -u)" = 0 ]; then
    chown -R app:app /var/lib/version-server
    exec setpriv --reuid=app --regid=app --init-groups --inh-caps=-all \
        /opt/version-server/.venv/bin/python -m versionserver
fi

exec /opt/version-server/.venv/bin/python -m versionserver
