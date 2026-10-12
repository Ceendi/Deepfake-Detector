from unittest.mock import MagicMock

import pytest
from opentelemetry import trace
from opentelemetry.sdk.trace import TracerProvider

from detector_runtime import DetectorRuntime

trace.set_tracer_provider(TracerProvider())


@pytest.fixture(params=["audio", "video"])
def runtime(request):
    redis = MagicMock()
    redis.exists.return_value = 0
    return DetectorRuntime(source=request.param, queue=f"analysis.{request.param}",
                           log=MagicMock(), tracer=trace.get_tracer("src.consumer"),
                           s3_client=MagicMock(), redis_client=redis,
                           process_task=MagicMock(), record_outcome=MagicMock(),
                           consume_iterator=request.param == "audio")


@pytest.fixture
def rds(runtime):
    return runtime.redis_client


