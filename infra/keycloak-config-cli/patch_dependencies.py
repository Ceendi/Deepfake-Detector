"""Apply supported dependency patches to the pinned upstream importer release."""

import sys
import xml.etree.ElementTree as ET
from pathlib import Path

NAMESPACE = 'http://maven.apache.org/POM/4.0.0'
ET.register_namespace('', NAMESPACE)


def tag(name):
    return f'{{{NAMESPACE}}}{name}'


path = Path(sys.argv[1])
tree = ET.parse(path)
root = tree.getroot()
root.find(f'{tag("parent")}/{tag("version")}').text = '3.5.16'
properties = root.find(tag('properties'))
versions = {
    'spring-framework.version': '6.2.19',
    'netty.version': '4.1.138.Final',
    'keycloak.client.version': '26.0.12',
    'jackson.version': '2.22.3',
    'resteasy.version': '7.0.5.Final',
    'logback.version': '1.5.38',
    'tomcat.version': '10.1.60',
}
for name, version in versions.items():
    element = properties.find(tag(name))
    if element is None:
        element = ET.SubElement(properties, tag(name))
    element.text = version

# logstash-logback-encoder 9 also brings Jackson 3; the Jackson 2 BOM does not
# manage these coordinates. Keep both generations on their patched releases.
dependencies = root.find(f'{tag("dependencyManagement")}/{tag("dependencies")}')
for artifact in ('jackson-core', 'jackson-databind'):
    dependency = ET.Element(tag('dependency'))
    for name, value in (
        ('groupId', 'tools.jackson.core'), ('artifactId', artifact), ('version', '3.2.3'),
    ):
        ET.SubElement(dependency, tag(name)).text = value
    dependencies.insert(0, dependency)

tree.write(path, encoding='unicode', xml_declaration=True)
