import os
import time
from contextlib import contextmanager
from pathlib import Path

import pytest


def test_startup_cleanup_reclaims_stale_attempt_but_preserves_live_and_other_files(tmp_path,
                                                                                monkeypatch):
    from detector_runtime import attempts

    monkeypatch.setattr(attempts.tempfile, "gettempdir", lambda: str(tmp_path))
    stale = tmp_path / "video-attempt-stale"
    stale.mkdir()
    (stale / "input").write_bytes(b"abandoned upload")
    unrelated = tmp_path / "audio-attempt-stale"
    unrelated.mkdir()
    target = tmp_path / "unrelated"
    target.mkdir()
    with attempts.attempt_directory("video") as live:
        (Path(live) / "input").write_bytes(b"in use")
        cutoff = time.time() + 1
        attempts.cleanup_attempts("video", cutoff)
        assert not stale.exists()
        assert (Path(live) / "input").read_bytes() == b"in use"
        assert unrelated.exists() and target.exists()
    assert not Path(live).exists()


def test_cleanup_preserves_directories_newer_than_startup(tmp_path, monkeypatch):
    from detector_runtime import attempts

    monkeypatch.setattr(attempts.tempfile, "gettempdir", lambda: str(tmp_path))
    recent = tmp_path / "video-attempt-recent"
    recent.mkdir()
    os.utime(recent, (200, 200))
    attempts.cleanup_attempts("video", 100)
    assert recent.exists()


def test_cleanup_skips_leased_directories_and_plain_files(tmp_path, monkeypatch):
    from detector_runtime import attempts

    monkeypatch.setattr(attempts.tempfile, "gettempdir", lambda: str(tmp_path))
    leased = tmp_path / "video-attempt-leased"
    leased.mkdir()
    (leased / "input").write_bytes(b"still owned")
    regular = tmp_path / "video-attempt-file"
    regular.write_bytes(b"not a directory")
    # A second descriptor cannot take the lease while another holder keeps it.
    with attempts._lease(leased) as held:
        assert held
        attempts.cleanup_attempts("video", time.time() + 1)
    assert (leased / "input").read_bytes() == b"still owned"
    assert regular.read_bytes() == b"not a directory"


def test_cleanup_tolerates_directory_reclaimed_concurrently(tmp_path, monkeypatch):
    from detector_runtime import attempts

    monkeypatch.setattr(attempts.tempfile, "gettempdir", lambda: str(tmp_path))
    (tmp_path / "video-attempt-first").mkdir()
    (tmp_path / "video-attempt-second").mkdir()
    removed = []

    def rmtree(directory):
        removed.append(Path(directory).name)
        raise FileNotFoundError(directory)

    monkeypatch.setattr(attempts.shutil, "rmtree", rmtree)
    attempts.cleanup_attempts("video", time.time() + 1)
    assert sorted(removed) == ["video-attempt-first", "video-attempt-second"]


def test_attempt_directory_refuses_unleased_directory(tmp_path, monkeypatch):
    from detector_runtime import attempts

    @contextmanager
    def unavailable(directory):
        yield False

    monkeypatch.setattr(attempts.tempfile, "tempdir", str(tmp_path))
    monkeypatch.setattr(attempts, "_lease", unavailable)
    with (pytest.raises(RuntimeError, match="attempt directory"),
          attempts.attempt_directory("video")):
        pytest.fail("attempt must not start without its lease")
    assert not list(tmp_path.iterdir())
