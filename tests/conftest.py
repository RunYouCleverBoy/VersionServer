import subprocess
import zipfile
from io import BytesIO
from pathlib import Path

from fastapi.testclient import TestClient

from versionserver.app import create_app
from versionserver.config import AppConfig


def make_client(
    tmp_path: Path,
    *,
    storage_root: Path | None = None,
    json_store_path: Path | None = None,
    jwt_secret: str = "test-secret",
    jwt_certificate=None,
    jwt_private_key=None,
    follow_redirects: bool = True,
    blank_store: bool = True,
) -> TestClient:
    storage = storage_root or (tmp_path / "files")
    store_path = json_store_path or (tmp_path / "store.json")
    if blank_store and json_store_path is None and not store_path.exists():
        store_path.parent.mkdir(parents=True, exist_ok=True)
        store_path.write_text("", encoding="utf-8")
    app = create_app(
        AppConfig(
            storage_root=storage,
            json_store_path=store_path,
            jwt_secret=jwt_secret,
            jwt_certificate=jwt_certificate,
            jwt_private_key=jwt_private_key,
        )
    )
    return TestClient(app, follow_redirects=follow_redirects)


def login(client: TestClient, user_id: str, password: str) -> str:
    response = client.post("/login", json={"userId": user_id, "password": password})
    assert response.status_code == 200, response.text
    return response.json()["token"]


def login_response(client: TestClient, user_id: str, password: str):
    return client.post("/login", json={"userId": user_id, "password": password})


def auth(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def upload_file(client: TestClient, token: str, project: str, version: str, file_name: str, data: bytes):
    return client.post(
        f"/projects/{project}/versions/{version}/files",
        headers=auth(token),
        files={"file": (file_name, data, "application/octet-stream")},
    )


def grant_access(client: TestClient, token: str, project: str, user_id: str):
    return client.post(
        f"/projects/{project}/access",
        headers=auth(token),
        json={"userId": user_id},
    )


def is_jwt(token: str) -> bool:
    parts = token.split(".")
    return len(parts) == 3 and all(parts)


def version_names(payload: dict) -> list[str]:
    return [item["name"] for item in payload["versions"]]


def file_names(payload: dict) -> list[str]:
    return [item["name"] for item in payload["files"]]


def unzip(data: bytes) -> dict[str, bytes]:
    found: dict[str, bytes] = {}
    with zipfile.ZipFile(BytesIO(data)) as archive:
        for info in archive.infolist():
            if info.is_dir():
                continue
            found[info.filename] = archive.read(info)
    return found


def openssl(*args: str, cwd: Path | None = None) -> None:
    completed = subprocess.run(
        ["openssl", *args],
        cwd=cwd,
        check=False,
        capture_output=True,
        text=True,
    )
    if completed.returncode != 0:
        raise AssertionError(completed.stdout + completed.stderr)


def cookie_headers(response) -> list[str]:
    return response.headers.get_list("set-cookie")
