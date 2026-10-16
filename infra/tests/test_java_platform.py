"""Guard the compatible framework generations across independently built services."""

import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
NS = {'m': 'http://maven.apache.org/POM/4.0.0'}
SERVICES = ('gateway', 'eureka-server', 'orchestrator', 'file-service')


def pom(service):
    return ET.parse(ROOT / service / 'pom.xml').getroot()


class JavaPlatformTest(unittest.TestCase):
    def test_boot_generation_is_consistent(self):
        versions = {service: pom(service).findtext('m:parent/m:version', namespaces=NS)
                    for service in SERVICES}
        self.assertEqual(1, len(set(versions.values())), versions)
        for service, version in versions.items():
            with self.subTest(service=service):
                self.assertRegex(version, r'^4\.1\.\d+$')

    def test_cloud_train_supports_boot_generation(self):
        for service in SERVICES:
            with self.subTest(service=service):
                version = pom(service).findtext('m:properties/m:spring-cloud.version', namespaces=NS)
                self.assertRegex(version, r'^2025\.1\.\d+$')
                self.assertGreaterEqual(int(version.split('.')[-1]), 2)

    def test_springdoc_generation_matches_boot_and_is_consistent(self):
        versions = []
        for service in ('orchestrator', 'file-service'):
            with self.subTest(service=service):
                dependency = next(dep for dep in pom(service).findall('m:dependencies/m:dependency', NS)
                                  if dep.findtext('m:groupId', namespaces=NS) == 'org.springdoc')
                version = dependency.findtext('m:version', namespaces=NS)
                versions.append(version)
                self.assertRegex(version, r'^3\.1\.\d+$')
        self.assertEqual(1, len(set(versions)))

    def test_micrometer_override_does_not_downgrade_boot_generation(self):
        for service in SERVICES:
            with self.subTest(service=service):
                version = pom(service).findtext('m:properties/m:micrometer.version', namespaces=NS)
                if version is not None:
                    self.assertGreaterEqual(tuple(map(int, version.split('.'))), (1, 17, 1))


if __name__ == '__main__':
    unittest.main(verbosity=2)
