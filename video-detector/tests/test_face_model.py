import hashlib
import importlib.util
import io
import zipfile
from pathlib import Path
from unittest.mock import MagicMock

import pytest


def installer():
    spec = importlib.util.spec_from_file_location(
        "install_face_model", Path(__file__).parents[1] / "install_face_model.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def archive():
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, "w") as zipped:
        zipped.writestr("det_10g.onnx", b"controlled detection model")
        zipped.writestr("unused_recognition.onnx", b"unused")
    return stream.getvalue()


def test_verified_archive_installs_only_detection_model(tmp_path):
    data = archive()
    model = {"url": "https://example.test/buffalo_l.zip",
             "sha256": hashlib.sha256(data).hexdigest()}
    fetch = MagicMock(return_value=io.BytesIO(data))
    installer().install(model, tmp_path, fetch)
    assert [p.name for p in tmp_path.rglob("*.onnx")] == ["det_10g.onnx"]
    assert not list(tmp_path.rglob("*.zip"))
    assert (tmp_path / "models/buffalo_l/det_10g.onnx").read_bytes() == b"controlled detection model"


def test_mismatch_never_installs_weights(tmp_path):
    model = {"url": "https://example.test/buffalo_l.zip", "sha256": "0" * 64}
    with pytest.raises(ValueError, match="checksum"):
        installer().install(model, tmp_path, lambda *args, **kwargs: io.BytesIO(archive()))
    assert not list(tmp_path.rglob("*.onnx"))
