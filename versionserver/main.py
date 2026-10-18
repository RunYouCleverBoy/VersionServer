import asyncio
import logging
import os
import sys
from pathlib import Path

import uvicorn

from versionserver.app import create_app
from versionserver.config import AppConfig
from versionserver.logging_config import configure_logging, use_process_logging
from versionserver.signing import CertificateSigning, HOW_TO_CREATE, InvalidSigning, load_jwt_signing
from versionserver.tls import (
    MISSING,
    InvalidTls,
    MissingTls,
    ReadyTls,
    invalid_guidance,
    load_server_tls,
    materialize_pem,
)

logger = logging.getLogger("versionserver")
HTTP_PORT = 8080


def build_app():
    """ASGI app for hosts that import the process instead of calling main()."""
    use_process_logging()
    config, _signing_description = config_from_env()
    return create_app(config)


def config_from_env() -> tuple[AppConfig, str]:
    signing = load_jwt_signing(os.environ.get)
    if isinstance(signing, InvalidSigning):
        raise RuntimeError(signing.detail)
    config = _config_from_env(signing)
    if config.signing_key() is None:
        raise RuntimeError(HOW_TO_CREATE)
    description = signing.material.description if isinstance(signing, CertificateSigning) else "hmac"
    return config, description


def path_from_env(name: str, default: str) -> Path:
    raw = os.environ.get(name)
    if raw is not None and raw.strip() != "":
        return Path(raw)
    if os.environ.get("VERCEL") == "1":
        return Path("/tmp/version-server") / default
    return Path(default)


def main() -> None:
    log_dir = path_from_env("VERSION_SERVER_LOG_DIR", "logs")
    configure_logging(log_dir)
    try:
        config, signing_description = config_from_env()
    except RuntimeError as error:
        logger.error("%s", error)
        sys.exit(1)
    tls_load = load_server_tls(os.environ.get)
    tls = None
    if isinstance(tls_load, MissingTls):
        logger.warning("%s", MISSING)
    elif isinstance(tls_load, InvalidTls):
        logger.error("%s", invalid_guidance(tls_load.detail))
        sys.exit(1)
    elif isinstance(tls_load, ReadyTls):
        tls = tls_load.tls
    logger.info(
        "starting VersionServer storage=%s catalog=%s logs=%s uploadMaxBytes=%s jwt=%s tls=%s",
        config.storage_root.resolve(),
        "mongo" if config.mongo_uri else config.json_store_path.resolve(),
        log_dir.resolve(),
        config.multipart_form_field_limit_bytes,
        signing_description,
        tls.description if tls is not None else "off",
    )
    app = create_app(config)
    asyncio.run(_serve(app, tls))


def _config_from_env(signing: object) -> AppConfig:
    limit = _positive_int(os.environ.get("VERSION_SERVER_UPLOAD_MAX_BYTES"), 1024 * 1024 * 1024)
    certificate = None
    private_key = None
    if isinstance(signing, CertificateSigning):
        certificate = signing.material.certificate
        private_key = signing.material.private_key
    return AppConfig(
        storage_root=path_from_env("VERSION_SERVER_STORAGE", "data/files"),
        json_store_path=path_from_env("VERSION_SERVER_JSON", "data/store.json"),
        mongo_uri=_mongo_uri(),
        jwt_secret=os.environ.get("VERSION_SERVER_JWT_SECRET") or "dev-only-change-me",
        jwt_certificate=certificate,
        jwt_private_key=private_key,
        multipart_form_field_limit_bytes=limit,
    )


def _mongo_uri() -> str | None:
    raw = os.environ.get("MONGODB_URI")
    if raw is None or raw.strip() == "":
        return None
    return raw.strip()


def _positive_int(raw: str | None, default: int) -> int:
    if raw is None or raw.strip() == "":
        return default
    try:
        value = int(raw)
    except ValueError:
        return default
    if value <= 0:
        return default
    return value


async def _serve(app, tls) -> None:
    http_config = uvicorn.Config(
        app,
        host="0.0.0.0",
        port=HTTP_PORT,
        access_log=False,
        proxy_headers=False,
        server_header=False,
    )
    http_server = uvicorn.Server(http_config)
    servers = [http_server]
    https_server = None
    if tls is not None:
        certificate_path, key_path = materialize_pem(tls)
        https_config = uvicorn.Config(
            app,
            host="0.0.0.0",
            port=tls.https_port,
            ssl_certfile=str(certificate_path),
            ssl_keyfile=str(key_path),
            access_log=False,
            proxy_headers=False,
            server_header=False,
        )
        https_server = uvicorn.Server(https_config)
        https_server.install_signal_handlers = lambda: None
        servers.append(https_server)

    async def stop_https_when_http_stops() -> None:
        while not http_server.should_exit:
            await asyncio.sleep(0.2)
        if https_server is not None:
            https_server.should_exit = True

    await asyncio.gather(stop_https_when_http_stops(), *(server.serve() for server in servers))
