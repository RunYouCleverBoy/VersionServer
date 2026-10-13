from datetime import datetime, timedelta, timezone

import jwt
from cryptography.hazmat.primitives.asymmetric import rsa

from tests.conftest import auth, cookie_headers, is_jwt, login, login_response, make_client
from versionserver.signing import CertificateSigning, load_jwt_signing


def test_login_issues_a_jwt_and_sets_an_httponly_cookie(tmp_path):
    with make_client(tmp_path) as client:
        response = login_response(client, "admin", "admin-pass")
        assert response.status_code == 200
        token = response.json()["token"]
        assert is_jwt(token)
        header = _cookie(response, "VS_TOKEN")
        assert token in header
        assert "HttpOnly" in header
        assert "Path=/" in header


def test_cookie_jwt_alone_authenticates(tmp_path):
    with make_client(tmp_path) as client:
        login(client, "admin", "admin-pass")
        response = client.get("/projects")
        assert response.status_code == 200
        body = response.json()
        assert body["userId"] == "admin"
        assert body["role"] == "Admin"
        assert body["projects"] == []


def test_invalid_jwt_string_is_rejected(tmp_path):
    with make_client(tmp_path) as client:
        response = client.get("/projects", headers=auth("not.a.jwt"))
        assert response.status_code == 401


def test_tampered_jwt_is_rejected(tmp_path):
    with make_client(tmp_path) as client:
        token = login(client, "admin", "admin-pass")
    tampered = token[:-1] + ("b" if token[-1] == "a" else "a")
    with make_client(tmp_path) as bare:
        response = bare.get("/projects", headers=auth(tampered))
        assert response.status_code == 401


def test_jwt_remains_valid_after_restart_with_the_same_secret(tmp_path):
    storage = tmp_path / "files"
    store = tmp_path / "store.json"
    store.write_text("", encoding="utf-8")
    secret = "stable-secret"
    with make_client(
        tmp_path, storage_root=storage, json_store_path=store, jwt_secret=secret, blank_store=False
    ) as client:
        token = login(client, "admin", "admin-pass")
        assert is_jwt(token)
    with make_client(
        tmp_path, storage_root=storage, json_store_path=store, jwt_secret=secret, blank_store=False
    ) as client:
        response = client.get("/projects", headers=auth(token))
        assert response.status_code == 200


def test_no_api_accepts_a_token_not_signed_by_our_certificate(tmp_path):
    certificate = tmp_path / "jwt.crt"
    key = tmp_path / "jwt.key"
    _openssl_cert(certificate, key)
    signing = load_jwt_signing(
        lambda name: { "VERSION_SERVER_JWT_CERT": str(certificate), "VERSION_SERVER_JWT_KEY": str(key) }.get(name)
    )
    assert isinstance(signing, CertificateSigning)
    with make_client(
        tmp_path,
        jwt_certificate=signing.material.certificate,
        jwt_private_key=signing.material.private_key,
    ) as client:
        token = login(client, "admin", "admin-pass")
        assert client.get("/projects", headers=auth(token)).status_code == 200
        _assert_every_protected_api_rejects(client, auth(_token_signed_by_other_certificate()))
        _assert_every_protected_api_rejects(
            client,
            {"Cookie": f"VS_TOKEN={_token_signed_by_other_certificate()}"},
        )


def test_no_api_accepts_a_token_not_signed_by_our_signing_key(tmp_path):
    with make_client(tmp_path) as client:
        ours = login(client, "admin", "admin-pass")
        assert client.get("/projects", headers=auth(ours)).status_code == 200
        for foreign in (_token_signed_by_other_secret(), _token_signed_by_other_certificate()):
            _assert_every_protected_api_rejects(client, auth(foreign))
            _assert_every_protected_api_rejects(client, {"Cookie": f"VS_TOKEN={foreign}"})


def test_jwt_signed_with_a_different_secret_is_rejected_after_restart(tmp_path):
    storage = tmp_path / "files"
    store = tmp_path / "store.json"
    store.write_text("", encoding="utf-8")
    with make_client(
        tmp_path, storage_root=storage, json_store_path=store, jwt_secret="secret-a", blank_store=False
    ) as client:
        token = login(client, "admin", "admin-pass")
    with make_client(
        tmp_path, storage_root=storage, json_store_path=store, jwt_secret="secret-b", blank_store=False
    ) as client:
        assert client.get("/projects", headers=auth(token)).status_code == 401


def test_logout_clears_the_auth_cookie(tmp_path):
    with make_client(tmp_path) as client:
        login(client, "admin", "admin-pass")
        assert client.get("/projects").status_code == 200
        logout = client.post("/logout")
        assert logout.status_code == 204
        cleared = _cookie(logout, "VS_TOKEN")
        assert "Max-Age=0" in cleared or "VS_TOKEN=;" in cleared or 'VS_TOKEN=""' in cleared
        assert client.get("/projects").status_code == 401


def test_login_response_body_token_matches_the_cookie(tmp_path):
    with make_client(tmp_path) as client:
        response = login_response(client, "alice", "alice-pass")
        assert '"token"' in response.text
        token = response.json()["token"]
        assert token in _cookie(response, "VS_TOKEN")
        assert is_jwt(token)


def _assert_every_protected_api_rejects(client, headers: dict[str, str]) -> None:
    calls = [
        client.post("/password", headers=headers),
        client.get("/projects", headers=headers),
        client.get("/users", headers=headers),
        client.post("/users", headers=headers),
        client.post("/projects/alpha/access", headers=headers),
        client.get("/projects/alpha/versions", headers=headers),
        client.post("/projects/alpha/versions/1.0/files", headers=headers),
        client.get("/projects/alpha/versions/1.0/files", headers=headers),
        client.get("/projects/alpha/versions/1.0/archive", headers=headers),
        client.get("/projects/alpha/versions/1.0/files/app.bin", headers=headers),
        client.delete("/projects/alpha/versions/1.0/files/app.bin", headers=headers),
        client.delete("/projects/alpha/versions/1.0", headers=headers),
    ]
    for response in calls:
        assert response.status_code == 401, response.request.url


def _cookie(response, name: str) -> str:
    for header in cookie_headers(response):
        if header.startswith(f"{name}="):
            return header
    raise AssertionError(cookie_headers(response))


def _openssl_cert(certificate, key) -> None:
    from tests.conftest import openssl

    openssl(
        "req", "-x509", "-newkey", "rsa:2048", "-sha256", "-days", "1", "-nodes",
        "-keyout", str(key), "-out", str(certificate), "-subj", "/CN=version-server-jwt",
    )


def _claims() -> dict:
    now = datetime.now(timezone.utc)
    return {
        "iss": "version-server",
        "aud": "version-server",
        "sub": "admin",
        "role": "Admin",
        "iat": now,
        "exp": now + timedelta(hours=1),
    }


def _token_signed_by_other_secret() -> str:
    return jwt.encode(_claims(), "not-our-signing-secret", algorithm="HS256")


def _token_signed_by_other_certificate() -> str:
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    return jwt.encode(_claims(), key, algorithm="RS256")
