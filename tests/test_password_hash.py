from versionserver.passwords import sha256_hex
from versionserver.database import JsonDatabase
from versionserver.models import Role


def test_seeded_store_persists_hashes_not_plaintext(tmp_path):
    path = tmp_path / "store.json"
    path.write_text("", encoding="utf-8")
    JsonDatabase(path)
    text = path.read_text(encoding="utf-8")
    assert "admin-pass" not in text
    assert "alice-pass" not in text
    assert "bob-pass" not in text
    assert "passwordHash" in text
    assert sha256_hex("admin-pass") in text


def test_login_accepts_plaintext_against_hashed_store(tmp_path):
    path = tmp_path / "store.json"
    path.write_text("", encoding="utf-8")
    database = JsonDatabase(path)
    assert database.authenticate("admin", "admin-pass") is not None
    assert database.authenticate("admin", "wrong") is None


def test_enrolled_users_are_stored_hashed(tmp_path):
    path = tmp_path / "store.json"
    path.write_text("", encoding="utf-8")
    database = JsonDatabase(path)
    database.add_user("carol", "carol-pass", Role.Client)
    assert "carol-pass" not in path.read_text(encoding="utf-8")
    stored = database.user("carol")
    assert stored is not None
    assert stored.password_hash == sha256_hex("carol-pass")
    assert database.authenticate("carol", "carol-pass") is not None
