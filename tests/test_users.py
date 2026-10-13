from tests.conftest import auth, grant_access, login, login_response, make_client
from versionserver.database import JsonDatabase
from versionserver.models import Role


def test_missing_store_file_is_created_with_default_users(tmp_path):
    path = tmp_path / "nested" / "store.json"
    assert not path.exists()
    database = JsonDatabase(path)
    assert path.exists()
    assert "passwordHash" in path.read_text(encoding="utf-8")
    assert database.authenticate("admin", "admin-pass").role == Role.Admin
    assert database.authenticate("alice", "alice-pass").role == Role.Client
    assert database.authenticate("bob", "bob-pass").role == Role.Client


def test_fresh_server_accepts_default_admin_login(tmp_path):
    storage = tmp_path / "files"
    store = tmp_path / "json" / "store.json"
    assert not store.exists()
    with make_client(tmp_path, storage_root=storage, json_store_path=store, blank_store=False) as client:
        response = login_response(client, "admin", "admin-pass")
        assert response.status_code == 200
        assert response.json()["userId"] == "admin"
        assert store.exists()


def test_admin_can_enroll_a_client(tmp_path):
    with make_client(tmp_path) as client:
        admin = login(client, "admin", "admin-pass")
        enrolled = client.post(
            "/users",
            headers=auth(admin),
            json={"userId": "carol", "password": "carol-pass", "role": "Client"},
        )
        assert enrolled.status_code == 201
        assert enrolled.json() == {"userId": "carol", "role": "Client"}
        logged_in = login_response(client, "carol", "carol-pass")
        assert logged_in.json()["userId"] == "carol"
        assert logged_in.json()["role"] == "Client"
        assert logged_in.json()["token"]


def test_admin_can_enroll_another_admin(tmp_path):
    with make_client(tmp_path) as client:
        admin = login(client, "admin", "admin-pass")
        enrolled = client.post(
            "/users",
            headers=auth(admin),
            json={"userId": "root2", "password": "root2-pass", "role": "Admin"},
        )
        assert enrolled.status_code == 201
        assert enrolled.json()["role"] == "Admin"


def test_client_cannot_enroll_a_user(tmp_path):
    with make_client(tmp_path) as client:
        alice = login(client, "alice", "alice-pass")
        enrolled = client.post(
            "/users",
            headers=auth(alice),
            json={"userId": "carol", "password": "carol-pass", "role": "Client"},
        )
        assert enrolled.status_code == 403


def test_enrolling_a_duplicate_user_id_is_rejected(tmp_path):
    with make_client(tmp_path) as client:
        admin = login(client, "admin", "admin-pass")
        enrolled = client.post(
            "/users",
            headers=auth(admin),
            json={"userId": "alice", "password": "new-pass", "role": "Client"},
        )
        assert enrolled.status_code == 409


def test_granting_access_to_an_unknown_user_is_rejected(tmp_path):
    with make_client(tmp_path) as client:
        admin = login(client, "admin", "admin-pass")
        grant = grant_access(client, admin, "alpha", "nobody")
        assert grant.status_code == 404
        alice = login(client, "alice", "alice-pass")
        listed = client.get("/projects", headers=auth(alice))
        assert listed.json()["projects"] == []


def test_admin_can_grant_access_after_enrolling(tmp_path):
    with make_client(tmp_path) as client:
        admin = login(client, "admin", "admin-pass")
        client.post(
            "/users",
            headers=auth(admin),
            json={"userId": "carol", "password": "carol-pass", "role": "Client"},
        )
        grant = grant_access(client, admin, "alpha", "carol")
        assert grant.status_code == 204
        carol = login(client, "carol", "carol-pass")
        listed = client.get("/projects", headers=auth(carol))
        assert listed.json()["projects"] == ["alpha"]


def test_admin_can_list_enrolled_users(tmp_path):
    with make_client(tmp_path) as client:
        admin = login(client, "admin", "admin-pass")
        listed = client.get("/users", headers=auth(admin))
        assert listed.status_code == 200
        assert sorted(user["userId"] for user in listed.json()["users"]) == ["admin", "alice", "bob"]


def test_client_cannot_list_users(tmp_path):
    with make_client(tmp_path) as client:
        alice = login(client, "alice", "alice-pass")
        assert client.get("/users", headers=auth(alice)).status_code == 403
