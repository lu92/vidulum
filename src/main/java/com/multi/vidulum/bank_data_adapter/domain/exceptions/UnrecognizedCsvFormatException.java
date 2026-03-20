package com.multi.vidulum.bank_data_adapter.domain.exceptions;

import lombok.Getter;

@Getter
public class UnrecognizedCsvFormatException extends RuntimeException {

    private final String detectedHeaders;
    private final String aiErrorMessage;

    public UnrecognizedCsvFormatException(String detectedHeaders, String aiErrorMessage) {
        super(String.format("Could not recognize bank CSV format. AI message: %s", aiErrorMessage));
        this.detectedHeaders = detectedHeaders;
        this.aiErrorMessage = aiErrorMessage;
    }
}
