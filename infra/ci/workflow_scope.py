"""Path selection within always-triggered workflows; an unreadable list runs everything."""

import fnmatch
import json
import sys
from pathlib import Path


def selected(paths, patterns):
    return not paths or any(fnmatch.fnmatchcase(path, pattern)
                            for path in paths for pattern in patterns)


if __name__ == '__main__':
    patterns = json.loads(Path(__file__).with_name('workflow_paths.json').read_text())
    paths = [line.strip() for line in sys.stdin if line.strip()]
    print('run=' + str(selected(paths, patterns[sys.argv[1]])).lower())
