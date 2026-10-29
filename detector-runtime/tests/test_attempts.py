import os
import time
from pathlib import Path


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
