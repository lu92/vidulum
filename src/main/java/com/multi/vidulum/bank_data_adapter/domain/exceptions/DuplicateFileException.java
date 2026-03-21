package com.multi.vidulum.bank_data_adapter.domain.exceptions;

import lombok.Getter;

@Getter
public class DuplicateFileException extends RuntimeException {

    private final String fileHash;
    private final String existingTransformationId;

    public DuplicateFileException(String fileHash, String existingTransformationId) {
        super(String.format("File with hash [%s] already processed. Existing transformation: [%s]",
            fileHash, existingTransformationId));
        this.fileHash = fileHash;
        this.existingTransformationId = existingTransformationId;
    }
}
