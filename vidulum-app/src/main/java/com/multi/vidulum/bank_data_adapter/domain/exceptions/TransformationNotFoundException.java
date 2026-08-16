package com.multi.vidulum.bank_data_adapter.domain.exceptions;

import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;
import lombok.Getter;

@Getter
public class TransformationNotFoundException extends BusinessException {

    private final String transformationId;

    public TransformationNotFoundException(String transformationId) {
        super(String.format("Transformation not found: [%s]", transformationId));
        this.transformationId = transformationId;
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.AI_ADAPTER_TRANSFORMATION_NOT_FOUND;
    }
}
