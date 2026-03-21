package com.multi.vidulum.bank_data_adapter.domain.exceptions;

import lombok.Getter;

@Getter
public class TransformationNotFoundException extends RuntimeException {

    private final String transformationId;

    public TransformationNotFoundException(String transformationId) {
        super(String.format("Transformation not found: [%s]", transformationId));
        this.transformationId = transformationId;
    }
}
