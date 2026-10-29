"""Select CI-verified source revisions and promote a complete, immutable image set."""

import json
import os
import re
import subprocess
import sys
from pathlib import Path

SERVICES = ('gateway', 'eureka-server', 'orchestrator', 'file-service', 'video-detector',
            'audio-detector', 'keycloak', 'frontend', 'postgres', 'keycloak-config-cli', 'alloy')


def command(*args):
    return subprocess.check_output(args, text=True).strip()


def green_sha(runs):
    for run in runs:
        if (run['conclusion'] == 'success' and run['event'] == 'push'
                and run['headBranch'] == 'main' and re.fullmatch('[0-9a-f]{40}', run['headSha'])):
            return run['headSha']
    raise ValueError('No successful CI push run on main; refusing to publish')


def run_tag(sha, number, attempt):
    return f'sha-{sha[:7]}-r{number}-a{attempt}'


def is_current_main(sha, tip):
    return sha == tip


def manifest(reference):
    result = subprocess.run(['docker', 'buildx', 'imagetools', 'inspect', '--raw', reference],
                            text=True, capture_output=True, check=False)
    if not result.returncode:
        return json.loads(result.stdout)
    # Authentication, rate limits and network errors must never mean "tag absent".
    if re.search(r'manifest unknown|ghcr\.io/\S+: not found(?:\s|$)',
                 result.stderr, re.IGNORECASE):
        return None
    raise RuntimeError(result.stderr)


def select():
    repository = os.environ['GITHUB_REPOSITORY']
    event_name = os.environ['GITHUB_EVENT_NAME']
    event = json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_text())
    if event_name == 'pull_request':
        sha = os.environ['GITHUB_SHA']
    elif event_name == 'workflow_run':
        run = event['workflow_run']
        if run['head_repository']['full_name'] != repository:
            raise ValueError('CI must belong to this repository')
        sha = green_sha([{'headSha': run['head_sha'], 'headBranch': run['head_branch'],
                          'event': run['event'], 'conclusion': run['conclusion']}])
    else:
        runs = json.loads(command('gh', 'run', 'list', '--repo', repository, '--workflow', 'ci.yml',
                                  '--branch', 'main', '--event', 'push', '--status', 'success',
                                  '--limit', '1', '--json', 'headSha,headBranch,event,conclusion'))
        sha = green_sha(runs)
    rebuild = event_name == 'schedule' or os.environ.get('NO_CACHE') == 'true'
    tag = f'sha-{sha[:7]}'
    if event_name != 'pull_request' and (rebuild or os.environ['GITHUB_RUN_ATTEMPT'] != '1'
            or any(manifest(f'ghcr.io/{repository.lower()}/{service}:{tag}') is not None
                   for service in SERVICES)):
        tag = run_tag(sha, os.environ['GITHUB_RUN_NUMBER'], os.environ['GITHUB_RUN_ATTEMPT'])
    with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as output:
        output.write(f'sha={sha}\ntag={tag}\nrebuild={str(rebuild).lower()}\n')


def promote():
    repository, sha, tag = (os.environ[key] for key in ('GITHUB_REPOSITORY', 'SHA', 'TAG'))
    for service in SERVICES:
        tip = command('gh', 'api', f'repos/{repository}/commits/main', '--jq', '.sha')
        if not is_current_main(sha, tip):
            print(f'latest unchanged: {sha} is not current main ({tip})')
            return
        image = f'ghcr.io/{repository.lower()}/{service}'
        command('docker', 'buildx', 'imagetools', 'create', '--prefer-index=false',
                '--tag', f'{image}:latest', f'{image}:{tag}')


def push_image():
    image = (f"ghcr.io/{os.environ['GITHUB_REPOSITORY'].lower()}/"
             f"{os.environ['SERVICE']}:{os.environ['TAG']}")
    existing = manifest(image)
    if existing is not None:
        local = command('docker', 'image', 'inspect', image, '--format', '{{.Id}}')
        if existing.get('config', {}).get('digest') != local:
            raise ValueError(f'Refusing to overwrite immutable tag {image}')
        print(f'{image} already contains this scanned image')
        return
    command('docker', 'push', image)


if __name__ == '__main__':
    {'select': select, 'promote': promote, 'push': push_image}[sys.argv[1]]()
