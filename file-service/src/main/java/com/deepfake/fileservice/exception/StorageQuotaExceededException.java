package com.deepfake.fileservice.exception;

public class StorageQuotaExceededException extends RuntimeException {
    public StorageQuotaExceededException() {
        super("Przekroczono limit miejsca na pliki (5 GB). Usuń stare analizy, aby wgrać nowe.");
    }
}
