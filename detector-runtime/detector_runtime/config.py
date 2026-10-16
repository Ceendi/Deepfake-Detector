"""Environment validation without importing model libraries or exposing configured values."""

import math
import os

import structlog

from .storage import required_env


def number(name, default, *, integer=False, minimum=0, maximum=None, exclusive=False):
    try:
        value = (int if integer else float)(os.getenv(name, str(default)))
    except ValueError:
        raise ValueError(f"{name} must be a valid {'integer' if integer else 'number'}") from None
    if (
        (not integer and not math.isfinite(value))
        or value < minimum
        or (exclusive and value == minimum)
        or (maximum is not None and (value > maximum or (exclusive and value == maximum)))
    ):
        raise ValueError(f"{name} is outside its allowed range")
    return value


def choice(name, default, allowed):
    value = os.getenv(name, default)
    if value not in allowed:
        raise ValueError(f"{name} must be one of {', '.join(allowed)}")
    return value


def validate_startup(load):
    try:
        settings = load()
        number("DETECTOR_TASK_HARD_LIMIT_SECONDS", 3600, exclusive=True)
        for name in ("RABBITMQ_PASSWORD", "S3_ACCESS_KEY", "S3_SECRET_KEY"):
            required_env(name)
        number("RABBITMQ_PORT", 5672, integer=True, minimum=1, maximum=65535)
        number("REDIS_PORT", 6379, integer=True, minimum=1, maximum=65535)
        return settings
    except (ValueError, RuntimeError) as error:
        structlog.get_logger(__name__).error("invalid_configuration", reason=str(error))
        raise SystemExit(1) from None
