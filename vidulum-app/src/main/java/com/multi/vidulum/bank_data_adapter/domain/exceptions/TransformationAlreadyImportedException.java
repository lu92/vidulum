package com.multi.vidulum.bank_data_adapter.domain.exceptions;

import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;
import lombok.Getter;

@Getter
public class TransformationAlreadyImportedException extends BusinessException {

    private final String transformationId;
    private final String stagingSessionId;

    public TransformationAlreadyImportedException(String transformationId, String stagingSessionId) {
        super(String.format("Transformation [%s] has already been imported. Staging session: [%s]",
            transformationId, stagingSessionId));
        this.transformationId = transformationId;
        this.stagingSessionId = stagingSessionId;
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.AI_ADAPTER_ALREADY_IMPORTED;
    }
}
