package com.multi.vidulum.bank_data_adapter.domain.exceptions;

import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;
import lombok.Getter;

@Getter
public class DuplicateFileException extends BusinessException {

    private final String fileHash;
    private final String existingTransformationId;

    public DuplicateFileException(String fileHash, String existingTransformationId) {
        super(String.format("File with hash [%s] already processed. Existing transformation: [%s]",
            fileHash, existingTransformationId));
        this.fileHash = fileHash;
        this.existingTransformationId = existingTransformationId;
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.AI_ADAPTER_DUPLICATE_FILE;
    }
}
