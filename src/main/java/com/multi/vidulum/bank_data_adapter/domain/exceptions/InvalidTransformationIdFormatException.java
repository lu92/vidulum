package com.multi.vidulum.bank_data_adapter.domain.exceptions;

import lombok.Getter;

@Getter
public class InvalidTransformationIdFormatException extends RuntimeException {

    private final String providedId;

    public InvalidTransformationIdFormatException(String providedId) {
        super(String.format("Invalid transformation ID format: [%s]. Expected UUID format", providedId));
        this.providedId = providedId;
    }
}
