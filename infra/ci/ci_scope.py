"""Choose the CI jobs a pull request needs from its changed files (one path per stdin line).

A path that no rule knows runs everything: a missing rule may cost CI minutes, never a skipped
test. Pushes to main always run everything (see ci.yml). Prints GitHub step outputs.
"""

import json
import re
import sys

JAVA = ("gateway", "eureka-server", "orchestrator", "file-service")
EVERYTHING = {"frontend", "detectors", *(f"java:{service}" for service in JAVA)}

# First match wins. Every cross-directory read in the test suites needs a rule here.
RULES = [
    # Documentation is not read by any job in ci.yml.
    (re.compile(r"^docs/|^[^/]+\.md$"), set()),
    # Standalone stdlib tools: the python job, which always runs, lints and tests them.
    (re.compile(r"^tools/"), set()),
    # Shared wire fixtures: the audio detector and frontend tests load them too.
    (re.compile(r"^orchestrator/src/test/resources/contracts/"),
     {"java:orchestrator", "detectors", "frontend"}),
    (re.compile(r"^frontend/"), {"frontend"}),
    (re.compile(r"^(gateway|eureka-server|orchestrator|file-service)/"), None),
    # The orchestrator integration tests start the real detector consumers from these paths.
    (re.compile(r"^(audio-detector|video-detector|detector-runtime|tests/worker_redelivery)/"),
     {"detectors", "java:orchestrator"}),
]


def scope(paths):
    selected = set()
    for path in paths:
        for pattern, jobs in RULES:
            match = pattern.match(path)
            if match:
                selected |= {f"java:{match.group(1)}"} if jobs is None else jobs
                break
        else:
            return set(EVERYTHING)
    return selected


def outputs(selected):
    return {
        "java": json.dumps([service for service in JAVA if f"java:{service}" in selected]),
        "frontend": str("frontend" in selected).lower(),
        "detectors": str("detectors" in selected).lower(),
    }


if __name__ == "__main__":
    files = [line.strip() for line in sys.stdin if line.strip()]
    # An empty list means the file list could not be read: run everything.
    for key, value in outputs(scope(files) if files else EVERYTHING).items():
        print(f"{key}={value}")
