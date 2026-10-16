"""Validate video settings independently of optional model dependencies."""

from detector_runtime.config import choice, number


def load_config():
    return {
        "MEDIA_MAX_DURATION_SECONDS": number("MEDIA_MAX_DURATION_SECONDS", 1800, exclusive=True),
        "MEDIA_MAX_PIXELS": number("MEDIA_MAX_PIXELS", 8294400, integer=True, minimum=1),
        "DECISION_THRESHOLD": number("DECISION_THRESHOLD", 0.5, maximum=1, exclusive=True),
        "FRAME_STRATEGY": choice("FRAME_STRATEGY", "uniform", ("uniform", "middle2s")),
        "ONNX_INTRA_THREADS": number("ONNX_INTRA_THREADS", 4, integer=True, minimum=1),
        "DET_SIZE": number("DET_SIZE", 640, integer=True, minimum=1),
        "MIN_FACES": number("MIN_FACES", 4, integer=True, minimum=1, maximum=16),
        "GRADCAM_TOPK": number("GRADCAM_TOPK", 3, integer=True, minimum=1, maximum=16),
        "HEALTH_MAX_SILENCE_SECONDS": number(
            "HEALTH_MAX_SILENCE_SECONDS", 120, integer=True, minimum=1
        ),
    }
