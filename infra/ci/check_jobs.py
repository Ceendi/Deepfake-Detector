"""Summarize every prerequisite; intentional skips pass, failures and cancellations block."""

import json
import os
import sys


def main():
    jobs = json.load(sys.stdin)
    lines = [f"- {name}: {job['result']}" for name, job in jobs.items()]
    if os.environ.get('FORK_PR') == 'true':
        lines.append('SonarCloud is unavailable on fork PRs (no secret); every other gate still applies. '
                     'Maintainers must run Sonar on an in-repository branch before merging.')
    summary = '\n'.join(lines) + '\n'
    print(summary)
    if path := os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(path, 'a', encoding='utf-8') as output:
            output.write(summary)
    return int(not jobs or any(job['result'] not in ('success', 'skipped')
                               for job in jobs.values()))


if __name__ == '__main__':
    sys.exit(main())
