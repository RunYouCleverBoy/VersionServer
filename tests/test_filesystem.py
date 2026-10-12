from versionserver.files import FilesystemFileStore


def test_storing_a_file_makes_it_readable(tmp_path):
    store = FilesystemFileStore(tmp_path)
    payload = bytes([1, 2, 3, 4])
    assert store.store("alpha", "1.0", "app.bin", _stream(payload))
    assert store.read("alpha", "1.0", "app.bin") == payload


def test_listing_files_returns_what_was_stored(tmp_path):
    store = FilesystemFileStore(tmp_path)
    store.store("alpha", "1.0", "a.bin", _stream(bytes([1])))
    store.store("alpha", "1.0", "b.bin", _stream(bytes([2])))
    assert sorted(store.list("alpha", "1.0")) == ["a.bin", "b.bin"]


def test_deleting_a_file_makes_a_later_read_miss(tmp_path):
    store = FilesystemFileStore(tmp_path)
    store.store("alpha", "1.0", "app.bin", _stream(bytes([1, 2, 3])))
    store.delete("alpha", "1.0", "app.bin")
    assert store.read("alpha", "1.0", "app.bin") is None


def test_missing_file_returns_none(tmp_path):
    store = FilesystemFileStore(tmp_path)
    assert store.read("alpha", "1.0", "missing.bin") is None


def test_files_for_different_projects_and_versions_stay_isolated(tmp_path):
    store = FilesystemFileStore(tmp_path)
    store.store("alpha", "1.0", "app.bin", _stream(bytes([1])))
    store.store("beta", "1.0", "app.bin", _stream(bytes([2])))
    store.store("alpha", "2.0", "app.bin", _stream(bytes([3])))
    assert store.read("alpha", "1.0", "app.bin") == bytes([1])
    assert store.read("beta", "1.0", "app.bin") == bytes([2])
    assert store.read("alpha", "2.0", "app.bin") == bytes([3])
    assert store.list("alpha", "1.0") == ["app.bin"]


def test_two_stores_with_different_roots_do_not_share_files(tmp_path):
    store_a = FilesystemFileStore(tmp_path / "a")
    store_b = FilesystemFileStore(tmp_path / "b")
    store_a.store("alpha", "1.0", "app.bin", _stream(bytes([9])))
    assert store_b.read("alpha", "1.0", "app.bin") is None


def test_nested_relative_paths_are_stored_and_listed(tmp_path):
    store = FilesystemFileStore(tmp_path)
    store.store("alpha", "1.0", "lib/util.bin", _stream(bytes([4])))
    store.store("alpha", "1.0", "app.bin", _stream(bytes([5])))
    assert store.read("alpha", "1.0", "lib/util.bin") == bytes([4])
    assert sorted(store.list("alpha", "1.0")) == ["app.bin", "lib/util.bin"]


def test_path_traversal_in_a_file_name_is_rejected(tmp_path):
    store = FilesystemFileStore(tmp_path)
    assert store.store("alpha", "1.0", "../escape.bin", _stream(bytes([1]))) is False
    assert store.read("alpha", "1.0", "../escape.bin") is None


def test_deleting_a_version_removes_all_of_its_files(tmp_path):
    store = FilesystemFileStore(tmp_path)
    store.store("alpha", "1.0", "app.bin", _stream(bytes([1])))
    store.store("alpha", "1.0", "lib/util.bin", _stream(bytes([2])))
    store.store("alpha", "2.0", "app.bin", _stream(bytes([3])))
    store.delete_version("alpha", "1.0")
    assert store.read("alpha", "1.0", "app.bin") is None
    assert store.read("alpha", "1.0", "lib/util.bin") is None
    assert store.list("alpha", "1.0") == []
    assert store.read("alpha", "2.0", "app.bin") == bytes([3])


def _stream(payload: bytes):
    from io import BytesIO

    return BytesIO(payload)
