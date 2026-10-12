import os
import tempfile
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path

from cryptography import x509
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.types import PrivateKeyTypes
from cryptography.hazmat.primitives.serialization import pkcs12
from cryptography.x509.oid import NameOID

from versionserver.pem import env_value, load_certificates, load_private_key, private_key_matches, read_material

HTTP_PORT = 8080
DEFAULT_HTTPS_PORT = 8443
KEY_ALIAS_DEFAULT = "version-server"

HOW_TO_CREATE = """
Create the certificate yourself. Do not commit it, and do not copy it into the Docker image.

Create a key and certificate:

  openssl req -x509 -newkey rsa:2048 -sha256 -days 825 -nodes \\
    -keyout server.key -out server.crt \\
    -subj "/CN=localhost" \\
    -addext "subjectAltName=DNS:localhost,IP:127.0.0.1"

Point the server at those files, or paste the PEM text. A value containing -----BEGIN is the certificate itself:

  VERSION_SERVER_TLS_CERT=/absolute/path/server.crt
  VERSION_SERVER_TLS_KEY=/absolute/path/server.key

A PKCS#12 or JKS file can be given as a single path:

  VERSION_SERVER_TLS_CERT=/absolute/path/server.p12
  VERSION_SERVER_TLS_KEYSTORE_PASSWORD=your-password

In Docker, keep the files on the host and mount that directory. Paths under VERSION_SERVER_TLS_DIR are exposed in the container as /certs/...:

  VERSION_SERVER_TLS_DIR=/absolute/host/path/to/certs
  VERSION_SERVER_TLS_CERT=/certs/server.crt
  VERSION_SERVER_TLS_KEY=/certs/server.key

HTTPS listens on port 8443 (VERSION_SERVER_HTTPS_PORT) after a certificate is configured.
Keep the private key readable only by the account that runs the server.
""".strip()

MISSING = (
    f"TLS certificate is not configured. Version Server is serving HTTP only, on port {HTTP_PORT}.\n\n"
    f"{HOW_TO_CREATE}"
)


def invalid_guidance(detail: str) -> str:
    return f"{detail}\n\n{HOW_TO_CREATE}"


@dataclass
class ServerTls:
    private_key: PrivateKeyTypes
    certificates: list[x509.Certificate]
    key_alias: str
    password: str
    https_port: int
    description: str
    aliases: dict[str, x509.Certificate]

    def certificate_for_alias(self, alias: str) -> x509.Certificate | None:
        return self.aliases.get(alias)


class ServerTlsLoad:
    pass


class MissingTls(ServerTlsLoad):
    pass


class InvalidTls(ServerTlsLoad):
    def __init__(self, detail: str) -> None:
        self.detail = detail


class ReadyTls(ServerTlsLoad):
    def __init__(self, tls: ServerTls) -> None:
        self.tls = tls


def subject_cn(tls: ServerTls) -> str | None:
    certificate = tls.certificate_for_alias(tls.key_alias)
    if certificate is None:
        return None
    attributes = certificate.subject.get_attributes_for_oid(NameOID.COMMON_NAME)
    if not attributes:
        return None
    value = attributes[0].value
    if isinstance(value, str):
        return value
    return str(value)


def materialize_pem(tls: ServerTls) -> tuple[Path, Path]:
    directory = Path(tempfile.mkdtemp(prefix="version-server-tls-"))
    certificate_path = directory / "server.crt"
    key_path = directory / "server.key"
    certificate_path.write_bytes(b"".join(certificate.public_bytes(serialization.Encoding.PEM) for certificate in tls.certificates))
    key_path.write_bytes(
        tls.private_key.private_bytes(
            encoding=serialization.Encoding.PEM,
            format=serialization.PrivateFormat.PKCS8,
            encryption_algorithm=serialization.NoEncryption(),
        )
    )
    os.chmod(key_path, 0o600)
    return certificate_path, key_path


def load_server_tls(env: Callable[[str], str | None]) -> ServerTlsLoad:
    cert_value = env_value(env, "VERSION_SERVER_TLS_CERT")
    key_value = env_value(env, "VERSION_SERVER_TLS_KEY")
    keystore_value = env_value(env, "VERSION_SERVER_TLS_KEYSTORE")
    if cert_value is None and key_value is None and keystore_value is None:
        return MissingTls()
    https_port = _https_port(env)
    if https_port is None:
        return InvalidTls(f"VERSION_SERVER_HTTPS_PORT must be a TCP port other than {HTTP_PORT}.")
    if keystore_value is not None and (cert_value is not None or key_value is not None):
        return InvalidTls("Set VERSION_SERVER_TLS_KEYSTORE or VERSION_SERVER_TLS_CERT, not both.")

    keystore_path = keystore_value or (cert_value if cert_value is not None and _is_keystore_path(cert_value) else None)
    if keystore_path is not None:
        if key_value is not None:
            return InvalidTls(
                "A PKCS#12 or JKS file does not use VERSION_SERVER_TLS_KEY. "
                "Set VERSION_SERVER_TLS_KEYSTORE_PASSWORD instead."
            )
        return _load_keystore_tls(keystore_path, env, https_port)

    if cert_value is None or key_value is None:
        return InvalidTls(
            "Set both VERSION_SERVER_TLS_CERT and VERSION_SERVER_TLS_KEY, or set VERSION_SERVER_TLS_KEYSTORE."
        )
    certificate_text = read_material(cert_value)
    if certificate_text is None:
        return InvalidTls(f"VERSION_SERVER_TLS_CERT was not found at {cert_value}.")
    key_text = read_material(key_value)
    if key_text is None:
        return InvalidTls(f"VERSION_SERVER_TLS_KEY was not found at {key_value}.")
    password = env_value(env, "VERSION_SERVER_TLS_KEY_PASSWORD") or ""
    certificates = load_certificates(certificate_text)
    private_key = load_private_key(key_text, password)
    leaf = certificates[0] if certificates else None
    if certificates is None or private_key is None or leaf is None or not private_key_matches(leaf, private_key):
        return InvalidTls(_pem_failure_detail())
    return ReadyTls(
        ServerTls(
            private_key=private_key,
            certificates=certificates,
            key_alias=KEY_ALIAS_DEFAULT,
            password="",
            https_port=https_port,
            description=_material_description(cert_value, "certificate"),
            aliases={KEY_ALIAS_DEFAULT: leaf},
        )
    )


def _pem_failure_detail() -> str:
    return (
        "Could not read the PEM certificate and private key. "
        "Use an unencrypted PKCS#8 key (BEGIN PRIVATE KEY) or an RSA key (BEGIN RSA PRIVATE KEY). "
        "The private key must match the first certificate in the file. "
        "Encrypted keys need VERSION_SERVER_TLS_KEY_PASSWORD. "
        "Convert a key with: openssl pkcs8 -topk8 -nocrypt -in server.key -out server.pkcs8.key"
    )


def _load_keystore_tls(path_value: str, env: Callable[[str], str | None], https_port: int) -> ServerTlsLoad:
    if "-----BEGIN" in path_value:
        return InvalidTls("VERSION_SERVER_TLS_KEYSTORE must be a file path, not PEM text.")
    path = Path(path_value)
    if not path.is_file():
        return InvalidTls(f"Keystore was not found at {path_value}.")
    password = (
        env_value(env, "VERSION_SERVER_TLS_KEYSTORE_PASSWORD")
        or env_value(env, "VERSION_SERVER_TLS_KEY_PASSWORD")
        or ""
    )
    loaded = _open_keystore(path, password)
    if isinstance(loaded, str):
        return InvalidTls(f"Could not open keystore {path_value}: {loaded}")
    private_key, certificates, aliases = loaded
    requested = env_value(env, "VERSION_SERVER_TLS_KEY_ALIAS")
    alias = requested or next(iter(aliases), None)
    if alias is None:
        return InvalidTls(f"Keystore {path_value} has no private key entry.")
    if alias not in aliases:
        return InvalidTls(f"Keystore {path_value} has no private key named {alias}.")
    return ReadyTls(
        ServerTls(
            private_key=private_key,
            certificates=certificates,
            key_alias=alias,
            password=password,
            https_port=https_port,
            description=f"keystore {path_value}",
            aliases=aliases,
        )
    )


def _open_keystore(
    path: Path,
    password: str,
) -> tuple[PrivateKeyTypes, list[x509.Certificate], dict[str, x509.Certificate]] | str:
    suffix = path.suffix.lower()
    try:
        if suffix == ".jks":
            return _open_jks(path, password)
        return _open_pkcs12(path, password)
    except Exception as error:
        message = str(error).strip() or error.__class__.__name__
        return message


def _open_pkcs12(
    path: Path,
    password: str,
) -> tuple[PrivateKeyTypes, list[x509.Certificate], dict[str, x509.Certificate]]:
    data = path.read_bytes()
    password_bytes = password.encode("utf-8")
    try:
        loaded = pkcs12.load_pkcs12(data, password_bytes)
    except ValueError:
        loaded = pkcs12.load_pkcs12(data, password_bytes or None)
    if loaded.key is None or loaded.cert is None:
        raise ValueError("no private key entry")
    certificates = [loaded.cert.certificate]
    aliases: dict[str, x509.Certificate] = {}
    friendly = _friendly_name(loaded.cert.friendly_name) or KEY_ALIAS_DEFAULT
    aliases[friendly] = loaded.cert.certificate
    for extra in loaded.additional_certs:
        certificates.append(extra.certificate)
        extra_name = _friendly_name(extra.friendly_name)
        if extra_name and extra_name not in aliases:
            aliases[extra_name] = extra.certificate
    return loaded.key, certificates, aliases


def _open_jks(
    path: Path,
    password: str,
) -> tuple[PrivateKeyTypes, list[x509.Certificate], dict[str, x509.Certificate]]:
    try:
        import jks
    except ImportError as error:
        raise RuntimeError("reading a JKS file requires the jks package; use a PKCS#12 keystore instead") from error

    store = jks.KeyStore.load(str(path), password)
    if not store.private_keys:
        raise ValueError("no private key entry")
    alias, entry = next(iter(store.private_keys.items()))
    if not entry.is_decrypted():
        entry.decrypt(password)
    private_key = serialization.load_der_private_key(entry.pkey_pkcs8, password=None)
    certificates: list[x509.Certificate] = []
    for _kind, encoded in entry.cert_chain:
        certificates.append(x509.load_der_x509_certificate(encoded))
    if not certificates:
        raise ValueError("no certificate")
    return private_key, certificates, {alias: certificates[0]}


def _friendly_name(value: bytes | str | None) -> str | None:
    if value is None:
        return None
    if isinstance(value, bytes):
        text = value.decode("utf-8", errors="replace")
    else:
        text = value
    if text == "":
        return None
    return text


def _https_port(env: Callable[[str], str | None]) -> int | None:
    raw = env_value(env, "VERSION_SERVER_HTTPS_PORT")
    if raw is None:
        return DEFAULT_HTTPS_PORT
    try:
        port = int(raw)
    except ValueError:
        return None
    if port < 1 or port > 65535 or port == HTTP_PORT:
        return None
    return port


def _is_keystore_path(value: str) -> bool:
    if "-----BEGIN" in value:
        return False
    name = Path(value).name.lower()
    return name.endswith(".p12") or name.endswith(".pfx") or name.endswith(".jks")


def _material_description(value: str, kind: str) -> str:
    if "-----BEGIN" in value:
        return f"inlined PEM {kind}"
    return f"{kind} path {value}"
