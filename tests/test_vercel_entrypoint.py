from pathlib import Path

from fastapi import FastAPI

from versionserver.main import build_app, path_from_env


def test_unset_paths_stay_in_the_working_tree(monkeypatch):
    monkeypatch.delenv("VERCEL", raising=False)
    monkeypatch.delenv("VERSION_SERVER_STORAGE", raising=False)
    assert path_from_env("VERSION_SERVER_STORAGE", "data/files") == Path("data/files")


def test_vercel_defaults_storage_under_tmp(monkeypatch):
    monkeypatch.setenv("VERCEL", "1")
    monkeypatch.delenv("VERSION_SERVER_JSON", raising=False)
    assert path_from_env("VERSION_SERVER_JSON", "data/store.json") == Path(
        "/tmp/version-server/data/store.json"
    )


def test_explicit_path_wins_on_vercel(monkeypatch, tmp_path):
    monkeypatch.setenv("VERCEL", "1")
    monkeypatch.setenv("VERSION_SERVER_STORAGE", str(tmp_path / "files"))
    assert path_from_env("VERSION_SERVER_STORAGE", "data/files") == tmp_path / "files"


def test_asgi_entrypoint_exports_fastapi(monkeypatch, tmp_path):
    monkeypatch.setenv("VERSION_SERVER_STORAGE", str(tmp_path / "files"))
    monkeypatch.setenv("VERSION_SERVER_JSON", str(tmp_path / "store.json"))
    monkeypatch.delenv("VERSION_SERVER_JWT_CERT", raising=False)
    monkeypatch.delenv("VERSION_SERVER_JWT_KEY", raising=False)
    monkeypatch.delenv("VERSION_SERVER_JWT_KEY_PASSWORD", raising=False)
    import importlib

    import app as entry

    importlib.reload(entry)
    assert isinstance(entry.app, FastAPI)
    assert isinstance(build_app(), FastAPI)
