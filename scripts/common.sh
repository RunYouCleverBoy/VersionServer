# Shared setup for the Version Server container scripts. Source this file.

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
    echo "Source this file from scripts/run-local.sh or scripts/update.sh." >&2
    exit 1
fi

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

require_docker() {
    if ! command -v docker >/dev/null 2>&1; then
        echo "Docker is required." >&2
        exit 1
    fi
    if ! docker compose version >/dev/null 2>&1; then
        echo "Docker Compose v2 is required (docker compose)." >&2
        exit 1
    fi
}

ensure_env_file() {
    if [[ -f "$root/.env" ]]; then
        return
    fi
    local secret
    secret="$(openssl rand -hex 32)"
    cat > "$root/.env" <<EOF
# Version Server container settings. Do not commit this file.

# Leave empty to keep files in the Docker volume "version-server-data"
# (mounted at /var/lib/version-server: files/, store.json, logs/).
# Or set an absolute host path to bind-mount that directory instead.
VERSION_SERVER_DATA_DIR=

# HMAC secret for login cookies. Changing it signs everyone out.
VERSION_SERVER_JWT_SECRET="${secret}"

# Host port published to container port 8080.
VERSION_SERVER_PORT=8080

# Max size of one multipart upload part, in bytes (1 GiB).
VERSION_SERVER_UPLOAD_MAX_BYTES=1073741824

# Uploads are buffered in memory, so the heap must exceed that limit.
JAVA_TOOL_OPTIONS=-Xmx2g

# TLS certificate. Leave these empty to serve HTTP and print setup steps.
# Create the files yourself. Do not commit them or copy them into the image.
# VERSION_SERVER_TLS_CERT=/certs/server.crt
# VERSION_SERVER_TLS_KEY=/certs/server.key
# VERSION_SERVER_TLS_DIR=/absolute/host/path/to/certs
# VERSION_SERVER_HTTPS_PORT=8443
VERSION_SERVER_TLS_CERT=
VERSION_SERVER_TLS_KEY=
VERSION_SERVER_TLS_KEY_PASSWORD=
VERSION_SERVER_TLS_KEYSTORE=
VERSION_SERVER_TLS_KEYSTORE_PASSWORD=
VERSION_SERVER_TLS_KEY_ALIAS=
VERSION_SERVER_TLS_DIR=
VERSION_SERVER_HTTPS_PORT=8443
EOF
    chmod 600 "$root/.env"
    echo "Created $root/.env with a new JWT secret."
    echo "Files will be stored in the Docker volume version-server-data."
}

load_runtime_env() {
    set -a
    # shellcheck disable=SC1091
    source "$root/.env"
    set +a

    : "${VERSION_SERVER_PORT:=8080}"
    : "${VERSION_SERVER_UPLOAD_MAX_BYTES:=1073741824}"
    : "${JAVA_TOOL_OPTIONS:=-Xmx2g}"
    : "${VERSION_SERVER_DATA_DIR:=}"
    : "${VERSION_SERVER_TLS_CERT:=}"
    : "${VERSION_SERVER_TLS_KEY:=}"
    : "${VERSION_SERVER_TLS_KEY_PASSWORD:=}"
    : "${VERSION_SERVER_TLS_KEYSTORE:=}"
    : "${VERSION_SERVER_TLS_KEYSTORE_PASSWORD:=}"
    : "${VERSION_SERVER_TLS_KEY_ALIAS:=}"
    : "${VERSION_SERVER_TLS_DIR:=}"
    VERSION_SERVER_TLS_DIR="${VERSION_SERVER_TLS_DIR%/}"
    : "${VERSION_SERVER_HTTPS_PORT:=8443}"

    if [[ -z "${VERSION_SERVER_JWT_SECRET:-}" || "${VERSION_SERVER_JWT_SECRET}" == "dev-only-change-me" ]]; then
        echo "Set VERSION_SERVER_JWT_SECRET in .env to a long random value (see .env.example)." >&2
        exit 1
    fi

    export VERSION_SERVER_DATA_DIR VERSION_SERVER_PORT VERSION_SERVER_UPLOAD_MAX_BYTES
    export VERSION_SERVER_JWT_SECRET JAVA_TOOL_OPTIONS
    export VERSION_SERVER_TLS_CERT VERSION_SERVER_TLS_KEY VERSION_SERVER_TLS_KEY_PASSWORD
    export VERSION_SERVER_TLS_KEYSTORE VERSION_SERVER_TLS_KEYSTORE_PASSWORD VERSION_SERVER_TLS_KEY_ALIAS
    export VERSION_SERVER_TLS_DIR VERSION_SERVER_HTTPS_PORT

    if [[ -n "$VERSION_SERVER_DATA_DIR" ]]; then
        mkdir -p "${VERSION_SERVER_DATA_DIR}/files" "${VERSION_SERVER_DATA_DIR}/logs"
    fi
    if [[ -n "$VERSION_SERVER_TLS_DIR" ]]; then
        containerize_tls_paths
    fi
}

tls_configured() {
    [[ -n "${VERSION_SERVER_TLS_CERT:-}" || -n "${VERSION_SERVER_TLS_KEYSTORE:-}" ]]
}

containerize_tls_paths() {
    VERSION_SERVER_TLS_CERT="$(container_tls_path "$VERSION_SERVER_TLS_CERT")"
    VERSION_SERVER_TLS_KEY="$(container_tls_path "$VERSION_SERVER_TLS_KEY")"
    VERSION_SERVER_TLS_KEYSTORE="$(container_tls_path "$VERSION_SERVER_TLS_KEYSTORE")"
    export VERSION_SERVER_TLS_CERT VERSION_SERVER_TLS_KEY VERSION_SERVER_TLS_KEYSTORE
}

container_tls_path() {
    local value="${1:-}"
    if [[ -z "$value" || "$value" == *"-----BEGIN"* ]]; then
        printf '%s\n' "$value"
        return
    fi
    case "$value" in
        "$VERSION_SERVER_TLS_DIR"/*)
            printf '/certs/%s\n' "${value#"$VERSION_SERVER_TLS_DIR"/}"
            ;;
        *)
            printf '%s\n' "$value"
            ;;
    esac
}

warn_if_tls_missing() {
    if tls_configured; then
        return
    fi
    cat <<'EOF' >&2
WARNING: TLS certificate is not configured. Version Server will serve HTTP only.
Create the certificate yourself. Do not commit it, and do not copy it into the Docker image.

  openssl req -x509 -newkey rsa:2048 -sha256 -days 825 -nodes \
    -keyout server.key -out server.crt \
    -subj "/CN=localhost" \
    -addext "subjectAltName=DNS:localhost,IP:127.0.0.1"

Point the server at those files, or paste PEM text (a value containing -----BEGIN is the certificate itself):

  VERSION_SERVER_TLS_CERT=/absolute/path/server.crt
  VERSION_SERVER_TLS_KEY=/absolute/path/server.key

A PKCS#12 or JKS file can be a single path:

  VERSION_SERVER_TLS_CERT=/absolute/path/server.p12
  VERSION_SERVER_TLS_KEYSTORE_PASSWORD=your-password

In Docker, keep the files on the host and mount that directory:

  VERSION_SERVER_TLS_DIR=/absolute/host/path/to/certs
  VERSION_SERVER_TLS_CERT=/certs/server.crt
  VERSION_SERVER_TLS_KEY=/certs/server.key

HTTPS listens on port 8443 (VERSION_SERVER_HTTPS_PORT) after a certificate is configured.
EOF
}

compose() {
    if [[ -n "${VERSION_SERVER_TLS_DIR:-}" && ! -d "$VERSION_SERVER_TLS_DIR" ]]; then
        echo "VERSION_SERVER_TLS_DIR does not exist: ${VERSION_SERVER_TLS_DIR}" >&2
        echo "Create that directory, put the certificate there, and do not commit those files." >&2
        exit 1
    fi
    local -a files=(-f "$root/docker-compose.yml")
    if tls_configured; then
        files+=(-f "$root/docker-compose.https.yml")
    fi
    if [[ -n "${VERSION_SERVER_TLS_DIR:-}" ]]; then
        files+=(-f "$root/docker-compose.tls-dir.yml")
    fi
    docker compose "${files[@]}" "$@"
}

container_publishes_port() {
    local published
    published="$(docker inspect -f '{{if .State.Running}}{{(index (index .NetworkSettings.Ports "8080/tcp") 0).HostPort}}{{end}}' version-server 2>/dev/null || true)"
    [[ "$published" == "$VERSION_SERVER_PORT" ]]
}

ensure_port_available() {
    if ! command -v lsof >/dev/null 2>&1; then
        return
    fi
    if lsof -nP -iTCP:"${VERSION_SERVER_PORT}" -sTCP:LISTEN >/dev/null 2>&1; then
        echo "Port ${VERSION_SERVER_PORT} is already in use. Stop that process, or set VERSION_SERVER_PORT in .env." >&2
        exit 1
    fi
    if tls_configured && lsof -nP -iTCP:"${VERSION_SERVER_HTTPS_PORT}" -sTCP:LISTEN >/dev/null 2>&1; then
        echo "Port ${VERSION_SERVER_HTTPS_PORT} is already in use. Stop that process, or set VERSION_SERVER_HTTPS_PORT in .env." >&2
        exit 1
    fi
}

data_mount() {
    if [[ -n "${VERSION_SERVER_DATA_DIR:-}" ]]; then
        printf '%s\n' "$VERSION_SERVER_DATA_DIR"
    else
        printf '%s\n' "version-server-data"
    fi
}

data_is_fresh() {
    if ! docker image inspect version-server:local >/dev/null 2>&1; then
        return 0
    fi
    if docker run --rm --entrypoint bash -v "$(data_mount):/data:ro" version-server:local -c 'test -f /data/store.json'; then
        return 1
    fi
    return 0
}

server_url() {
    printf 'http://localhost:%s/\n' "$VERSION_SERVER_PORT"
}

print_status() {
    local files store logs
    if [[ -n "${VERSION_SERVER_DATA_DIR:-}" ]]; then
        files="${VERSION_SERVER_DATA_DIR}/files"
        store="${VERSION_SERVER_DATA_DIR}/store.json"
        logs="${VERSION_SERVER_DATA_DIR}/logs"
    else
        files="Docker volume version-server-data, at /var/lib/version-server/files"
        store="Docker volume version-server-data, at /var/lib/version-server/store.json"
        logs="Docker volume version-server-data, at /var/lib/version-server/logs"
    fi
    cat <<EOF
Version Server is running.
  UI:    $(server_url)
  Files: ${files}
  Store: ${store}
  Logs:  ${logs}
EOF
    if tls_configured; then
        echo "  HTTPS: https://localhost:${VERSION_SERVER_HTTPS_PORT}/"
    fi
}

open_browser() {
    local url
    url="$(server_url)"
    case "$(uname -s)" in
        Darwin)
            open "$url"
            ;;
        Linux)
            if [[ -n "${DISPLAY:-}${WAYLAND_DISPLAY:-}" ]] && command -v xdg-open >/dev/null 2>&1; then
                xdg-open "$url" >/dev/null 2>&1 || true
            else
                echo "Open ${url} in a browser."
            fi
            ;;
        *)
            echo "Open ${url} in a browser."
            ;;
    esac
}
