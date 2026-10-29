"""Summarize Trivy JSON and reject reported critical vulnerabilities."""

import argparse
from datetime import datetime, timezone
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


def expiry(risk):
    timestamp = datetime.fromisoformat(risk['expires_at'])
    if timestamp.tzinfo is None:
        raise ValueError('Accepted security risks require a timezone-aware expiry')
    return timestamp


def matching_risk(report, target, vulnerability, risks):
    for risk in risks:
        if (report['ArtifactName'] == risk['image']
                and risk['digest'] in (report.get('Metadata', {}).get('RepoDigests') or [])
                and target in risk['targets']
                and vulnerability['VulnerabilityID'] == risk['vulnerability']
                and vulnerability['PkgName'] == risk['package']
                and vulnerability['InstalledVersion'] == risk['installed_version']):
            return risk
    return None


def main(arguments=None, *, now=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('report')
    parser.add_argument('--accepted-risks', type=Path)
    args = parser.parse_args(arguments)
    report = json.loads(Path(args.report).read_text())
    risks = json.loads(args.accepted_risks.read_text()) if args.accepted_risks else []
    for risk in risks:
        expiry(risk)
    now = now or datetime.now(timezone.utc)
    rows = findings(report)
    critical = sum(v['Severity'] == 'CRITICAL' for _, v in rows)
    unfixed = sum(not v.get('FixedVersion') for _, v in rows)
    accepted = 0
    decisions = []
    warnings = set()
    for target, vulnerability in rows:
        risk = matching_risk(report, target, vulnerability, risks)
        status = 'Report only'
        if vulnerability['Severity'] == 'CRITICAL':
            status = 'BLOCKING'
            if risk and now < expiry(risk):
                accepted += 1
                status = 'Accepted until ' + risk['expires_at']
                warnings.add(f"Accepted security risk {risk['vulnerability']} in {risk['image']}; "
                             f"expires {risk['expires_at']}. {risk['reason']}")
            elif risk:
                status = 'BLOCKING: acceptance expired ' + risk['expires_at']
        decisions.append((vulnerability, status))
    lines = [
        f'### {report["ArtifactName"]}',
        f'CRITICAL: {critical}; HIGH: {len(rows) - critical}; without a listed fix: {unfixed}.',
        f'Accepted CRITICAL: {accepted}; blocking CRITICAL: {critical - accepted}.',
        '', '| Severity | Advisory | Package | Installed | Fixed | Gate |',
        '| --- | --- | --- | --- | --- | --- |',
    ]
    for vulnerability, status in decisions:
        values = [vulnerability[name] for name in ('Severity', 'VulnerabilityID', 'PkgName', 'InstalledVersion')]
        values.append(vulnerability.get('FixedVersion') or 'No vendor fix listed')
        values.append(status)
        lines.append('| ' + ' | '.join(str(value).replace('|', '\\|') for value in values) + ' |')
    summary = '\n'.join(lines) + '\n'
    print(summary)
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as stream:
            stream.write(summary)
    for warning in sorted(warnings):
        print('::warning::' + warning.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A'))
    return 1 if critical > accepted else 0


if __name__ == '__main__':
    sys.exit(main())
