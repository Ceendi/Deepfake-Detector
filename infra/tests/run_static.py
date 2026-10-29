"""Container-free guards. Compose config needs the CLI, never a running Docker daemon."""

import sys
import unittest

SUITES = (
    'test_ci_gates', 'test_ci_reliability', 'test_ci_scope', 'test_model_smoke',
    'test_publishing', 'test_workflow_scope', 'test_security_report', 'test_e2e_ml',
    'test_java_platform', 'test_live_acceptance', 'test_test_ports', 'test_compose_images',
    'test_security_configuration', 'test_discovery.DiscoveryConfigurationTest',
    'test_core_reliability.CoreReliabilityConfigurationTest',
    'test_monitoring_configuration.MonitoringConfigurationTest',
)


if __name__ == '__main__':
    suite = unittest.defaultTestLoader.loadTestsFromNames(SUITES)
    sys.exit(not unittest.TextTestRunner(verbosity=2).run(suite).wasSuccessful())
