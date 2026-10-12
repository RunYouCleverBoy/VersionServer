from tests.conftest import auth, grant_access, login, make_client, version_names


def test_listing_projects_without_a_token_is_rejected(tmp_path):
    with make_client(tmp_path) as client:
        assert client.get("/projects").status_code == 401


def test_listing_projects_with_an_invalid_token_is_rejected(tmp_path):
    with make_client(tmp_path) as client:
        assert client.get("/projects", headers=auth("not-a-token")).status_code == 401


def test_admin_can_authorize_a_user(tmp_path):
    with make_client(tmp_path) as client:
        admin = login(client, "admin", "admin-pass")
        assert grant_access(client, admin, "alpha", "alice").status_code == 204
        alice = login(client, "alice", "alice-pass")
        listed = client.get("/projects", headers=auth(alice))
        assert listed.status_code == 200
        assert listed.json()["projects"] == ["alpha"]


def test_admin_sees_every_project(tmp_path):
    with make_client(tmp_path) as client:
        admin = login(client, "admin", "admin-pass")
        grant_access(client, admin, "alpha", "alice")
        grant_access(client, admin, "beta", "bob")
        listed = client.get("/projects", headers=auth(admin))
        assert listed.status_code == 200
        assert sorted(listed.json()["projects"]) == ["alpha", "beta"]


def test_admin_can_list_versions_without_a_personal_grant(tmp_path):
    with make_client(tmp_path) as client:
        admin = login(client, "admin", "admin-pass")
        grant_access(client, admin, "alpha", "alice")
        versions = client.get("/projects/alpha/versions", headers=auth(admin))
        assert versions.status_code == 200
        assert version_names(versions.json()) == []


def test_client_cannot_authorize_a_user(tmp_path):
    with make_client(tmp_path) as client:
        alice = login(client, "alice", "alice-pass")
        assert grant_access(client, alice, "alpha", "bob").status_code == 403


def test_user_with_no_grants_gets_an_empty_project_list(tmp_path):
    with make_client(tmp_path) as client:
        alice = login(client, "alice", "alice-pass")
        listed = client.get("/projects", headers=auth(alice))
        assert listed.status_code == 200
        assert listed.json()["projects"] == []


def test_user_authorized_for_one_project_sees_only_that_project(tmp_path):
    with make_client(tmp_path) as client:
        admin = login(client, "admin", "admin-pass")
        grant_access(client, admin, "alpha", "alice")
        grant_access(client, admin, "beta", "bob")
        alice = login(client, "alice", "alice-pass")
        listed = client.get("/projects", headers=auth(alice))
        assert listed.json()["projects"] == ["alpha"]


def test_a_token_only_sees_that_users_projects(tmp_path):
    with make_client(tmp_path) as client:
        admin = login(client, "admin", "admin-pass")
        grant_access(client, admin, "alpha", "alice")
        alice = login(client, "alice", "alice-pass")
        bob = login(client, "bob", "bob-pass")
        assert client.get("/projects", headers=auth(bob)).json()["projects"] == []
        assert client.get("/projects", headers=auth(alice)).json()["projects"] == ["alpha"]


def test_grants_still_apply_after_a_process_restart(tmp_path):
    storage = tmp_path / "files"
    store = tmp_path / "store.json"
    store.write_text("", encoding="utf-8")
    with make_client(tmp_path, storage_root=storage, json_store_path=store, blank_store=False) as client:
        admin = login(client, "admin", "admin-pass")
        grant_access(client, admin, "alpha", "alice")
    with make_client(tmp_path, storage_root=storage, json_store_path=store, blank_store=False) as client:
        alice = login(client, "alice", "alice-pass")
        listed = client.get("/projects", headers=auth(alice))
        assert listed.json()["projects"] == ["alpha"]
