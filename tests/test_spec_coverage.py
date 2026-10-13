from tests.conftest import auth, file_names, grant_access, login, make_client, upload_file


def test_protected_routes_reject_missing_tokens(tmp_path):
    with make_client(tmp_path) as client:
        assert client.get("/users").status_code == 401
        assert client.post(
            "/users",
            json={"userId": "x", "password": "y", "role": "Client"},
        ).status_code == 401
        assert client.delete("/users/alice").status_code == 401
        assert client.post("/projects/alpha/access", json={"userId": "alice"}).status_code == 401
        assert client.get("/projects/alpha/versions").status_code == 401
        assert client.get("/projects/alpha/versions/1.0/files").status_code == 401
        assert client.get("/projects/alpha/versions/1.0/files/app.bin").status_code == 401
        assert client.delete("/projects/alpha/versions/1.0/files/app.bin").status_code == 401
        upload = client.post(
            "/projects/alpha/versions/1.0/files",
            files={"file": ("app.bin", bytes([1]), "application/octet-stream")},
        )
        assert upload.status_code == 401


def test_protected_routes_reject_invalid_tokens(tmp_path):
    with make_client(tmp_path) as client:
        headers = auth("not-a-token")
        assert client.get("/users", headers=headers).status_code == 401
        assert client.get("/projects/alpha/versions", headers=headers).status_code == 401
        assert client.delete("/projects/alpha/versions/1.0/files/app.bin", headers=headers).status_code == 401


def test_enrolling_with_blank_user_id_or_empty_password_is_rejected(tmp_path):
    with make_client(tmp_path) as client:
        admin = login(client, "admin", "admin-pass")
        assert client.post(
            "/users",
            headers=auth(admin),
            json={"userId": "  ", "password": "pass", "role": "Client"},
        ).status_code == 400
        assert client.post(
            "/users",
            headers=auth(admin),
            json={"userId": "carol", "password": "", "role": "Client"},
        ).status_code == 400


def test_granting_with_blank_user_id_is_rejected(tmp_path):
    with make_client(tmp_path) as client:
        admin = login(client, "admin", "admin-pass")
        assert client.post(
            "/projects/alpha/access",
            headers=auth(admin),
            json={"userId": "  "},
        ).status_code == 400


def test_user_list_does_not_expose_passwords(tmp_path):
    with make_client(tmp_path) as client:
        admin = login(client, "admin", "admin-pass")
        listed = client.get("/users", headers=auth(admin))
        users = listed.json()["users"]
        assert {user["userId"] for user in users} == {"admin", "alice", "bob"}
        raw = listed.text
        assert "password" not in raw
        assert "admin-pass" not in raw


def test_admin_sees_projects_that_exist_only_as_uploaded_artifacts(tmp_path):
    with make_client(tmp_path) as client:
        admin = login(client, "admin", "admin-pass")
        upload_file(client, admin, "solo", "1.0", "app.bin", bytes([9]))
        listed = client.get("/projects", headers=auth(admin))
        assert listed.json()["projects"] == ["solo"]


def test_admin_can_download_without_a_personal_grant(tmp_path):
    with make_client(tmp_path) as client:
        admin = login(client, "admin", "admin-pass")
        upload_file(client, admin, "alpha", "1.0", "app.bin", bytes([1, 2]))
        files = client.get("/projects/alpha/versions/1.0/files", headers=auth(admin))
        assert file_names(files.json()) == ["app.bin"]
        download = client.get("/projects/alpha/versions/1.0/files/app.bin", headers=auth(admin))
        assert download.status_code == 200
        assert download.content == bytes([1, 2])


def test_path_traversal_in_uploaded_file_name_is_rejected(tmp_path):
    with make_client(tmp_path) as client:
        admin = login(client, "admin", "admin-pass")
        upload = upload_file(client, admin, "alpha", "1.0", "../escape.bin", bytes([1]))
        assert upload.status_code == 400


def test_nested_file_can_be_deleted_by_admin(tmp_path):
    with make_client(tmp_path) as client:
        admin = login(client, "admin", "admin-pass")
        grant_access(client, admin, "alpha", "alice")
        upload_file(client, admin, "alpha", "1.0", "lib/util.bin", bytes([3]))
        deleted = client.delete("/projects/alpha/versions/1.0/files/lib/util.bin", headers=auth(admin))
        assert deleted.status_code == 204
        alice = login(client, "alice", "alice-pass")
        files = client.get("/projects/alpha/versions/1.0/files", headers=auth(alice))
        assert file_names(files.json()) == []
        download = client.get("/projects/alpha/versions/1.0/files/lib/util.bin", headers=auth(alice))
        assert download.status_code == 404


def test_enrolled_user_survives_restart(tmp_path):
    storage = tmp_path / "files"
    store = tmp_path / "store.json"
    store.write_text("", encoding="utf-8")
    with make_client(tmp_path, storage_root=storage, json_store_path=store, blank_store=False) as client:
        admin = login(client, "admin", "admin-pass")
        client.post(
            "/users",
            headers=auth(admin),
            json={"userId": "carol", "password": "carol-pass", "role": "Client"},
        )
        grant_access(client, admin, "alpha", "carol")
    with make_client(tmp_path, storage_root=storage, json_store_path=store, blank_store=False) as client:
        carol = login(client, "carol", "carol-pass")
        listed = client.get("/projects", headers=auth(carol))
        assert listed.json()["projects"] == ["alpha"]
