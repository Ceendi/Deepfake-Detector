"""Validate audio settings independently of optional model dependencies."""

from detector_runtime.config import number


def load_config():
    return {
        "MEL_EER_THRESHOLD": number("MEL_EER_THRESHOLD", 0.3049, maximum=1, exclusive=True),
        "W2V2_EER_THRESHOLD": number("W2V2_EER_THRESHOLD", 0.3000, maximum=1, exclusive=True),
        "MEDIA_MAX_DURATION_SECONDS": number("MEDIA_MAX_DURATION_SECONDS", 1800, exclusive=True),
        "ONNX_INTRA_THREADS": number("ONNX_INTRA_THREADS", 0, integer=True),
    }
