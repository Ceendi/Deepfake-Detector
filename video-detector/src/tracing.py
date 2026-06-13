"""OpenTelemetry setup for the detector consumer.

The tracer is always real, so every processed message gets a valid trace context and
trace_id reaches the structlog output (D2). OTLP export is gated by
OTEL_TRACING_EXPORT_ENABLED — same knob as the Java services — so a dev run without
Tempo doesn't spam a dead endpoint. The export endpoint comes from the standard
OTEL_EXPORTER_OTLP_TRACES_ENDPOINT env var (set in docker-compose).
"""
import os

from opentelemetry import trace
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor


def init_tracing(service_name: str) -> None:
    provider = TracerProvider(resource=Resource.create({"service.name": service_name}))
    if os.getenv("OTEL_TRACING_EXPORT_ENABLED", "false").lower() == "true":
        # Lazy import: the exporter drags in protobuf, needed only when actually exporting.
        from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
        provider.add_span_processor(BatchSpanProcessor(OTLPSpanExporter()))
    trace.set_tracer_provider(provider)
