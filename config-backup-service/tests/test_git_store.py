"""CRUD-04/RN-CB3: versionado en Git y diff entre versiones (RF-07)."""

from __future__ import annotations

from pathlib import Path

from app.git_store import GitStore


def test_save_and_diff_between_versions(tmp_path: Path) -> None:
    store = GitStore(str(tmp_path / "repo"))
    device = "device-1"
    c1 = store.save_config(device, "line1\nline2\n", "line1\nline2\n", "v1")
    c2 = store.save_config(device, "line1\nCHANGED\n", "line1\nline2\n", "v2")

    diff = store.diff(device, c1, c2, "running")

    assert c1 != c2
    assert "CHANGED" in diff


def test_reopens_existing_repository(tmp_path: Path) -> None:
    path = str(tmp_path / "repo")
    GitStore(path).save_config("d", "a\n", "a\n", "v1")

    commit = GitStore(path).save_config("d", "b\n", "b\n", "v2")

    assert commit
