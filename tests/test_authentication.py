from tests.conftest import is_jwt, login_response, make_client


def test_admin_authenticates_and_receives_a_token(tmp_path):
    with make_client(tmp_path) as client:
        response = login_response(client, "admin", "admin-pass")
        assert response.status_code == 200
        body = response.json()
        assert is_jwt(body["token"])
        assert body["userId"] == "admin"
        assert body["role"] == "Admin"


def test_client_authenticates_and_receives_a_token(tmp_path):
    with make_client(tmp_path) as client:
        response = login_response(client, "alice", "alice-pass")
        assert response.status_code == 200
        body = response.json()
        assert is_jwt(body["token"])
        assert body["userId"] == "alice"
        assert body["role"] == "Client"


def test_bad_credentials_are_rejected(tmp_path):
    with make_client(tmp_path) as client:
        response = login_response(client, "admin", "wrong")
        assert response.status_code == 401


def test_users_can_still_authenticate_after_a_process_restart(tmp_path):
    storage = tmp_path / "files"
    store = tmp_path / "store.json"
    store.write_text("", encoding="utf-8")
    with make_client(tmp_path, storage_root=storage, json_store_path=store, blank_store=False) as client:
        assert login_response(client, "admin", "admin-pass").status_code == 200
    with make_client(tmp_path, storage_root=storage, json_store_path=store, blank_store=False) as client:
        second = login_response(client, "admin", "admin-pass")
        assert second.status_code == 200
        assert second.json()["token"] != ""
