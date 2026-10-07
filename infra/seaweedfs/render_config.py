"""Render credential string values as JSON, then publish on the shared volume."""

import argparse
import json
import os
from pathlib import Path
import sys
import tempfile

IDENTITIES = {
    "admin": "S3_ADMIN",
    "file-service": "S3_FILE_SERVICE",
    "orchestrator": "S3_ORCHESTRATOR",
    "detector": "S3_DETECTOR",
}


class ConfigurationError(Exception):
    """A safe error message that never includes credential values."""


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ConfigurationError("duplicate JSON field in the S3 template")
        result[key] = value
    return result


def load_json(text):
    def invalid_constant(_value):
        raise ConfigurationError("invalid JSON constant in the S3 template")

    return json.loads(text, object_pairs_hook=unique_object, parse_constant=invalid_constant)


def render(template, environment):
    values = {}
    for prefix in IDENTITIES.values():
        for suffix in ("KEY", "SECRET"):
            name = f"{prefix}_{suffix}"
            value = environment.get(name)
            if not value:
                raise ConfigurationError(f"{name} must be set and non-empty")
            # Environment strings must be valid UTF-8 and usable by S3 clients.
            # Never trim, normalize Unicode, or interpret escape sequences.
            try:
                value.encode("utf-8", errors="strict")
            except UnicodeError:
                raise ConfigurationError(f"{name} must contain valid UTF-8") from None
            if any(ord(char) < 32 or ord(char) == 127 for char in value):
                raise ConfigurationError(f"{name} must not contain control characters")
            values[name] = value
    keys = [values[f"{prefix}_KEY"] for prefix in IDENTITIES.values()]
    if len(set(keys)) != len(keys):
        raise ConfigurationError("S3 access keys must be distinct across identities")

    document = load_json(template)
    if not isinstance(document, dict) or set(document) != {"identities"}:
        raise ConfigurationError("invalid S3 template structure")
    identities = document["identities"]
    if not isinstance(identities, list) or len(identities) != len(IDENTITIES):
        raise ConfigurationError("invalid S3 template identities")
    seen = set()
    for identity in identities:
        if not isinstance(identity, dict) or set(identity) != {"name", "credentials", "actions"}:
            raise ConfigurationError("invalid S3 template identity structure")
        name = identity["name"]
        if not isinstance(name, str) or name not in IDENTITIES or name in seen:
            raise ConfigurationError("invalid S3 template identity name")
        seen.add(name)
        actions = identity["actions"]
        if not isinstance(actions, list) or not actions or any(
            not isinstance(action, str) or not action for action in actions
        ):
            raise ConfigurationError("invalid S3 template actions")
        prefix = IDENTITIES[name]
        expected = {"accessKey": f"${{{prefix}_KEY}}", "secretKey": f"${{{prefix}_SECRET}}"}
        if identity["credentials"] != [expected]:
            raise ConfigurationError("invalid S3 template credential placeholders")
        # Only whole credential values are replaced; inserted values are never
        # scanned again, even when they contain another placeholder's spelling.
        identity["credentials"] = [{
            "accessKey": values[f"{prefix}_KEY"],
            "secretKey": values[f"{prefix}_SECRET"],
        }]
    return document


def publish(document, destination):
    temporary = None
    try:
        # Same directory/filesystem as the destination makes replace atomic.
        with tempfile.NamedTemporaryFile(
            mode="w+", encoding="utf-8", dir=destination.parent,
            prefix=".s3.json-", delete=False,
        ) as stream:
            temporary = Path(stream.name)
            json.dump(document, stream, ensure_ascii=False, allow_nan=False, indent=2)
            stream.write("\n")
            stream.flush()
            stream.seek(0)
            if load_json(stream.read()) != document:
                raise ConfigurationError("rendered S3 JSON failed validation")
            # Retain the existing downstream read permissions; incomplete files
            # stay private (0600) until they have been validated.
            os.fchmod(stream.fileno(), 0o644)
            os.fsync(stream.fileno())
        os.replace(temporary, destination)
        temporary = None
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description="Render the SeaweedFS S3 identity configuration")
    parser.add_argument("--template", type=Path, default=Path("/s3.json.tmpl"))
    parser.add_argument("--output", type=Path, default=Path("/out/s3.json"))
    args = parser.parse_args()
    try:
        template = args.template.read_text(encoding="utf-8")
        publish(render(template, os.environ), args.output)
    except ConfigurationError as error:
        print(f"ERROR: SeaweedFS S3 configuration: {error}", file=sys.stderr)
        return 1
    except (OSError, ValueError, TypeError, RecursionError):
        # Parser and OS exceptions may include template contents or file names.
        print("ERROR: SeaweedFS S3 configuration could not be rendered, validated or published", file=sys.stderr)
        return 1
    print("SeaweedFS S3 identity configuration validated and published")
    return 0


if __name__ == "__main__":
    sys.exit(main())
