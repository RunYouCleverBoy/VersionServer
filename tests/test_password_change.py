from tests.conftest import auth, login, login_response, make_client


def test_client_can_change_password(tmp_path):
    with make_client(tmp_path) as client:
        _assert_change(client, "alice", "alice-pass", "alice-new", bearer=True)


def test_admin_can_change_password(tmp_path):
    with make_client(tmp_path) as client:
        _assert_change(client, "admin", "admin-pass", "admin-new", bearer=True)


def test_admin_can_change_password_with_cookie(tmp_path):
    with make_client(tmp_path) as client:
        _assert_change(client, "admin", "admin-pass", "admin-cookie-new", bearer=False)


def test_client_wrong_current_password_is_rejected(tmp_path):
    with make_client(tmp_path) as client:
        _assert_wrong(client, "alice", "alice-pass", bearer=True)


def test_admin_wrong_current_password_is_rejected(tmp_path):
    with make_client(tmp_path) as client:
        _assert_wrong(client, "admin", "admin-pass", bearer=True)


def test_admin_wrong_current_password_with_cookie_keeps_session(tmp_path):
    with make_client(tmp_path) as client:
        login(client, "admin", "admin-pass")
        changed = client.post(
            "/password",
            json={"currentPassword": "wrong", "newPassword": "should-not-apply"},
        )
        assert changed.status_code == 401
        assert client.get("/projects").status_code == 200
        still = login_response(client, "admin", "admin-pass")
        assert still.json()["userId"] == "admin"


def test_empty_new_password_is_rejected(tmp_path):
    with make_client(tmp_path) as client:
        token = login(client, "alice", "alice-pass")
        changed = client.post(
            "/password",
            headers=auth(token),
            json={"currentPassword": "alice-pass", "newPassword": ""},
        )
        assert changed.status_code == 400


def test_password_change_without_a_token_is_rejected(tmp_path):
    with make_client(tmp_path) as client:
        changed = client.post(
            "/password",
            json={"currentPassword": "alice-pass", "newPassword": "alice-new"},
        )
        assert changed.status_code == 401


def test_changed_password_survives_restart(tmp_path):
    storage = tmp_path / "files"
    store = tmp_path / "store.json"
    store.write_text("", encoding="utf-8")
    with make_client(tmp_path, storage_root=storage, json_store_path=store, blank_store=False) as client:
        token = login(client, "alice", "alice-pass")
        client.post(
            "/password",
            headers=auth(token),
            json={"currentPassword": "alice-pass", "newPassword": "alice-persisted"},
        )
    with make_client(tmp_path, storage_root=storage, json_store_path=store, blank_store=False) as client:
        login_body = login_response(client, "alice", "alice-persisted")
        assert login_body.json()["userId"] == "alice"


def _assert_change(client, user_id, current_password, new_password, bearer: bool) -> None:
    token = login(client, user_id, current_password)
    headers = auth(token) if bearer else None
    changed = client.post(
        "/password",
        headers=headers,
        json={"currentPassword": current_password, "newPassword": new_password},
    )
    assert changed.status_code == 204
    assert login_response(client, user_id, current_password).status_code == 401
    fresh = login_response(client, user_id, new_password)
    assert fresh.json()["userId"] == user_id
    assert fresh.json()["token"] != ""


def _assert_wrong(client, user_id, current_password, bearer: bool) -> None:
    token = login(client, user_id, current_password)
    headers = auth(token) if bearer else None
    changed = client.post(
        "/password",
        headers=headers,
        json={"currentPassword": "wrong", "newPassword": f"{user_id}-new"},
    )
    assert changed.status_code == 401
    assert login_response(client, user_id, current_password).json()["userId"] == user_id
