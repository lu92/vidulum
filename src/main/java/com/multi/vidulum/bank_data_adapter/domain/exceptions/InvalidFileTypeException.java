package com.multi.vidulum.bank_data_adapter.domain.exceptions;

import lombok.Getter;

@Getter
public class InvalidFileTypeException extends RuntimeException {

    private final String detectedType;

    public InvalidFileTypeException(String detectedType) {
        super(String.format("Invalid file type: [%s]. Expected CSV file", detectedType));
        this.detectedType = detectedType;
    }
}
