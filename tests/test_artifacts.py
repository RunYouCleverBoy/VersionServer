from datetime import datetime, timezone

from tests.conftest import auth, file_names, grant_access, login, make_client, unzip, upload_file, version_names
from versionserver.database import JsonDatabase
from versionserver.files import FilesystemFileStore
from versionserver.passwords import sha256_hex
from versionserver.sync import sync_artifacts_from_disk
from versionserver.timefmt import EPOCH, parse_instant


def test_authorized_user_can_download_a_whole_version_as_zip(tmp_path):
    with make_client(tmp_path) as client:
        admin = login(client, "admin", "admin-pass")
        upload_file(client, admin, "alpha", "1.0", "app.bin", bytes([1, 2]))
        upload_file(client, admin, "alpha", "1.0", "lib/util.bin", bytes([3, 4]))
        grant_access(client, admin, "alpha", "alice")
        alice = login(client, "alice", "alice-pass")
        response = client.get("/projects/alpha/versions/1.0/archive", headers=auth(alice))
        assert response.status_code == 200
        assert "zip" in response.headers["content-type"]
        assert unzip(response.content) == {
            "app.bin": bytes([1, 2]),
            "lib/util.bin": bytes([3, 4]),
        }


def test_authorized_user_can_download_a_directory_prefix_as_zip(tmp_path):
    with make_client(tmp_path) as client:
        admin = login(client, "admin", "admin-pass")
        upload_file(client, admin, "alpha", "1.0", "app.bin", bytes([1]))
        upload_file(client, admin, "alpha", "1.0", "lib/util.bin", bytes([2]))
        upload_file(client, admin, "alpha", "1.0", "lib/nested/x.bin", bytes([3]))
        grant_access(client, admin, "alpha", "alice")
        alice = login(client, "alice", "alice-pass")
        response = client.get("/projects/alpha/versions/1.0/archive?prefix=lib", headers=auth(alice))
        assert response.status_code == 200
        assert unzip(response.content) == {
            "util.bin": bytes([2]),
            "nested/x.bin": bytes([3]),
        }


def test_archive_with_unknown_prefix_is_not_found(tmp_path):
    with make_client(tmp_path) as client:
        admin = login(client, "admin", "admin-pass")
        upload_file(client, admin, "alpha", "1.0", "app.bin", bytes([1]))
        response = client.get("/projects/alpha/versions/1.0/archive?prefix=missing", headers=auth(admin))
        assert response.status_code == 404


def test_unauthorized_user_cannot_download_archive(tmp_path):
    with make_client(tmp_path) as client:
        admin = login(client, "admin", "admin-pass")
        upload_file(client, admin, "alpha", "1.0", "app.bin", bytes([1]))
        bob = login(client, "bob", "bob-pass")
        response = client.get("/projects/alpha/versions/1.0/archive", headers=auth(bob))
        assert response.status_code == 403


def test_latest_version_is_the_newest_upload(tmp_path):
    database = JsonDatabase(tmp_path / "store.json")
    database.add_artifact("alpha", "1.0", "a.bin", datetime(2024, 1, 1, tzinfo=timezone.utc))
    database.add_artifact("alpha", "2.0", "b.bin", datetime(2024, 6, 1, tzinfo=timezone.utc))
    database.add_artifact("alpha", "1.0", "c.bin", datetime(2024, 3, 1, tzinfo=timezone.utc))
    versions = database.versions_for("alpha")
    assert [item.name for item in versions] == ["1.0", "2.0"]
    assert versions[0].latest is False
    assert versions[1].latest is True


def test_reuploading_a_file_refreshes_its_timestamp(tmp_path):
    database = JsonDatabase(tmp_path / "store.json")
    database.add_artifact("alpha", "1.0", "a.bin", datetime(2024, 1, 1, tzinfo=timezone.utc))
    database.add_artifact("alpha", "2.0", "b.bin", datetime(2024, 2, 1, tzinfo=timezone.utc))
    assert next(item for item in database.versions_for("alpha") if item.name == "2.0").latest
    database.add_artifact("alpha", "1.0", "a.bin", datetime(2024, 12, 1, tzinfo=timezone.utc))
    versions = database.versions_for("alpha")
    assert next(item for item in versions if item.name == "1.0").latest
    assert not next(item for item in versions if item.name == "2.0").latest
    found = database.find_artifact("alpha", "1.0", "a.bin")
    assert found is not None
    assert found.uploaded_at == "2024-12-01T00:00:00Z"


def test_existing_store_without_uploaded_at_is_rewritten(tmp_path):
    path = tmp_path / "store.json"
    path.write_text(
        """
        {
          "users": [
            { "id": "admin", "passwordHash": "%s", "role": "Admin" }
          ],
          "grants": [],
          "artifacts": [
            { "project": "alpha", "version": "1.0", "fileName": "app.bin" }
          ]
        }
        """.strip()
        % sha256_hex("admin-pass"),
        encoding="utf-8",
    )
    JsonDatabase(path)
    rewritten = path.read_text(encoding="utf-8")
    assert "uploadedAt" in rewritten
    assert "app.bin" in rewritten


def test_startup_sync_indexes_files_missing_from_the_store(tmp_path):
    storage = tmp_path / "files"
    store = tmp_path / "store.json"
    store.write_text(
        """
        {
          "users": [
            { "id": "admin", "passwordHash": "%s", "role": "Admin" }
          ],
          "grants": [],
          "artifacts": []
        }
        """.strip()
        % sha256_hex("admin-pass"),
        encoding="utf-8",
    )
    nested = storage / "alpha" / "1.0" / "lib"
    nested.mkdir(parents=True)
    (nested / "util.bin").write_bytes(bytes([1, 2]))
    (storage / "alpha" / "1.0" / "app.bin").write_bytes(bytes([3]))
    (storage / "beta" / "2.0").mkdir(parents=True)
    (storage / "beta" / "2.0" / "notes.txt").write_bytes(bytes([4]))
    database = JsonDatabase(store)
    added = sync_artifacts_from_disk(database, FilesystemFileStore(storage))
    assert added == 3
    assert {item.file_name for item in database.artifacts("alpha", "1.0")} == {"app.bin", "lib/util.bin"}
    assert [item.file_name for item in database.artifacts("beta", "2.0")] == ["notes.txt"]
    assert all(parse_instant(item.uploaded_at) > parse_instant(EPOCH) for item in database.artifacts("alpha", "1.0"))


def test_startup_sync_does_not_overwrite_existing_metadata(tmp_path):
    storage = tmp_path / "files"
    store = tmp_path / "store.json"
    stamped = "2024-01-01T00:00:00Z"
    store.write_text(
        """
        {
          "users": [
            { "id": "admin", "passwordHash": "%s", "role": "Admin" }
          ],
          "grants": [],
          "artifacts": [
            {
              "project": "alpha",
              "version": "1.0",
              "fileName": "app.bin",
              "uploadedAt": "%s"
            }
          ]
        }
        """.strip()
        % (sha256_hex("admin-pass"), stamped),
        encoding="utf-8",
    )
    (storage / "alpha" / "1.0").mkdir(parents=True)
    (storage / "alpha" / "1.0" / "app.bin").write_bytes(bytes([1]))
    (storage / "alpha" / "1.0" / "extra.bin").write_bytes(bytes([2]))
    database = JsonDatabase(store)
    added = sync_artifacts_from_disk(database, FilesystemFileStore(storage))
    assert added == 1
    found = database.find_artifact("alpha", "1.0", "app.bin")
    extra = database.find_artifact("alpha", "1.0", "extra.bin")
    assert found is not None and found.uploaded_at == stamped
    assert extra is not None and extra.file_name == "extra.bin"


def test_module_startup_sync_makes_on_disk_files_visible(tmp_path):
    storage = tmp_path / "files"
    (storage / "alpha" / "1.0").mkdir(parents=True)
    (storage / "alpha" / "1.0" / "from-disk.bin").write_bytes(bytes([9]))
    store = tmp_path / "store.json"
    store.write_text(
        """
        {
          "users": [
            { "id": "admin", "passwordHash": "%s", "role": "Admin" },
            { "id": "alice", "passwordHash": "%s", "role": "Client" }
          ],
          "grants": [
            { "userId": "alice", "project": "alpha" }
          ],
          "artifacts": []
        }
        """.strip()
        % (sha256_hex("admin-pass"), sha256_hex("alice-pass")),
        encoding="utf-8",
    )
    with make_client(tmp_path, storage_root=storage, json_store_path=store, blank_store=False) as client:
        alice = login(client, "alice", "alice-pass")
        versions = client.get("/projects/alpha/versions", headers=auth(alice))
        assert versions.status_code == 200
        assert version_names(versions.json()) == ["1.0"]
        files = client.get("/projects/alpha/versions/1.0/files", headers=auth(alice))
        assert file_names(files.json()) == ["from-disk.bin"]


def test_list_all_walks_project_version_trees(tmp_path):
    root = tmp_path / "files"
    (root / "alpha" / "1.0" / "lib").mkdir(parents=True)
    (root / "alpha" / "1.0" / "app.bin").write_bytes(bytes([1]))
    (root / "alpha" / "1.0" / "lib" / "util.bin").write_bytes(bytes([2]))
    (root / "readme.txt").write_bytes(bytes([3]))
    (root / "lonely-project").mkdir()
    listed = {
        (item.project, item.version, item.file_name)
        for item in FilesystemFileStore(root).list_all()
    }
    assert listed == {
        ("alpha", "1.0", "app.bin"),
        ("alpha", "1.0", "lib/util.bin"),
    }
