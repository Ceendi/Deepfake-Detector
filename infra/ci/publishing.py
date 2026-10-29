"""Select CI-verified source revisions and promote a complete, immutable image set."""

import json
import os
import re
import subprocess
import sys
from pathlib import Path

SERVICES = ('gateway', 'eureka-server', 'orchestrator', 'file-service', 'video-detector',
            'audio-detector', 'keycloak', 'frontend', 'postgres', 'keycloak-config-cli', 'alloy')
# Event payloads, API output and environment values reach docker/gh argument lists; accept only
# the shapes GitHub and this workflow produce, so no value can turn into an option.
COMMIT = re.compile(r'[0-9a-f]{40}')
REPOSITORY = re.compile(r'[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9_.][A-Za-z0-9_.-]{0,99}')
TAG = re.compile(r'sha-[0-9a-f]{7}(?:-r[0-9]+-a[0-9]+)?')
IMAGE = re.compile(r'ghcr\.io/[a-z0-9._/-]+:[A-Za-z0-9._-]+')
COUNTER = re.compile(r'[1-9][0-9]{0,9}')


def checked(pattern, value, name):
    if not isinstance(value, str) or pattern.fullmatch(value) is None:
        raise ValueError(f'Refusing invalid {name}: {value!r}')
    return value


def commit(value):
    # Re-rendered from its integer value, so only hex digits can reach a command line.
    return f'{int(checked(COMMIT, value, "commit SHA"), 16):040x}'


def service_name(value):
    if value not in SERVICES:
        raise ValueError(f'Refusing unknown service: {value!r}')
    return SERVICES[SERVICES.index(value)]


def image(repository, service, tag, *, latest=False):
    name = f'ghcr.io/{checked(REPOSITORY, repository, "repository").lower()}/{service_name(service)}'
    tag = 'latest' if latest else checked(TAG, tag, 'tag')
    return checked(IMAGE, f'{name}:{tag}', 'image reference')


def command(*args):
    return subprocess.check_output(args, text=True).strip()


def green_sha(runs):
    for run in runs:
        if (run['conclusion'] == 'success' and run['event'] == 'push'
                and run['headBranch'] == 'main' and isinstance(run['headSha'], str)
                and COMMIT.fullmatch(run['headSha'])):
            return commit(run['headSha'])
    raise ValueError('No successful CI push run on main; refusing to publish')


def run_tag(sha, number, attempt):
    number, attempt = (int(checked(COUNTER, value, 'run counter')) for value in (number, attempt))
    return f'sha-{commit(sha)[:7]}-r{number}-a{attempt}'


def is_current_main(sha, tip):
    return sha == tip


def manifest(reference):
    reference = checked(IMAGE, reference, 'image reference')
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
    repository = checked(REPOSITORY, os.environ['GITHUB_REPOSITORY'], 'repository')
    event_name = os.environ['GITHUB_EVENT_NAME']
    event = json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_text())
    if event_name == 'pull_request':
        sha = commit(os.environ['GITHUB_SHA'])
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
            or any(manifest(image(repository, service, tag)) is not None
                   for service in SERVICES)):
        tag = run_tag(sha, os.environ['GITHUB_RUN_NUMBER'], os.environ['GITHUB_RUN_ATTEMPT'])
    with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as output:
        output.write(f'sha={sha}\ntag={tag}\nrebuild={str(rebuild).lower()}\n')


def promote():
    repository = checked(REPOSITORY, os.environ['GITHUB_REPOSITORY'], 'repository')
    sha, tag = commit(os.environ['SHA']), checked(TAG, os.environ['TAG'], 'tag')
    for service in SERVICES:
        tip = command('gh', 'api', f'repos/{repository}/commits/main', '--jq', '.sha')
        if not is_current_main(sha, tip):
            print(f'latest unchanged: {sha} is not current main ({tip})')
            return
        command('docker', 'buildx', 'imagetools', 'create', '--prefer-index=false',
                '--tag', image(repository, service, tag, latest=True),
                image(repository, service, tag))


def push_image():
    reference = image(os.environ['GITHUB_REPOSITORY'], os.environ['SERVICE'], os.environ['TAG'])
    existing = manifest(reference)
    if existing is not None:
        local = command('docker', 'image', 'inspect', reference, '--format', '{{.Id}}')
        if existing.get('config', {}).get('digest') != local:
            raise ValueError(f'Refusing to overwrite immutable tag {reference}')
        print(f'{reference} already contains this scanned image')
        return
    command('docker', 'push', reference)


if __name__ == '__main__':
    {'select': select, 'promote': promote, 'push': push_image}[sys.argv[1]]()
