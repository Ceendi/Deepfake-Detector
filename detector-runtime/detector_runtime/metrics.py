from .consumer import _task_hard_limit_seconds


def inference_duration_buckets():
    # A configured longer attempt must remain visible in finite percentile buckets.
    buckets = (0.1, 0.25, 0.5, 1, 2, 3, 4, 5, 8, 10, 15, 20, 30, 60, 120,
               300, 600, 1200, 1800, 3600)
    hard_limit = _task_hard_limit_seconds()
    return buckets + (hard_limit,) if hard_limit > buckets[-1] else buckets
