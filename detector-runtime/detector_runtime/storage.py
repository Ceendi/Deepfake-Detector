import os

import boto3
import redis
from botocore.client import Config
from redis.backoff import NoBackoff
from redis.retry import Retry


def create_s3_client(endpoint: str, access_key: str, secret_key: str):
    return boto3.client(
        "s3",
        endpoint_url=os.getenv("S3_ENDPOINT", endpoint),
        aws_access_key_id=os.getenv("S3_ACCESS_KEY", access_key),
        aws_secret_access_key=os.getenv("S3_SECRET_KEY", secret_key),
        region_name=os.getenv("S3_REGION", "us-east-1"),
        config=Config(s3={"addressing_style": "path"}),
    )


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
