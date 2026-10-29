"""Reclaim abandoned inputs without removing another worker's live attempt."""

import os
import shutil
import tempfile
from contextlib import contextmanager
from pathlib import Path


@contextmanager
def _lease(directory):
    with (directory / ".lease").open("a+b") as lease:
        try:
            if os.name == "nt":
                import msvcrt

                lease.write(b"0")
                lease.flush()
                lease.seek(0)
                msvcrt.locking(lease.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl

                fcntl.flock(lease.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            yield False
        else:
            # Closing the descriptor releases the lease, including after a process kill.
            yield True


@contextmanager
def attempt_directory(source):
    with (tempfile.TemporaryDirectory(prefix=f"{source}-attempt-") as directory,
          _lease(Path(directory)) as acquired):
        if not acquired:
            raise RuntimeError("Cannot acquire private attempt directory")
        yield directory


def cleanup_attempts(source, started_at):
    for directory in Path(tempfile.gettempdir()).glob(f"{source}-attempt-*"):
        try:
            if directory.is_symlink() or not directory.is_dir():
                continue
            if directory.stat().st_mtime >= started_at:
                continue
            with _lease(directory) as acquired:
                if not acquired:
                    continue
            shutil.rmtree(directory)
        except FileNotFoundError:
            # Another restarting process can reclaim the same abandoned directory.
            continue
