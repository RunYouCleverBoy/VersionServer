from dataclasses import dataclass
from pathlib import Path

from cryptography import x509
from cryptography.hazmat.primitives.asymmetric.rsa import RSAPrivateKey

from versionserver.pem import is_rsa_pair, private_key_matches


@dataclass
class AppConfig:
    storage_root: Path
    json_store_path: Path
    mongo_uri: str | None = None
    jwt_secret: str = "dev-only-change-me"
    jwt_certificate: x509.Certificate | None = None
    jwt_private_key: RSAPrivateKey | None = None
    jwt_issuer: str = "version-server"
    jwt_audience: str = "version-server"
    jwt_cookie_name: str = "VS_TOKEN"
    jwt_validity_seconds: int = 24 * 60 * 60
    multipart_form_field_limit_bytes: int = 1024 * 1024 * 1024

    def signing_key(self) -> tuple[str, object] | None:
        if self.jwt_certificate is None and self.jwt_private_key is None:
            return ("HS256", self.jwt_secret)
        if self.jwt_certificate is None or self.jwt_private_key is None:
            return None
        if not is_rsa_pair(self.jwt_certificate, self.jwt_private_key):
            return None
        if not private_key_matches(self.jwt_certificate, self.jwt_private_key):
            return None
        return ("RS256", self.jwt_private_key)

    def verify_key(self) -> tuple[str, object] | None:
        signing = self.signing_key()
        if signing is None:
            return None
        algorithm, key = signing
        if algorithm == "HS256":
            return algorithm, key
        if self.jwt_certificate is None:
            return None
        return algorithm, self.jwt_certificate.public_key()
