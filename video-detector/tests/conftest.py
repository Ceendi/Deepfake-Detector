import os

# The runtime refuses to start without credentials; tests never reach S3 or RabbitMQ.
os.environ.setdefault("S3_ACCESS_KEY", "test")
os.environ.setdefault("S3_SECRET_KEY", "test")
os.environ.setdefault("RABBITMQ_PASSWORD", "test")
