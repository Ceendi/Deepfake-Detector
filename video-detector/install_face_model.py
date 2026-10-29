"""Build-time installation of the checksum-pinned RetinaFace detection model."""

import hashlib
import shutil
import sys
import tempfile
import tomllib
import urllib.request
import zipfile
from pathlib import Path


def install(model, root, fetch=urllib.request.urlopen):
    with tempfile.TemporaryFile() as archive:
        with fetch(model["url"], timeout=30) as response:
            shutil.copyfileobj(response, archive)
        archive.seek(0)
        if hashlib.file_digest(archive, "sha256").hexdigest() != model["sha256"]:
            raise ValueError("Face model checksum mismatch")
        archive.seek(0)
        with zipfile.ZipFile(archive) as zipped:
            target = Path(root) / "models/buffalo_l/det_10g.onnx"
            target.parent.mkdir(parents=True, exist_ok=True)
            with zipped.open("det_10g.onnx") as source, target.open("wb") as destination:
                shutil.copyfileobj(source, destination)


if __name__ == "__main__":
    with open(sys.argv[1], "rb") as manifest:
        install(tomllib.load(manifest), sys.argv[2])
