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

# Users, grants, and the artifact index. Leave empty to keep them in store.json.
# File bytes stay on disk either way. Do not commit a real URI.
MONGODB_URI=

# HMAC secret for login cookies. Changing it signs everyone out.
# Ignored for signing when VERSION_SERVER_JWT_CERT and VERSION_SERVER_JWT_KEY are set.
VERSION_SERVER_JWT_SECRET="${secret}"

# RSA certificate that signs login JWTs. Leave empty to create jwt.crt and jwt.key
# in the data volume on the next start. Do not commit those files.
VERSION_SERVER_JWT_CERT=
VERSION_SERVER_JWT_KEY=
VERSION_SERVER_JWT_KEY_PASSWORD=

# Host port published to container port 8080.
VERSION_SERVER_PORT=8080

# Max size of one multipart upload part, in bytes (1 GiB).
# The server streams each part to disk.
VERSION_SERVER_UPLOAD_MAX_BYTES=1073741824

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
    : "${VERSION_SERVER_DATA_DIR:=}"
    : "${MONGODB_URI:=}"
    : "${VERSION_SERVER_JWT_CERT:=}"
    : "${VERSION_SERVER_JWT_KEY:=}"
    : "${VERSION_SERVER_JWT_KEY_PASSWORD:=}"
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
    export MONGODB_URI
    export VERSION_SERVER_JWT_SECRET VERSION_SERVER_JWT_CERT VERSION_SERVER_JWT_KEY
    export VERSION_SERVER_JWT_KEY_PASSWORD
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

ensure_jwt_certificate() {
    if [[ -n "${VERSION_SERVER_JWT_CERT:-}" || -n "${VERSION_SERVER_JWT_KEY:-}" ]]; then
        if [[ -z "${VERSION_SERVER_JWT_CERT:-}" || -z "${VERSION_SERVER_JWT_KEY:-}" ]]; then
            echo "Set both VERSION_SERVER_JWT_CERT and VERSION_SERVER_JWT_KEY." >&2
            exit 1
        fi
        return
    fi

    local cert_path="/var/lib/version-server/jwt/jwt.crt"
    local key_path="/var/lib/version-server/jwt/jwt.key"
    if jwt_material_present; then
        echo "Using the JWT certificate already stored with the server data."
    else
        echo "Creating a new JWT signing certificate in the server data volume."
        local dir="/tmp/version-server-jwt-$$"
        mkdir -p "$dir"
        openssl req -x509 -newkey rsa:2048 -sha256 -days 825 -nodes \
            -keyout "$dir/jwt.key" -out "$dir/jwt.crt" \
            -subj "/CN=version-server-jwt" >/dev/null 2>&1
        chmod 600 "$dir/jwt.key"
        copy_jwt_material "$dir"
        rm -rf "$dir"
    fi
    set_dotenv VERSION_SERVER_JWT_CERT "$cert_path"
    set_dotenv VERSION_SERVER_JWT_KEY "$key_path"
    export VERSION_SERVER_JWT_CERT="$cert_path"
    export VERSION_SERVER_JWT_KEY="$key_path"
}

jwt_material_present() {
    if [[ -n "${VERSION_SERVER_DATA_DIR:-}" ]]; then
        [[ -f "${VERSION_SERVER_DATA_DIR}/jwt/jwt.crt" && -f "${VERSION_SERVER_DATA_DIR}/jwt/jwt.key" ]]
        return
    fi
    docker volume inspect version-server-data >/dev/null 2>&1 || return 1
    local image="version-server:local"
    if ! docker image inspect "$image" >/dev/null 2>&1; then
        return 1
    fi
    docker run --rm --entrypoint bash -v version-server-data:/data:ro "$image" \
        -c 'test -f /data/jwt/jwt.crt && test -f /data/jwt/jwt.key'
}

copy_jwt_material() {
    local dir="$1"
    if [[ -n "${VERSION_SERVER_DATA_DIR:-}" ]]; then
        mkdir -p "${VERSION_SERVER_DATA_DIR}/jwt"
        cp "$dir/jwt.crt" "$dir/jwt.key" "${VERSION_SERVER_DATA_DIR}/jwt/"
        chmod 600 "${VERSION_SERVER_DATA_DIR}/jwt/jwt.key"
        return
    fi
    docker volume create version-server-data >/dev/null
    local image="version-server:local"
    if ! docker image inspect "$image" >/dev/null 2>&1; then
        image="alpine:3.20"
    fi
    if [[ "$image" == alpine:* ]]; then
        docker run --rm -v version-server-data:/data -v "$dir:/src:ro" "$image" \
            sh -c 'mkdir -p /data/jwt && cp /src/jwt.crt /src/jwt.key /data/jwt && chmod 600 /data/jwt/jwt.key'
    else
        docker run --rm --entrypoint bash -v version-server-data:/data -v "$dir:/src:ro" "$image" \
            -c 'mkdir -p /data/jwt && cp /src/jwt.crt /src/jwt.key /data/jwt && chmod 600 /data/jwt/jwt.key'
    fi
}

set_dotenv() {
    local key="$1"
    local value="$2"
    python3 - "$root/.env" "$key" "$value" <<'PY'
import sys
path, key, value = sys.argv[1:]
lines = open(path).read().splitlines()
prefix = key + "="
found = False
out = []
for line in lines:
    if line.startswith(prefix):
        out.append(prefix + value)
        found = True
    else:
        out.append(line)
if not found:
    out.append(prefix + value)
open(path, "w").write("\n".join(out) + "\n")
PY
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
    if [[ -n "${VERSION_SERVER_JWT_CERT:-}" ]]; then
        echo "  JWT:   ${VERSION_SERVER_JWT_CERT}"
    fi
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
