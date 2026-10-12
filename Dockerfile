# Version Server image.
# Host state is mounted on /var/lib/version-server (see docker-compose.yml).
# TLS certificates are not copied into this image. Mount them at run time.

FROM python:3.12-slim-bookworm AS build

RUN apt-get update \
    && apt-get install -y --no-install-recommends gcc libc6-dev \
    && rm -rf /var/lib/apt/lists/*
COPY --from=ghcr.io/astral-sh/uv:0.13.0 /uv /uvx /bin/
WORKDIR /src
ENV UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    UV_PYTHON_DOWNLOADS=never

COPY pyproject.toml uv.lock ./
COPY docs docs
COPY versionserver versionserver
RUN uv sync --frozen --no-dev --no-editable

FROM python:3.12-slim-bookworm

RUN useradd --uid 10001 --home-dir /var/lib/version-server --shell /usr/sbin/nologin --no-create-home app

WORKDIR /var/lib/version-server

COPY --from=build /src/.venv /opt/version-server/.venv
COPY docs /opt/version-server/docs
COPY docker/entrypoint.sh /opt/version-server/entrypoint.sh
RUN chmod 755 /opt/version-server/entrypoint.sh

ENV PATH="/opt/version-server/.venv/bin:$PATH" \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    VERSION_SERVER_DOCS=/opt/version-server/docs \
    VERSION_SERVER_STORAGE=/var/lib/version-server/files \
    VERSION_SERVER_JSON=/var/lib/version-server/store.json \
    VERSION_SERVER_LOG_DIR=/var/lib/version-server/logs \
    VERSION_SERVER_UPLOAD_MAX_BYTES=1073741824

EXPOSE 8080 8443

HEALTHCHECK --interval=5s --timeout=3s --start-period=15s --retries=20 \
    CMD python3 -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8080/ui/', timeout=2)"

ENTRYPOINT ["/opt/version-server/entrypoint.sh"]
