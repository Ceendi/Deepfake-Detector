"""Submit N analyses at once and time how long the running detectors need to drain them.

Same stack and account as live_acceptance.py. See docs/reports/detector-scaling.md.

    python tests/live/detector_scaling.py AUDIO speech.flac 12 label [results.jsonl]
"""

import json
import statistics
import sys
import time
from pathlib import Path

from live_acceptance import TERMINAL, Api

MIME = {'.flac': 'audio/flac', '.wav': 'audio/wav', '.mp3': 'audio/mpeg', '.mp4': 'video/mp4'}


def main(kind, media, count, label, output=None):
    media = Path(media)
    api = Api()
    files = []
    for _ in range(count):
        status, body = api.upload(media, MIME[media.suffix.lower()])
        if status not in (200, 201):
            raise SystemExit(f'upload -> {status} {body}')
        files.append(body['fileId'])
    # Uploads finish first, so the timer covers only queueing and detector work.
    start = time.monotonic()
    ids = [api.create(file_id, kind) for file_id in files]
    finished = {}
    while len(finished) < count:
        for analysis_id in ids:
            if analysis_id not in finished:
                analysis = api.get(analysis_id)
                if analysis['status'] in TERMINAL:
                    finished[analysis_id] = (time.monotonic() - start, analysis['status'], analysis.get('verdict'))
        if time.monotonic() - start > 3600:
            raise SystemExit('analyses did not finish within an hour')
        time.sleep(1)
    times = sorted(seconds for seconds, _, _ in finished.values())
    result = {'label': label, 'kind': kind, 'n': count, 'makespan_s': round(times[-1], 1),
              'median_finish_s': round(statistics.median(times), 1),
              'per_hour': round(count / times[-1] * 3600),
              'outcomes': sorted({(status, verdict) for _, status, verdict in finished.values()})}
    print(json.dumps(result))
    if output:
        with open(output, 'a', encoding='utf-8') as stream:
            stream.write(json.dumps(result) + '\n')


if __name__ == '__main__':
    if len(sys.argv) not in (5, 6):
        raise SystemExit(__doc__)
    main(sys.argv[1], sys.argv[2], int(sys.argv[3]), *sys.argv[4:])
