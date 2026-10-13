class DetectorError(RuntimeError):
    """Domain failure whose string code and deliberate message are published to users."""

    code = "PROCESSING_ERROR"


class MediaTooLongError(DetectorError):
    code = "MEDIA_TOO_LONG"

    def __init__(self):
        super().__init__("Materiał przekracza dopuszczalną długość. Wgraj krótsze nagranie.")
