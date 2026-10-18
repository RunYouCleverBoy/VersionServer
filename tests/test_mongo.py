import os
from datetime import datetime, timezone

import pytest

from versionserver.models import Role
from versionserver.mongo import MongoDatabase, database_name

_TEST_URI = os.environ.get("VERSION_SERVER_TEST_MONGO")


def test_database_name_defaults_when_the_uri_has_no_database():
    uri = "mongodb+srv://user:secret@example.mongodb.net/?retryWrites=true&w=majority"
    assert database_name(uri) == "version-server"
    assert database_name("mongodb://localhost:27017/ledger") == "ledger"


@pytest.fixture()
def catalog():
    if _TEST_URI is None:
        pytest.skip("VERSION_SERVER_TEST_MONGO is not set")
    existing = MongoDatabase(_TEST_URI)
    existing._client.drop_database(existing._db.name)
    existing._client.close()
    database = MongoDatabase(_TEST_URI)
    yield database
    database._client.drop_database(database._db.name)
    database._client.close()


def test_empty_database_seeds_the_default_users(catalog):
    assert catalog.authenticate("admin", "admin-pass") is not None
    assert catalog.authenticate("alice", "wrong") is None
    assert {user.id for user in catalog.users()} == {"admin", "alice", "bob"}


def test_duplicate_user_is_rejected(catalog):
    assert catalog.add_user("carol", "carol-pass", Role.Client) is not None
    assert catalog.add_user("carol", "other-pass", Role.Client) is None
    assert catalog.add_user(" ", "carol-pass", Role.Client) is None
    assert catalog.change_password("carol", "carol-pass", "") is None
    assert catalog.change_password("carol", "wrong", "next-pass") is False
    assert catalog.change_password("carol", "carol-pass", "next-pass") is True
    assert catalog.authenticate("carol", "next-pass") is not None


def test_deleting_a_user_removes_their_grants(catalog):
    assert catalog.add_grant("alice", "alpha") is True
    assert catalog.add_grant("nobody", "alpha") is False
    removed = catalog.remove_user("alice")
    assert removed is not None and removed.id == "alice"
    assert catalog.user("alice") is None
    assert catalog.remove_user("alice") is None
    alice = catalog.add_user("alice", "alice-pass", Role.Client)
    assert alice is not None
    assert catalog.projects_for(alice) == []


def test_versions_follow_the_newest_upload(catalog):
    catalog.add_artifact("alpha", "1.0", "a.bin", datetime(2024, 1, 1, tzinfo=timezone.utc))
    catalog.add_artifact("alpha", "2.0", "b.bin", datetime(2024, 6, 1, tzinfo=timezone.utc))
    catalog.add_artifact("alpha", "1.0", "c.bin", datetime(2024, 3, 1, tzinfo=timezone.utc))
    versions = catalog.versions_for("alpha")
    assert [item.name for item in versions] == ["1.0", "2.0"]
    assert versions[1].latest is True
    catalog.add_artifact("alpha", "1.0", "a.bin", datetime(2024, 12, 1, tzinfo=timezone.utc))
    versions = catalog.versions_for("alpha")
    assert next(item for item in versions if item.name == "1.0").latest is True
    found = catalog.find_artifact("alpha", "1.0", "a.bin")
    assert found is not None
    assert found.uploaded_at == "2024-12-01T00:00:00Z"
    assert catalog.remove_artifact("alpha", "1.0", "c.bin") is not None
    catalog.remove_version("alpha", "2.0")
    assert catalog.find_artifact("alpha", "2.0", "b.bin") is None
    assert [item.file_name for item in catalog.artifacts("alpha", "1.0")] == ["a.bin"]


def test_admin_projects_list_grants_before_artifacts(catalog):
    catalog.add_grant("alice", "from-grant")
    catalog.add_artifact("from-file", "1.0", "a.bin", datetime(2024, 1, 1, tzinfo=timezone.utc))
    admin = catalog.user("admin")
    assert admin is not None
    assert catalog.projects_for(admin) == ["from-grant", "from-file"]
    assert catalog.is_authorized(admin, "from-file") is True
    alice = catalog.user("alice")
    assert alice is not None
    assert catalog.is_authorized(alice, "from-grant") is True
    assert catalog.is_authorized(alice, "from-file") is False
