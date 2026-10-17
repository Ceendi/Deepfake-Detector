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
    def test_upload_detection_uses_tika_core_four_without_parsers(self):
        dependencies = [dep for dep in pom('file-service').findall('m:dependencies/m:dependency', NS)
                        if dep.findtext('m:groupId', namespaces=NS) == 'org.apache.tika']
        self.assertEqual(['tika-core'], [dep.findtext('m:artifactId', namespaces=NS)
                                       for dep in dependencies])
        self.assertRegex(dependencies[0].findtext('m:version', namespaces=NS), r'^4\.\d+\.\d+$')

    def test_testcontainers_generation_and_bom_are_consistent(self):
        versions = []
        for service in SERVICES:
            with self.subTest(service=service):
                project = pom(service)
                version = project.findtext('m:properties/m:testcontainers.version', namespaces=NS)
                self.assertIsNotNone(version)
                self.assertRegex(version, r'^2\.\d+\.\d+$')
                versions.append(version)
                for dependency in project.findall('m:dependencyManagement/m:dependencies/m:dependency', NS):
                    if dependency.findtext('m:groupId', namespaces=NS) == 'org.testcontainers':
                        self.assertEqual('testcontainers-bom', dependency.findtext('m:artifactId', namespaces=NS))
                        self.assertEqual('${testcontainers.version}', dependency.findtext('m:version', namespaces=NS))
        self.assertEqual(1, len(set(versions)), versions)

    def test_testcontainers_modules_use_current_coordinates_and_test_scope(self):
        for service in SERVICES:
            for dependency in pom(service).findall('m:dependencies/m:dependency', NS):
                if dependency.findtext('m:groupId', namespaces=NS) != 'org.testcontainers':
                    continue
                artifact = dependency.findtext('m:artifactId', namespaces=NS)
                with self.subTest(service=service, artifact=artifact):
                    self.assertTrue(artifact == 'testcontainers' or artifact.startswith('testcontainers-'), artifact)
                    self.assertEqual('test', dependency.findtext('m:scope', namespaces=NS))
                    self.assertIsNone(dependency.findtext('m:version', namespaces=NS))

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
