"""Make the repository's detector runtime importable for the running interpreter.

pip cannot verify a hash for a source directory, so CI installs the hashed third-party pins with
--require-hashes and links the local package with a path file, as an editable install would.
"""

import sysconfig
from pathlib import Path

RUNTIME = Path(__file__).resolve().parents[2] / 'detector-runtime'


def link(site_packages):
    if not (RUNTIME / 'detector_runtime' / '__init__.py').is_file():
        raise FileNotFoundError(f'{RUNTIME} does not contain the detector_runtime package')
    target = Path(site_packages) / 'deepfake-detector-runtime.pth'
    target.write_text(f'{RUNTIME}\n', encoding='utf-8')
    return target


if __name__ == '__main__':
    print(f'linked {link(sysconfig.get_path("purelib"))}')
