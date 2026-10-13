from tests.conftest import login_response, make_client, openssl
from versionserver.docs import is_safe_doc_path, rewrite_doc_links
from versionserver.signing import HmacSigning, InvalidSigning, load_jwt_signing
from versionserver.tls import InvalidTls, MISSING, MissingTls, ReadyTls, invalid_guidance, load_server_tls, subject_cn


def test_doc_without_trailing_slash_redirects(tmp_path):
    with make_client(tmp_path, follow_redirects=False) as client:
        response = client.get("/doc")
        assert response.status_code == 302
        assert response.headers["location"] == "/doc/"


def test_doc_index_lists_documentation_pages(tmp_path):
    with make_client(tmp_path) as client:
        response = client.get("/doc/")
        assert response.status_code == 200
        body = response.text
        assert "Documentation" in body
        assert "/doc/API.md" in body
        assert "/doc/specs/API-SYSTEM.md" in body
        assert "/doc/specs/CLIENT-SYSTEM.md" in body
        assert "/doc/styles.css" in body


def test_api_markdown_is_rendered_as_html(tmp_path):
    with make_client(tmp_path) as client:
        response = client.get("/doc/API.md")
        assert response.status_code == 200
        assert response.headers["content-type"].startswith("text/html")
        body = response.text
        assert "Version Server API" in body or "HTTP API" in body
        assert "<table>" in body or "Authentication" in body
        assert "/doc/specs/API-SYSTEM.md" in body
        assert "<script>alert" not in body


def test_system_specs_render_with_tables(tmp_path):
    with make_client(tmp_path) as client:
        response = client.get("/doc/specs/API-SYSTEM.md")
        assert response.status_code == 200
        body = response.text.lower()
        assert "<table>" in response.text
        assert "jwt" in body or "token" in body


def test_doc_stylesheet_is_served(tmp_path):
    with make_client(tmp_path) as client:
        response = client.get("/doc/styles.css")
        assert response.status_code == 200
        assert "--accent" in response.text


def test_doc_path_traversal_is_rejected(tmp_path):
    with make_client(tmp_path) as client:
        assert client.get("/doc/../Application.kt").status_code == 404
        assert client.get("/doc/specs/../../build.gradle.kts").status_code == 404
        assert client.get("/doc/missing.md").status_code == 404


def test_relative_markdown_links_resolve_under_doc():
    rewritten = rewrite_doc_links("See [spec](specs/API-SYSTEM.md) and [abs](/ui/).", "API.md")
    assert "(/doc/specs/API-SYSTEM.md)" in rewritten
    assert "(/ui/)" in rewritten


def test_safe_doc_path_helper_rejects_traversal():
    assert is_safe_doc_path("API.md")
    assert is_safe_doc_path("specs/API-SYSTEM.md")
    assert not is_safe_doc_path("../secret.md")
    assert not is_safe_doc_path("specs/../../secret.md")
    assert not is_safe_doc_path("/etc/passwd.md")


def test_root_redirects_to_the_web_client(tmp_path):
    with make_client(tmp_path, follow_redirects=False) as client:
        response = client.get("/")
        assert response.status_code == 302
        assert response.headers["location"] == "/ui/"


def test_ui_without_trailing_slash_redirects(tmp_path):
    with make_client(tmp_path, follow_redirects=False) as client:
        response = client.get("/ui")
        assert response.status_code == 302
        assert response.headers["location"] == "/ui/"


def test_web_client_is_served(tmp_path):
    with make_client(tmp_path) as client:
        response = client.get("/ui/")
        assert response.status_code == 200
        body = response.text
        assert "Version Server" in body
        assert "Sign in" in body
        assert "/ui/app.js" in body
        assert "/ui/styles.css" in body
        assert "/doc/" in body
        assert "Docs" in body


def test_web_client_assets_and_controls(tmp_path):
    with make_client(tmp_path) as client:
        html = client.get("/ui/").text
        css = client.get("/ui/styles.css")
        script = client.get("/ui/app.js").text
        assert css.status_code == 200
        assert "--accent" in css.text
        assert "/login" in script
        assert "/projects" in script
        assert "collectFromDataTransfer" in script
        assert "webkitGetAsEntry" in script
        assert "drop-zone" in html
        assert "Drag files or folders here" in html
        assert "webkitdirectory" in html
        assert "Choose folder" in html
        assert "enroll-form" in html
        assert "Enroll user" in html
        assert "POST" in script
        assert "/users" in script
        assert "password-form" in html
        assert "Change password" in html
        assert "/password" in script
        assert 'path !== "/password"' in script
        assert "Current password is incorrect." in script
        assert "function isAdmin()" in script
        assert "els.adminEnroll.hidden = !isAdmin()" in script
        assert "els.adminGrant.hidden = !isAdmin()" in script
        assert "els.adminUsers.hidden = !isAdmin()" in script
        assert "els.adminUpload.hidden = !isAdmin()" in script
        assert "data-accordion" in script
        assert "other.open = false" in script
        assert "encodeURIComponent(segment)" in script
        assert "credentials" in script
        assert "enterWorkspace" in script
        assert "/logout" in script
        assert 'method: "POST"' in script
        assert "clearSession" in script
        assert "showToast" in script
        assert "localStorage" not in script
        assert "pick-files" in script or "pickFiles" in script
        assert "pick-folder" in script or "pickFolder" in script
        assert "grant-form" in script or "grantForm" in script
        assert "User must exist" in script or "Grant failed" in script
        assert "/versions/" in script and "DELETE" in script
        assert "Delete version" in script or "Deleted version" in script
        assert "(latest)" in script
        assert "uploadedAt" in script
        assert "/archive" in script
        assert "buildFileTree" in script
        assert "downloadIconButton" in script
        assert "downloadVersion" in script or "download-version" in script
        assert "grant-form" in html
        assert "Grant access" in html
        assert "account-box" in html
        assert "admin-upload" in html
        assert "Choose files" in html
        assert 'data-accordion="rail"' in html
        assert '<details id="admin-enroll"' in html
        assert '<details id="admin-users"' in html
        assert '<details id="admin-grant"' in html
        assert '<details id="account-box"' in html
        assert "download-version" in html
        assert "Download all" in html
        assert "file-tree" in html


def test_login_response_includes_role(tmp_path):
    with make_client(tmp_path) as client:
        response = login_response(client, "admin", "admin-pass")
        assert response.json()["userId"] == "admin"
        assert response.json()["role"] == "Admin"
        assert response.json()["token"]


def test_unknown_path_is_not_found(tmp_path):
    with make_client(tmp_path) as client:
        assert client.get("/does-not-exist").status_code == 404


def test_unset_certificate_keeps_hmac_signing():
    assert isinstance(load_jwt_signing(lambda _name: None), HmacSigning)


def test_certificate_without_a_key_is_rejected():
    loaded = load_jwt_signing(lambda name: "certs/jwt.crt" if name == "VERSION_SERVER_JWT_CERT" else None)
    assert isinstance(loaded, InvalidSigning)
    assert "VERSION_SERVER_JWT_KEY" in loaded.detail


def test_private_key_must_match_the_certificate(tmp_path):
    certificate = tmp_path / "jwt.crt"
    key = tmp_path / "jwt.key"
    other_key = tmp_path / "other.key"
    _cert(tmp_path, certificate, key)
    _cert(tmp_path, tmp_path / "other.crt", other_key)
    loaded = load_jwt_signing(
        lambda name: {
            "VERSION_SERVER_JWT_CERT": str(certificate),
            "VERSION_SERVER_JWT_KEY": str(other_key),
        }.get(name)
    )
    assert isinstance(loaded, InvalidSigning)
    assert "does not match" in loaded.detail


def test_missing_tls_certificate_reports_setup_guidance():
    assert isinstance(load_server_tls(lambda _name: None), MissingTls)
    assert "openssl req" in MISSING
    assert "Do not commit" in MISSING
    assert "Docker image" in MISSING
    assert "VERSION_SERVER_TLS_CERT" in MISSING
    assert "VERSION_SERVER_TLS_KEY" in MISSING
    assert "VERSION_SERVER_TLS_DIR" in MISSING


def test_pem_paths_and_inlined_pem_both_load(tmp_path):
    certificate = tmp_path / "server.crt"
    key = tmp_path / "server.key"
    _cert(tmp_path, certificate, key)
    from_paths = _ready(
        {
            "VERSION_SERVER_TLS_CERT": str(certificate),
            "VERSION_SERVER_TLS_KEY": str(key),
        }
    )
    assert str(certificate) in from_paths.description
    assert subject_cn(from_paths) == "localhost"
    from_text = _ready(
        {
            "VERSION_SERVER_TLS_CERT": certificate.read_text(encoding="utf-8"),
            "VERSION_SERVER_TLS_KEY": key.read_text(encoding="utf-8"),
            "VERSION_SERVER_HTTPS_PORT": "9443",
        }
    )
    assert from_text.description == "inlined PEM certificate"
    assert from_text.https_port == 9443
    assert subject_cn(from_paths) == subject_cn(from_text)


def test_pkcs1_private_key_is_accepted(tmp_path):
    certificate = tmp_path / "server.crt"
    key = tmp_path / "server.key"
    openssl("genrsa", "-out", str(key), "2048", cwd=tmp_path)
    openssl(
        "req", "-new", "-x509", "-key", str(key), "-out", str(certificate),
        "-days", "1", "-subj", "/CN=localhost",
        cwd=tmp_path,
    )
    loaded = _ready(
        {
            "VERSION_SERVER_TLS_CERT": str(certificate),
            "VERSION_SERVER_TLS_KEY": str(key),
        }
    )
    assert subject_cn(loaded) == "localhost"


def test_keystore_path_is_accepted(tmp_path):
    certificate = tmp_path / "server.crt"
    key = tmp_path / "server.key"
    store = tmp_path / "server.p12"
    _cert(tmp_path, certificate, key)
    openssl(
        "pkcs12", "-export", "-inkey", str(key), "-in", str(certificate), "-out", str(store),
        "-passout", "pass:test-pass", "-name", "version-server",
        cwd=tmp_path,
    )
    from_cert = _ready(
        {
            "VERSION_SERVER_TLS_CERT": str(store),
            "VERSION_SERVER_TLS_KEYSTORE_PASSWORD": "test-pass",
        }
    )
    assert from_cert.description == f"keystore {store}"
    from_store = _ready(
        {
            "VERSION_SERVER_TLS_KEYSTORE": str(store),
            "VERSION_SERVER_TLS_KEYSTORE_PASSWORD": "test-pass",
            "VERSION_SERVER_TLS_KEY_ALIAS": "version-server",
        }
    )
    assert subject_cn(from_store) == "localhost"


def test_jks_keystore_is_accepted(tmp_path):
    from datetime import datetime, timedelta, timezone

    import jks
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import rsa
    from cryptography.x509.oid import NameOID

    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "localhost")])
    now = datetime.now(timezone.utc)
    certificate = (
        x509.CertificateBuilder()
        .subject_name(name)
        .issuer_name(name)
        .public_key(key.public_key())
        .serial_number(1)
        .not_valid_before(now)
        .not_valid_after(now + timedelta(days=1))
        .sign(key, hashes.SHA256())
    )
    pkcs8 = key.private_bytes(
        serialization.Encoding.DER,
        serialization.PrivateFormat.PKCS8,
        serialization.NoEncryption(),
    )
    entry = jks.PrivateKeyEntry.new(
        "version-server",
        [certificate.public_bytes(serialization.Encoding.DER)],
        pkcs8,
    )
    store_path = tmp_path / "server.jks"
    jks.KeyStore.new("jks", [entry]).save(str(store_path), "test-pass")
    loaded = _ready(
        {
            "VERSION_SERVER_TLS_CERT": str(store_path),
            "VERSION_SERVER_TLS_KEYSTORE_PASSWORD": "test-pass",
        }
    )
    assert loaded.description == f"keystore {store_path}"
    assert subject_cn(loaded) == "localhost"


def test_a_certificate_without_a_key_is_rejected():
    loaded = load_server_tls(lambda name: "certs/server.crt" if name == "VERSION_SERVER_TLS_CERT" else None)
    assert isinstance(loaded, InvalidTls)
    assert "VERSION_SERVER_TLS_KEY" in loaded.detail
    assert "openssl req" in invalid_guidance(loaded.detail)


def test_a_missing_certificate_file_is_rejected():
    loaded = load_server_tls(
        lambda name: {
            "VERSION_SERVER_TLS_CERT": "/tmp/version-server-missing.crt",
            "VERSION_SERVER_TLS_KEY": "/tmp/version-server-missing.key",
        }.get(name)
    )
    assert isinstance(loaded, InvalidTls)
    assert "not found" in loaded.detail


def test_https_port_cannot_reuse_the_http_port(tmp_path):
    certificate = tmp_path / "server.crt"
    key = tmp_path / "server.key"
    _cert(tmp_path, certificate, key)
    loaded = load_server_tls(
        lambda name: {
            "VERSION_SERVER_TLS_CERT": str(certificate),
            "VERSION_SERVER_TLS_KEY": str(key),
            "VERSION_SERVER_HTTPS_PORT": "8080",
        }.get(name)
    )
    assert isinstance(loaded, InvalidTls)


def _cert(directory, certificate, key) -> None:
    openssl(
        "req", "-x509", "-newkey", "rsa:2048", "-sha256", "-days", "1", "-nodes",
        "-keyout", str(key), "-out", str(certificate), "-subj", "/CN=localhost",
        cwd=directory,
    )


def _ready(values: dict[str, str]):
    loaded = load_server_tls(lambda name: values.get(name))
    assert isinstance(loaded, ReadyTls)
    return loaded.tls
