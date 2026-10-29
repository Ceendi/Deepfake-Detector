import hashlib
import importlib.util
import io
import tomllib
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


def test_archive_without_detection_model_installs_nothing(tmp_path):
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, "w") as zipped:
        zipped.writestr("unused_recognition.onnx", b"unused")
    data = stream.getvalue()
    model = {"url": "https://example.test/buffalo_l.zip",
             "sha256": hashlib.sha256(data).hexdigest()}
    with pytest.raises(KeyError, match="det_10g.onnx"):
        installer().install(model, tmp_path, lambda *args, **kwargs: io.BytesIO(data))
    assert not list(tmp_path.rglob("*.onnx"))


def test_main_installs_from_manifest_into_root(tmp_path):
    data = archive()
    manifest = tmp_path / "face-model.toml"
    manifest.write_text('url = "https://example.test/buffalo_l.zip"\n'
                        f'sha256 = "{hashlib.sha256(data).hexdigest()}"\n')
    fetch = MagicMock(return_value=io.BytesIO(data))
    installer().main(manifest, tmp_path / "insightface", fetch)
    fetch.assert_called_once_with("https://example.test/buffalo_l.zip", timeout=30)
    installed = tmp_path / "insightface/models/buffalo_l/det_10g.onnx"
    assert installed.read_bytes() == b"controlled detection model"


def test_defaults_match_the_image_layout():
    module = installer()
    assert module.MANIFEST == Path(module.__file__).with_name("face-model.toml")
    assert module.ROOT == Path("/app/.insightface")
    with module.MANIFEST.open("rb") as stream:
        manifest = tomllib.load(stream)
    assert manifest["url"].startswith("https://github.com/deepinsight/insightface/releases/")
    assert len(manifest["sha256"]) == 64
