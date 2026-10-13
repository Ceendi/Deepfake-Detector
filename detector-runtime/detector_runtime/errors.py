class MediaTooLongError(RuntimeError):
    code = "MEDIA_TOO_LONG"

    def __init__(self):
        super().__init__("Materiał przekracza dopuszczalną długość. Wgraj krótsze nagranie.")
