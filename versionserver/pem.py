import base64
import re
from collections.abc import Callable
from pathlib import Path

from cryptography import x509
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.rsa import RSAPrivateKey, RSAPublicKey
from cryptography.hazmat.primitives.asymmetric.types import PrivateKeyTypes

_PEM_BLOCK = re.compile(
    r"-----BEGIN ([A-Z0-9 ]+)-----[\t ]*\r?\n(.*?)-----END \1-----",
    re.DOTALL,
)


def env_value(env: Callable[[str], str | None], name: str) -> str | None:
    raw = env(name)
    if raw is None:
        return None
    trimmed = raw.strip()
    if trimmed == "":
        return None
    return trimmed


def read_material(value: str) -> str | None:
    if "-----BEGIN" in value:
        return value
    path = Path(value)
    if not path.is_file():
        return None
    try:
        return path.read_text(encoding="utf-8")
    except OSError:
        return None


def load_certificates(text: str) -> list[x509.Certificate] | None:
    certificates: list[x509.Certificate] = []
    for kind, body in _pem_blocks(text):
        if kind != "CERTIFICATE":
            continue
        try:
            certificates.append(x509.load_der_x509_certificate(body))
        except ValueError:
            return None
    if not certificates:
        return None
    return certificates


def load_private_key(text: str, password: str) -> PrivateKeyTypes | None:
    data = text.encode("utf-8")
    candidates: list[bytes | None] = [password.encode("utf-8")] if password else []
    candidates.append(None)
    for candidate in candidates:
        try:
            return serialization.load_pem_private_key(data, password=candidate)
        except (TypeError, ValueError):
            continue
    return None


def private_key_matches(certificate: x509.Certificate, private_key: object) -> bool:
    public_from_key = getattr(private_key, "public_key", None)
    if not callable(public_from_key):
        return False
    try:
        derived = public_from_key()
        published = certificate.public_key()
        encoding = serialization.Encoding.DER
        fmt = serialization.PublicFormat.SubjectPublicKeyInfo
        return published.public_bytes(encoding, fmt) == derived.public_bytes(encoding, fmt)
    except (ValueError, TypeError):
        return False


def is_rsa_pair(certificate: x509.Certificate, private_key: object) -> bool:
    return isinstance(private_key, RSAPrivateKey) and isinstance(certificate.public_key(), RSAPublicKey)


def _pem_blocks(text: str) -> list[tuple[str, bytes]]:
    normalized = text.replace("\r\n", "\n")
    blocks: list[tuple[str, bytes]] = []
    for match in _PEM_BLOCK.finditer(normalized):
        body = "".join(match.group(2).split())
        try:
            decoded = base64.b64decode(body, validate=False)
        except Exception:
            continue
        blocks.append((match.group(1), decoded))
    return blocks
