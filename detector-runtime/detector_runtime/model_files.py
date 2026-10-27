"""Model weights pinned to a Hugging Face Hub commit by each detector's models.toml."""

import hashlib
import os
import re
import shutil
import time
import tomllib

import structlog
from huggingface_hub import hf_hub_download

# Transient network failures at container start must not leave the detector without a model.
RETRY_DELAYS_SECONDS = (5, 15, 30)
_COMMIT = re.compile(r"[0-9a-f]{40}")
_SHA256 = re.compile(r"[0-9a-f]{64}")
_STAGING = ".download"

log = structlog.get_logger(__name__)


class ModelFilesError(RuntimeError):
    pass


def load_manifest(path: str) -> dict:
    with open(path, "rb") as file:
        manifest = tomllib.load(file)
    repo, revision, files = manifest.get("repo"), manifest.get("revision"), manifest.get("files")
    if not isinstance(repo, str) or not repo:
        raise ModelFilesError("models.toml: repo must be set")
    # A branch or tag can move; only a commit pins the weights to this code.
    if not isinstance(revision, str) or not _COMMIT.fullmatch(revision):
        raise ModelFilesError("models.toml: revision must be a full commit hash")
    if not isinstance(files, dict) or not files:
        raise ModelFilesError("models.toml: [files] must list at least one file")
    for name, digest in files.items():
        parts = name.split("/")
        if name.startswith("/") or "\\" in name or any(part in ("", ".", "..") for part in parts):
            raise ModelFilesError(f"models.toml: invalid file name {name!r}")
        if not isinstance(digest, str) or not _SHA256.fullmatch(digest):
            raise ModelFilesError(f"models.toml: {name} needs a lowercase sha256")
    return manifest


def sha256(path: str) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as file:
        while chunk := file.read(8 * 1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def ensure_model_files(manifest_path: str, model_dir: str, *, download=hf_hub_download,
                       sleep=time.sleep) -> dict:
    """Download missing or altered files; files that already match their checksum stay offline."""
    manifest = load_manifest(manifest_path)
    repo, revision = manifest["repo"], manifest["revision"]
    staging = os.path.join(model_dir, _STAGING)
    for name, expected in manifest["files"].items():
        target = os.path.join(model_dir, *name.split("/"))
        if os.path.isfile(target) and sha256(target) == expected:
            continue
        log.info("model_file_download", repo=repo, revision=revision, file=name)
        for attempt, delay in enumerate((*RETRY_DELAYS_SECONDS, None), start=1):
            try:
                path = download(repo_id=repo, filename=name, revision=revision, local_dir=staging)
                break
            except Exception as error:
                if delay is None:
                    log.error("model_file_download_failed", file=name, attempts=attempt,
                              error_type=type(error).__name__)
                    raise ModelFilesError(f"Could not download {name}") from error
                log.warning("model_file_download_retry", file=name, attempt=attempt,
                            delay_seconds=delay, error_type=type(error).__name__)
                sleep(delay)
        if sha256(path) != expected:
            os.remove(path)
            log.error("model_file_checksum_mismatch", repo=repo, revision=revision, file=name)
            raise ModelFilesError(f"{name} does not match its sha256 in models.toml")
        os.makedirs(os.path.dirname(target), exist_ok=True)
        os.replace(path, target)
    shutil.rmtree(staging, ignore_errors=True)
    log.info("model_files_ready", repo=repo, revision=revision, files=len(manifest["files"]))
    return manifest
