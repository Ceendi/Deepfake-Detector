import hashlib
import os

import pytest

from detector_runtime.model_files import ModelFilesError, ensure_model_files, load_manifest

REVISION = "a" * 40
WEIGHTS = {"model.onnx": b"onnx-bytes", "sub/model.ckpt": b"ckpt-bytes"}


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def write_manifest(tmp_path, *, revision=REVISION, files=None):
    files = files if files is not None else {name: digest(data) for name, data in WEIGHTS.items()}
    lines = ['repo = "owner/model"', f'revision = "{revision}"', "", "[files]"]
    lines += [f'"{name}" = "{value}"' for name, value in files.items()]
    path = tmp_path / "models.toml"
    path.write_text("\n".join(lines) + "\n")
    return str(path)


class FakeHub:
    def __init__(self, content=WEIGHTS, failures=0):
        self.content, self.failures, self.calls = content, failures, []

    def __call__(self, *, repo_id, filename, revision, local_dir):
        self.calls.append((repo_id, filename, revision))
        if self.failures:
            self.failures -= 1
            raise ConnectionError("hub unreachable")
        path = os.path.join(local_dir, *filename.split("/"))
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "wb") as file:
            file.write(self.content[filename])
        return path


def test_downloads_missing_files_from_the_pinned_commit(tmp_path):
    hub, model_dir = FakeHub(), tmp_path / "models"

    ensure_model_files(write_manifest(tmp_path), str(model_dir), download=hub)

    assert sorted(hub.calls) == [("owner/model", "model.onnx", REVISION),
                                 ("owner/model", "sub/model.ckpt", REVISION)]
    assert (model_dir / "model.onnx").read_bytes() == b"onnx-bytes"
    assert (model_dir / "sub" / "model.ckpt").read_bytes() == b"ckpt-bytes"
    assert not (model_dir / ".download").exists()


def test_verified_files_start_without_network(tmp_path):
    manifest, model_dir = write_manifest(tmp_path), tmp_path / "models"
    ensure_model_files(manifest, str(model_dir), download=FakeHub())
    hub = FakeHub(failures=99)

    ensure_model_files(manifest, str(model_dir), download=hub, sleep=lambda _: None)

    assert hub.calls == []


def test_altered_file_is_downloaded_again(tmp_path):
    manifest, model_dir = write_manifest(tmp_path), tmp_path / "models"
    ensure_model_files(manifest, str(model_dir), download=FakeHub())
    (model_dir / "model.onnx").write_bytes(b"truncated")
    hub = FakeHub()

    ensure_model_files(manifest, str(model_dir), download=hub)

    assert [call[1] for call in hub.calls] == ["model.onnx"]
    assert (model_dir / "model.onnx").read_bytes() == b"onnx-bytes"


def test_checksum_mismatch_never_replaces_the_model(tmp_path):
    model_dir = tmp_path / "models"
    hub = FakeHub(content={**WEIGHTS, "model.onnx": b"tampered"})

    with pytest.raises(ModelFilesError, match="sha256"):
        ensure_model_files(write_manifest(tmp_path), str(model_dir), download=hub)

    assert not (model_dir / "model.onnx").exists()


def test_transient_failures_are_retried(tmp_path):
    delays = []

    ensure_model_files(write_manifest(tmp_path), str(tmp_path / "models"),
                       download=FakeHub(failures=2), sleep=delays.append, offline=False)

    assert delays == [5, 15]


def test_gives_up_after_the_last_retry(tmp_path):
    delays = []

    with pytest.raises(ModelFilesError, match="Could not download"):
        ensure_model_files(write_manifest(tmp_path), str(tmp_path / "models"),
                           download=FakeHub(failures=99), sleep=delays.append, offline=False)

    assert delays == [5, 15, 30]


def test_offline_mode_fails_at_once(tmp_path):
    delays, hub = [], FakeHub(failures=99)

    with pytest.raises(ModelFilesError, match="Could not download"):
        ensure_model_files(write_manifest(tmp_path), str(tmp_path / "models"),
                           download=hub, sleep=delays.append, offline=True)

    assert delays == [] and len(hub.calls) == 1


@pytest.mark.parametrize("revision", ["main", "v1.0", "A" * 40, "a" * 39])
def test_revision_must_be_a_commit(tmp_path, revision):
    with pytest.raises(ModelFilesError, match="commit"):
        load_manifest(write_manifest(tmp_path, revision=revision))


@pytest.mark.parametrize("name", ["../escape.onnx", "/abs.onnx", "a/./b.onnx", "a\\\\b.onnx"])
def test_file_names_stay_inside_the_model_dir(tmp_path, name):
    with pytest.raises(ModelFilesError, match="invalid file name"):
        load_manifest(write_manifest(tmp_path, files={name: "0" * 64}))


def test_every_file_needs_a_checksum(tmp_path):
    with pytest.raises(ModelFilesError, match="sha256"):
        load_manifest(write_manifest(tmp_path, files={"model.onnx": "latest"}))
