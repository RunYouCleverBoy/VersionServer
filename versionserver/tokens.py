from datetime import datetime, timedelta, timezone

import jwt

from versionserver.config import AppConfig
from versionserver.signing import HOW_TO_CREATE


class JwtTokenService:
    def __init__(self, config: AppConfig) -> None:
        signing = config.signing_key()
        verify = config.verify_key()
        if signing is None or verify is None:
            raise ValueError(HOW_TO_CREATE)
        self._algorithm, self._signing_key = signing
        self._verify_algorithm, self._verify_key = verify
        self._issuer = config.jwt_issuer
        self._audience = config.jwt_audience
        self._validity_seconds = config.jwt_validity_seconds

    def issue(self, user_id: str, role: str) -> str:
        now = datetime.now(timezone.utc)
        token = jwt.encode(
            {
                "iss": self._issuer,
                "aud": self._audience,
                "sub": user_id,
                "role": role,
                "iat": now,
                "exp": now + timedelta(seconds=self._validity_seconds),
            },
            self._signing_key,
            algorithm=self._algorithm,
        )
        if isinstance(token, bytes):
            return token.decode("ascii")
        return token

    def user_id_for(self, token: str) -> str | None:
        try:
            payload = jwt.decode(
                token,
                self._verify_key,
                algorithms=[self._verify_algorithm],
                audience=self._audience,
                issuer=self._issuer,
            )
        except jwt.PyJWTError:
            return None
        subject = payload.get("sub")
        if not isinstance(subject, str) or subject.strip() == "":
            return None
        return subject
