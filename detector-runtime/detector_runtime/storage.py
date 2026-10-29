import os

import boto3
import redis
from boto3.exceptions import RetriesExceededError
from botocore.client import Config
from botocore.exceptions import ClientError, HTTPClientError
from botocore.exceptions import ConnectionError as BotoConnectionError
from redis.backoff import NoBackoff
from redis.retry import Retry
from s3transfer.exceptions import RetriesExceededError as TransferRetriesExceededError

# Retryable per botocore's own rules, even when reported with a non-5xx status.
_TRANSIENT_S3_CODES = {
    "SlowDown", "RequestTimeout", "RequestTimeoutException", "Throttling",
    "ThrottlingException", "RequestThrottled", "TooManyRequestsException", "ServiceUnavailable",
}


def required_env(name: str) -> str:
    value = os.getenv(name)
    if not value:
        raise RuntimeError(f"{name} must be set")
    return value


def create_s3_client(endpoint: str):
    return boto3.client(
        "s3",
        endpoint_url=os.getenv("S3_ENDPOINT", endpoint),
        aws_access_key_id=required_env("S3_ACCESS_KEY"),
        aws_secret_access_key=required_env("S3_SECRET_KEY"),
        region_name=os.getenv("S3_REGION", "us-east-1"),
        # AMQP owns the outage retry budget; SDK retries would multiply each attempt.
        config=Config(connect_timeout=5, read_timeout=10,
                      retries={"mode": "standard", "total_max_attempts": 1},
                      s3={"addressing_style": "path"}),
    )


def is_transient_storage_error(error: BaseException) -> bool:
    """Connection, timeout, 5xx and throttling failures; missing or forbidden objects are not."""
    seen = set()
    while error is not None and id(error) not in seen:
        seen.add(id(error))
        if isinstance(error, (BotoConnectionError, HTTPClientError, RetriesExceededError,
                              TransferRetriesExceededError)):
            return True
        if isinstance(error, ClientError):
            status = error.response.get("ResponseMetadata", {}).get("HTTPStatusCode") or 0
            code = error.response.get("Error", {}).get("Code", "")
            return status >= 500 or status == 429 or code in _TRANSIENT_S3_CODES or (
                code.isdigit() and (int(code) >= 500 or int(code) == 429))
        # boto3 wraps upload ClientErrors without an explicit cause.
        error = error.__cause__ or error.__context__ or getattr(error, "last_exception", None)
    return False


def create_redis_client():
    return redis.Redis(
        host=os.getenv("REDIS_HOST", "localhost"),
        port=int(os.getenv("REDIS_PORT", "6379")),
        password=os.getenv("REDIS_PASSWORD"),
        socket_connect_timeout=1,
        socket_timeout=1,
        retry=Retry(NoBackoff(), 0),
        db=0,
        decode_responses=True,
    )
