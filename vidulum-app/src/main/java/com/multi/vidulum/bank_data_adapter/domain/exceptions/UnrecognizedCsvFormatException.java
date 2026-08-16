package com.multi.vidulum.bank_data_adapter.domain.exceptions;

import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;
import lombok.Getter;

@Getter
public class UnrecognizedCsvFormatException extends BusinessException {

    private final String detectedHeaders;
    private final String aiErrorMessage;

    public UnrecognizedCsvFormatException(String detectedHeaders, String aiErrorMessage) {
        super(String.format("Could not recognize bank CSV format. AI message: %s", aiErrorMessage));
        this.detectedHeaders = detectedHeaders;
        this.aiErrorMessage = aiErrorMessage;
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.AI_ADAPTER_UNRECOGNIZED_FORMAT;
    }
}
