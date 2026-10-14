class DetectorError(RuntimeError):
    """Domain failure whose string code and deliberate message are published to users."""

    code = "PROCESSING_ERROR"


class MediaTooLongError(DetectorError):
    code = "MEDIA_TOO_LONG"

    def __init__(self):
        super().__init__("Materiał przekracza dopuszczalną długość. Wgraj krótsze nagranie.")


class ModelUnavailableError(DetectorError):
    """Required inference models did not initialize successfully."""

    code = "MODEL_UNAVAILABLE"

    def __init__(self):
        super().__init__("Usługa analizy jest chwilowo niedostępna. Spróbuj ponownie później.")


class StorageUnavailableError(Exception):
    """Transient object storage failure; the task is redelivered instead of failed."""
