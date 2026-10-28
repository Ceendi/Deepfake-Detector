"""Check the batch CSV of the real-model E2E run (.github/workflows/e2e-ml.yml).

Only outcomes are asserted, never which verdict the models chose:
  audio (WAV/MP3/FLAC)    COMPLETED, FAKE/REAL, audio probability in [0, 1], no video probability
  *no-face*.mp4           FAILED with [NO_FACE_DETECTED]
  other video             COMPLETED, FAKE/REAL, video and audio probability in [0, 1]

Usage: python3 infra/ci/e2e_ml_outcomes.py results.csv media/
"""

import csv
import sys
from pathlib import Path

AUDIO = {'.wav', '.mp3', '.flac'}
VIDEO = {'.mp4', '.mov', '.avi'}


def probability(value):
    try:
        number = float(value)
    except ValueError:
        return False
    return 0.0 <= number <= 1.0


def problems(rows, files):
    """Every problem with the run, as text; empty when the run is as expected."""
    found = []
    by_file = {}
    for row in rows:
        if row['plik'] in by_file:
            found.append(f'{row["plik"]}: more than one row')
        by_file[row['plik']] = row
    if set(by_file) != set(files):
        found.append(f'rows {sorted(by_file)} do not match files {sorted(files)}')
    for name in sorted(set(by_file) & set(files)):
        row = by_file[name]
        suffix = Path(name).suffix.lower()
        verdict = row['werdykt'] in ('FAKE', 'REAL')
        if suffix in AUDIO:
            ok = (row['status'] == 'COMPLETED' and verdict and probability(row['prob_audio'])
                  and row['prob_wideo'] == '')
        elif 'no-face' in Path(name).name:
            ok = row['status'] == 'FAILED' and row['blad'].startswith('[NO_FACE_DETECTED]')
        elif suffix in VIDEO:
            ok = (row['status'] == 'COMPLETED' and verdict and probability(row['prob_wideo'])
                  and probability(row['prob_audio']))
        else:
            ok = False
        if not ok:
            found.append(f'{name}: unexpected outcome {row}')
    return found


def media_files(root):
    return sorted(path.relative_to(root).as_posix() for path in Path(root).rglob('*')
                  if path.suffix.lower() in AUDIO | VIDEO)


def main(argv):
    results, root = argv
    with open(results, newline='', encoding='utf-8') as handle:
        rows = list(csv.DictReader(handle))
    files = media_files(root)
    found = problems(rows, files)
    for problem in found:
        print(f'::error::{problem}')
    print(f'{len(rows)} rows for {len(files)} files, {len(found)} problems')
    return 1 if found or not files else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
