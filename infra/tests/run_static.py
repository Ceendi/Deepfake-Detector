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
) + tuple('test_observability_configuration.ObservabilityConfigurationTest.' + name for name in (
    'test_detectors_discover_every_replica_through_dns',
    'test_detector_panels_aggregate_instances_before_display',
    'test_core_defaults_do_not_require_monitoring_or_extra_replicas',
    'test_orchestrator_operator_overrides_reach_the_container',
    'test_dashboard_percentiles_and_detector_outcomes_are_provisioned',
    'test_panels_without_observations_explain_the_empty_value',
    'test_idle_percentiles_filter_zero_observation_series',
    'test_file_lifecycle_counters_are_visible_across_replicas',
))


if __name__ == '__main__':
    suite = unittest.defaultTestLoader.loadTestsFromNames(SUITES)
    sys.exit(not unittest.TextTestRunner(verbosity=2).run(suite).wasSuccessful())
