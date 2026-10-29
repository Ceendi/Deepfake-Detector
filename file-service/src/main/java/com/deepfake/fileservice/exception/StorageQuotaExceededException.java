package com.deepfake.fileservice.exception;

public class StorageQuotaExceededException extends RuntimeException {
    public StorageQuotaExceededException() {
        super("Przekroczono limit miejsca na pliki. Usuń stare analizy – miejsce zwalnia się po okresie retencji.");
    }
}
