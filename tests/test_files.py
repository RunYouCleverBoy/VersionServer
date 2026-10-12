from tests.conftest import auth, file_names, grant_access, login, make_client, upload_file, version_names


def test_admin_uploads_and_authorized_user_lists_and_downloads(tmp_path):
    with make_client(tmp_path) as client:
        admin = login(client, "admin", "admin-pass")
        grant_access(client, admin, "alpha", "alice")
        payload = bytes([10, 20, 30])
        upload = upload_file(client, admin, "alpha", "1.0", "app.bin", payload)
        assert upload.status_code == 201
        alice = login(client, "alice", "alice-pass")
        versions = client.get("/projects/alpha/versions", headers=auth(alice))
        assert versions.status_code == 200
        assert version_names(versions.json()) == ["1.0"]
        files = client.get("/projects/alpha/versions/1.0/files", headers=auth(alice))
        assert file_names(files.json()) == ["app.bin"]
        download = client.get("/projects/alpha/versions/1.0/files/app.bin", headers=auth(alice))
        assert download.status_code == 200
        assert download.content == payload


def test_client_cannot_upload(tmp_path):
    with make_client(tmp_path) as client:
        alice = login(client, "alice", "alice-pass")
        upload = upload_file(client, alice, "alpha", "1.0", "app.bin", bytes([1]))
        assert upload.status_code == 403


def test_unauthorized_user_cannot_list_or_download(tmp_path):
    with make_client(tmp_path) as client:
        admin = login(client, "admin", "admin-pass")
        grant_access(client, admin, "alpha", "alice")
        upload_file(client, admin, "alpha", "1.0", "app.bin", bytes([1, 2]))
        bob = login(client, "bob", "bob-pass")
        assert client.get("/projects/alpha/versions", headers=auth(bob)).status_code == 403
        assert client.get("/projects/alpha/versions/1.0/files", headers=auth(bob)).status_code == 403
        assert client.get("/projects/alpha/versions/1.0/files/app.bin", headers=auth(bob)).status_code == 403


def test_second_version_on_the_same_project_is_listed(tmp_path):
    with make_client(tmp_path) as client:
        admin = login(client, "admin", "admin-pass")
        grant_access(client, admin, "alpha", "alice")
        upload_file(client, admin, "alpha", "1.0", "app.bin", bytes([1]))
        upload_file(client, admin, "alpha", "2.0", "app.bin", bytes([2]))
        alice = login(client, "alice", "alice-pass")
        versions = client.get("/projects/alpha/versions", headers=auth(alice))
        assert sorted(version_names(versions.json())) == ["1.0", "2.0"]


def test_a_version_can_hold_more_than_one_file(tmp_path):
    with make_client(tmp_path) as client:
        admin = login(client, "admin", "admin-pass")
        grant_access(client, admin, "alpha", "alice")
        upload_file(client, admin, "alpha", "1.0", "app.bin", bytes([1]))
        upload_file(client, admin, "alpha", "1.0", "notes.txt", bytes([2, 3]))
        alice = login(client, "alice", "alice-pass")
        files = client.get("/projects/alpha/versions/1.0/files", headers=auth(alice))
        assert sorted(file_names(files.json())) == ["app.bin", "notes.txt"]
        app = client.get("/projects/alpha/versions/1.0/files/app.bin", headers=auth(alice))
        notes = client.get("/projects/alpha/versions/1.0/files/notes.txt", headers=auth(alice))
        assert app.content == bytes([1])
        assert notes.content == bytes([2, 3])


def test_projects_stay_isolated(tmp_path):
    with make_client(tmp_path) as client:
        admin = login(client, "admin", "admin-pass")
        grant_access(client, admin, "alpha", "alice")
        grant_access(client, admin, "beta", "alice")
        upload_file(client, admin, "alpha", "1.0", "app.bin", bytes([1]))
        upload_file(client, admin, "beta", "1.0", "app.bin", bytes([9]))
        alice = login(client, "alice", "alice-pass")
        alpha = client.get("/projects/alpha/versions/1.0/files/app.bin", headers=auth(alice))
        beta = client.get("/projects/beta/versions/1.0/files/app.bin", headers=auth(alice))
        assert alpha.content == bytes([1])
        assert beta.content == bytes([9])
        versions = client.get("/projects/alpha/versions", headers=auth(alice))
        assert version_names(versions.json()) == ["1.0"]


def test_nested_file_paths_can_be_listed_and_downloaded(tmp_path):
    with make_client(tmp_path) as client:
        admin = login(client, "admin", "admin-pass")
        grant_access(client, admin, "alpha", "alice")
        upload_file(client, admin, "alpha", "1.0", "lib/util.bin", bytes([4, 5]))
        alice = login(client, "alice", "alice-pass")
        files = client.get("/projects/alpha/versions/1.0/files", headers=auth(alice))
        assert file_names(files.json()) == ["lib/util.bin"]
        download = client.get("/projects/alpha/versions/1.0/files/lib/util.bin", headers=auth(alice))
        assert download.status_code == 200
        assert download.content == bytes([4, 5])


def test_uploaded_files_survive_restart(tmp_path):
    storage = tmp_path / "files"
    store = tmp_path / "store.json"
    store.write_text("", encoding="utf-8")
    with make_client(tmp_path, storage_root=storage, json_store_path=store, blank_store=False) as client:
        admin = login(client, "admin", "admin-pass")
        grant_access(client, admin, "alpha", "alice")
        upload_file(client, admin, "alpha", "1.0", "app.bin", bytes([7, 8, 9]))
    with make_client(tmp_path, storage_root=storage, json_store_path=store, blank_store=False) as client:
        alice = login(client, "alice", "alice-pass")
        files = client.get("/projects/alpha/versions/1.0/files", headers=auth(alice))
        download = client.get("/projects/alpha/versions/1.0/files/app.bin", headers=auth(alice))
        assert file_names(files.json()) == ["app.bin"]
        assert download.content == bytes([7, 8, 9])


def test_admin_delete_removes_the_file(tmp_path):
    with make_client(tmp_path) as client:
        admin = login(client, "admin", "admin-pass")
        grant_access(client, admin, "alpha", "alice")
        upload_file(client, admin, "alpha", "1.0", "app.bin", bytes([1, 2, 3]))
        deleted = client.delete("/projects/alpha/versions/1.0/files/app.bin", headers=auth(admin))
        assert deleted.status_code == 204
        alice = login(client, "alice", "alice-pass")
        files = client.get("/projects/alpha/versions/1.0/files", headers=auth(alice))
        download = client.get("/projects/alpha/versions/1.0/files/app.bin", headers=auth(alice))
        assert file_names(files.json()) == []
        assert download.status_code == 404


def test_client_cannot_delete_a_file(tmp_path):
    with make_client(tmp_path) as client:
        admin = login(client, "admin", "admin-pass")
        grant_access(client, admin, "alpha", "alice")
        upload_file(client, admin, "alpha", "1.0", "app.bin", bytes([1]))
        alice = login(client, "alice", "alice-pass")
        deleted = client.delete("/projects/alpha/versions/1.0/files/app.bin", headers=auth(alice))
        assert deleted.status_code == 403


def test_download_of_a_missing_file_is_not_found(tmp_path):
    with make_client(tmp_path) as client:
        admin = login(client, "admin", "admin-pass")
        grant_access(client, admin, "alpha", "alice")
        alice = login(client, "alice", "alice-pass")
        download = client.get("/projects/alpha/versions/1.0/files/missing.bin", headers=auth(alice))
        assert download.status_code == 404


def test_admin_can_delete_a_whole_version(tmp_path):
    with make_client(tmp_path) as client:
        admin = login(client, "admin", "admin-pass")
        grant_access(client, admin, "alpha", "alice")
        upload_file(client, admin, "alpha", "1.0", "app.bin", bytes([1]))
        upload_file(client, admin, "alpha", "1.0", "lib/util.bin", bytes([2]))
        upload_file(client, admin, "alpha", "2.0", "app.bin", bytes([3]))
        deleted = client.delete("/projects/alpha/versions/1.0", headers=auth(admin))
        assert deleted.status_code == 204
        alice = login(client, "alice", "alice-pass")
        versions = client.get("/projects/alpha/versions", headers=auth(alice))
        assert version_names(versions.json()) == ["2.0"]
        gone = client.get("/projects/alpha/versions/1.0/files/app.bin", headers=auth(alice))
        kept = client.get("/projects/alpha/versions/2.0/files/app.bin", headers=auth(alice))
        assert gone.status_code == 404
        assert kept.status_code == 200


def test_client_cannot_delete_a_version(tmp_path):
    with make_client(tmp_path) as client:
        admin = login(client, "admin", "admin-pass")
        grant_access(client, admin, "alpha", "alice")
        upload_file(client, admin, "alpha", "1.0", "app.bin", bytes([1]))
        alice = login(client, "alice", "alice-pass")
        deleted = client.delete("/projects/alpha/versions/1.0", headers=auth(alice))
        assert deleted.status_code == 403
        versions = client.get("/projects/alpha/versions", headers=auth(alice))
        assert version_names(versions.json()) == ["1.0"]


def test_deleting_an_unknown_version_is_idempotent(tmp_path):
    with make_client(tmp_path) as client:
        admin = login(client, "admin", "admin-pass")
        deleted = client.delete("/projects/alpha/versions/missing", headers=auth(admin))
        assert deleted.status_code == 204
