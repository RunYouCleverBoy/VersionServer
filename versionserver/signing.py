from collections.abc import Callable
from dataclasses import dataclass

from cryptography import x509
from cryptography.hazmat.primitives.asymmetric.rsa import RSAPrivateKey

from versionserver.pem import (
    env_value,
    is_rsa_pair,
    load_certificates,
    load_private_key,
    private_key_matches,
    read_material,
)


@dataclass(frozen=True)
class JwtCertificate:
    certificate: x509.Certificate
    private_key: RSAPrivateKey
    description: str


class JwtSigningLoad:
    pass


class HmacSigning(JwtSigningLoad):
    pass


class CertificateSigning(JwtSigningLoad):
    def __init__(self, material: JwtCertificate) -> None:
        self.material = material


class InvalidSigning(JwtSigningLoad):
    def __init__(self, detail: str) -> None:
        self.detail = detail


HOW_TO_CREATE = """
Create an RSA certificate and private key to sign login tokens. Do not commit them, and do not copy them into the Docker image.

  openssl req -x509 -newkey rsa:2048 -sha256 -days 825 -nodes \\
    -keyout jwt.key -out jwt.crt \\
    -subj "/CN=version-server-jwt"

Point the server at those files, or paste PEM text (a value containing -----BEGIN is the material itself):

  VERSION_SERVER_JWT_CERT=/absolute/path/jwt.crt
  VERSION_SERVER_JWT_KEY=/absolute/path/jwt.key

In Docker the files live in the data volume, which the container sees as:

  VERSION_SERVER_JWT_CERT=/var/lib/version-server/jwt/jwt.crt
  VERSION_SERVER_JWT_KEY=/var/lib/version-server/jwt/jwt.key

When both are unset, login tokens are signed with the HMAC secret in VERSION_SERVER_JWT_SECRET.
Keep the private key readable only by the account that runs the server.
""".strip()


def load_jwt_signing(env: Callable[[str], str | None]) -> JwtSigningLoad:
    cert_value = env_value(env, "VERSION_SERVER_JWT_CERT")
    key_value = env_value(env, "VERSION_SERVER_JWT_KEY")
    if cert_value is None and key_value is None:
        return HmacSigning()
    if cert_value is None or key_value is None:
        return InvalidSigning(
            "Set both VERSION_SERVER_JWT_CERT and VERSION_SERVER_JWT_KEY.\n\n" + HOW_TO_CREATE
        )
    certificate_text = read_material(cert_value)
    if certificate_text is None:
        return InvalidSigning(
            f"VERSION_SERVER_JWT_CERT was not found at {cert_value}.\n\n{HOW_TO_CREATE}"
        )
    key_text = read_material(key_value)
    if key_text is None:
        return InvalidSigning(f"VERSION_SERVER_JWT_KEY was not found at {key_value}.\n\n{HOW_TO_CREATE}")
    password = env_value(env, "VERSION_SERVER_JWT_KEY_PASSWORD") or ""
    certificates = load_certificates(certificate_text)
    certificate = certificates[0] if certificates else None
    if certificate is None:
        return InvalidSigning(f"VERSION_SERVER_JWT_CERT is not a PEM certificate.\n\n{HOW_TO_CREATE}")
    private_key = load_private_key(key_text, password)
    if not isinstance(private_key, RSAPrivateKey):
        return InvalidSigning(f"VERSION_SERVER_JWT_KEY is not an RSA private key.\n\n{HOW_TO_CREATE}")
    if not private_key_matches(certificate, private_key):
        return InvalidSigning(
            f"The JWT private key does not match VERSION_SERVER_JWT_CERT.\n\n{HOW_TO_CREATE}"
        )
    if not is_rsa_pair(certificate, private_key):
        return InvalidSigning(
            f"VERSION_SERVER_JWT_CERT must contain an RSA public key.\n\n{HOW_TO_CREATE}"
        )
    description = "inlined PEM certificate" if "-----BEGIN" in cert_value else f"certificate {cert_value}"
    return CertificateSigning(JwtCertificate(certificate, private_key, description))
