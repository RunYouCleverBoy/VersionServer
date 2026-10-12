#!/usr/bin/env bash
# Rebuild the image from the current checkout and restart the container.
# The data directory from .env is left in place.
set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

require_docker
ensure_env_file
load_runtime_env
ensure_jwt_certificate
warn_if_tls_missing

if [[ -n "${VERSION_SERVER_DATA_DIR}" ]]; then
    echo "Rebuilding Version Server. Data stays in ${VERSION_SERVER_DATA_DIR}"
else
    echo "Rebuilding Version Server. Data stays in Docker volume version-server-data"
fi
compose up -d --build --remove-orphans --wait
print_status
