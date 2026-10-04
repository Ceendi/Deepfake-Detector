"""Summarize Trivy JSON and reject reported critical vulnerabilities."""

import json
import os
import sys
from pathlib import Path


def findings(report):
    return [
        (result['Target'], vulnerability)
        for result in report.get('Results', [])
        for vulnerability in result.get('Vulnerabilities') or []
        if vulnerability['Severity'] in ('HIGH', 'CRITICAL')
    ]


def main():
    report = json.loads(Path(sys.argv[1]).read_text())
    rows = findings(report)
    critical = sum(v['Severity'] == 'CRITICAL' for _, v in rows)
    unfixed = sum(not v.get('FixedVersion') for _, v in rows)
    lines = [
        f'### {report["ArtifactName"]}',
        f'CRITICAL: {critical}; HIGH: {len(rows) - critical}; without a listed fix: {unfixed}.',
        '', '| Severity | Advisory | Package | Installed | Fixed |',
        '| --- | --- | --- | --- | --- |',
    ]
    for _, vulnerability in rows:
        values = [vulnerability[name] for name in ('Severity', 'VulnerabilityID', 'PkgName', 'InstalledVersion')]
        values.append(vulnerability.get('FixedVersion') or 'No vendor fix listed')
        lines.append('| ' + ' | '.join(str(value).replace('|', '\\|') for value in values) + ' |')
    summary = '\n'.join(lines) + '\n'
    print(summary)
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as stream:
            stream.write(summary)
    return 1 if critical else 0


if __name__ == '__main__':
    sys.exit(main())
