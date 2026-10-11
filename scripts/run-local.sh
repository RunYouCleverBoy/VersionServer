#!/usr/bin/env bash
# Start the local container and open the UI in a browser.
set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

require_docker
ensure_env_file
load_runtime_env
warn_if_tls_missing

fresh=0
if data_is_fresh; then
    fresh=1
fi

if container_publishes_port; then
    echo "Version Server is already running."
    print_status
    open_browser
    exit 0
fi

ensure_port_available
echo "Starting Version Server..."
compose up -d --remove-orphans --wait
print_status
if [[ "$fresh" == 1 ]]; then
    echo "Seeded sign-in: admin / admin-pass"
fi
open_browser
